package io.github.stupidgiraffe.kizuku.server;

import android.os.Binder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.Os;
import android.system.OsConstants;
import frb.axeron.server.FileStat;
import frb.axeron.server.IFileService;
import frb.axeron.server.IOutputStreamCallback;
import io.github.stupidgiraffe.kizuku.api.Protocol;
import io.github.stupidgiraffe.kizuku.core.Capability;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/** The Axeron file contract, always using the current host's actual Linux privileges. */
final class AxFileService extends IFileService.Stub {
    private final KizukuService host;
    private final ClientIdentity owner;
    // No queued open files: rejected transfers close their file and pipe in the caller.
    private final ExecutorService streams = new java.util.concurrent.ThreadPoolExecutor(0, 2, 30,
        java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>());
    private final Set<ParcelFileDescriptor> active = ConcurrentHashMap.newKeySet();

    AxFileService(KizukuService host, ClientIdentity owner) {
        this.host = host;
        this.owner = owner;
    }

    private void authorize() {
        if (Binder.getCallingUid() != owner.uid() ||
                !host.allowed(owner, Capability.AXERON, true))
            throw new SecurityException("File access requires this client's Axeron authorization");
    }

    @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (data.dataSize() > Protocol.MAX_IPC_BYTES) throw new SecurityException("IPC size limit exceeded");
        if (code != INTERFACE_TRANSACTION) authorize();
        return super.onTransact(code, data, reply, flags);
    }

    private static File path(String value) {
        if (value == null || value.length() > 4096 || value.indexOf(0) >= 0 || !value.startsWith("/"))
            throw new IllegalArgumentException("An absolute file path is required");
        return new File(value);
    }

    private interface FileOperation { boolean run() throws Exception; }

    private static boolean attempt(FileOperation operation) {
        try { return operation.run(); }
        catch (Exception failure) { return false; }
    }

    @Override public boolean mkdirs(String name) {
        File file = path(name);
        return file.isDirectory() || file.mkdirs();
    }

    @Override public boolean createNewFile(String name) {
        return attempt(() -> path(name).createNewFile());
    }

    @Override public boolean delete(String name) { return path(name).delete(); }
    @Override public boolean exists(String name) { return path(name).exists(); }

    @Override public boolean fsync(ParcelFileDescriptor descriptor) {
        try {
            if (descriptor == null) return false;
            Os.fsync(descriptor.getFileDescriptor());
            return true;
        } catch (Exception failure) { return false; }
        finally { close(descriptor); }
    }

    @Override public FileStat stat(String name) {
        File file = path(name);
        return metadata(file);
    }

    private static FileStat metadata(File file) {
        String canonical;
        try { canonical = file.getCanonicalPath(); }
        catch (IOException failure) { canonical = ""; }
        return new FileStat(file.getPath(), file.getName(), file.getParent(), file.exists(),
            file.isFile(), file.isDirectory(), file.isHidden(), file.length(), file.lastModified(), canonical);
    }

    @Override public String getCanonicalPath(String name) {
        try { return path(name).getCanonicalPath(); }
        catch (IOException failure) { return ""; }
    }

    @Override public List<FileStat> list(String name, int filter, int sortBy, boolean ascending, int offset, int limit) {
        if (filter < FILTER_ALL || filter > FILTER_DIRECTORIES || sortBy < SORT_NAME || sortBy > SORT_LENGTH ||
                offset < 0 || limit < -1 || limit > 4096) throw new IllegalArgumentException("Invalid directory page");
        File[] files = path(name).listFiles();
        if (files == null) throw new IllegalStateException("Cannot read this directory");
        Comparator<File> comparator = switch (sortBy) {
            case SORT_LAST_MOD -> Comparator.comparingLong(File::lastModified);
            case SORT_LENGTH -> Comparator.comparingLong(File::length);
            default -> Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER);
        };
        comparator = comparator.thenComparing(File::getName);
        if (!ascending) comparator = comparator.reversed();
        List<FileStat> result = Arrays.stream(files)
            .filter(file -> filter == FILTER_ALL || filter == FILTER_FILES && file.isFile() ||
                filter == FILTER_DIRECTORIES && file.isDirectory())
            .sorted(comparator).skip(offset).limit(limit <= 0 ? 4097 : limit).map(AxFileService::metadata).toList();
        Parcel encoded = Parcel.obtain();
        try {
            encoded.writeTypedList(result);
            if (result.size() > 4096 || encoded.dataSize() > Protocol.MAX_IPC_BYTES)
                throw new IllegalArgumentException("Directory is too large. Use offset and limit to read it in pages.");
        } finally { encoded.recycle(); }
        return result;
    }

    @Override public boolean setLastModified(String name, long time) { return path(name).setLastModified(time); }
    @Override public boolean chmod(String name, int mode) {
        if ((mode & ~07777) != 0) throw new IllegalArgumentException("Invalid file mode");
        return attempt(() -> { Os.chmod(path(name).getPath(), mode); return true; });
    }
    @Override public boolean chown(String name, int uid, int gid) {
        return attempt(() -> { Os.chown(path(name).getPath(), uid, gid); return true; });
    }

    @Override public boolean inputStreamPfd(String name, ParcelFileDescriptor output) {
        InputStream source = null;
        try {
            checkPipe(output, OsConstants.O_WRONLY);
            source = Files.newInputStream(path(name).toPath());
            InputStream input = source;
            begin(output, () -> {
                try (input; OutputStream target = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                    copy(input, target);
                } catch (IOException ignored) {
                    // The upstream read contract reports transfer failures as EOF.
                }
            });
            return true;
        } catch (Exception failure) {
            if (source != null) try { source.close(); } catch (IOException ignored) {}
            close(output);
            return false;
        }
    }

    @Override public void outputStreamPfd(String name, ParcelFileDescriptor input,
            IOutputStreamCallback callback, boolean append) {
        OutputStream target = null;
        try {
            checkPipe(input, OsConstants.O_RDONLY);
            target = new java.io.FileOutputStream(path(name), append);
            OutputStream output = target;
            begin(input, () -> {
                try (output; InputStream source = new ParcelFileDescriptor.AutoCloseInputStream(input)) {
                    copy(source, output);
                    if (output instanceof java.io.FileOutputStream file) file.getFD().sync();
                    if (callback != null) callback.onComplete();
                } catch (Exception failure) { error(callback, failure); }
            });
        } catch (Exception failure) {
            if (target != null) try { target.close(); } catch (IOException ignored) {}
            close(input);
            error(callback, failure);
        }
    }

    private void begin(ParcelFileDescriptor descriptor, Runnable transfer) {
        if (active.size() >= 8 || !active.add(descriptor)) throw new IllegalStateException("Too many file transfers");
        try {
            streams.execute(() -> {
                try { transfer.run(); }
                finally { active.remove(descriptor); close(descriptor); }
            });
        } catch (RuntimeException failure) {
            active.remove(descriptor);
            throw failure;
        }
    }

    private void copy(InputStream source, OutputStream target) throws IOException {
        byte[] bytes = new byte[32768];
        for (int count; (count = source.read(bytes)) != -1;) {
            if (!host.activeIdentity(owner))
                throw new IOException("File authorization ended");
            target.write(bytes, 0, count);
        }
        target.flush();
    }

    private static void checkPipe(ParcelFileDescriptor descriptor, int direction) throws Exception {
        if (descriptor == null) throw new IllegalArgumentException("Missing file pipe");
        LegacyRish.enforcePipe(descriptor, direction);
    }

    @Override public boolean renameTo(String from, String to, boolean overwrite) {
        return attempt(() -> {
            if (overwrite) Files.move(path(from).toPath(), path(to).toPath(), StandardCopyOption.REPLACE_EXISTING);
            else Files.move(path(from).toPath(), path(to).toPath());
            return true;
        });
    }

    private static void error(IOutputStreamCallback callback, Exception failure) {
        if (callback == null) return;
        try { callback.onError(IOutputStreamCallback.IO_EXCEPTION, failure.getMessage()); }
        catch (RemoteException gone) { /* The client closed its callback. */ }
    }

    void close() {
        active.forEach(AxFileService::close);
        active.clear();
        streams.shutdownNow();
    }

    private static void close(ParcelFileDescriptor descriptor) {
        if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) {}
    }
}
