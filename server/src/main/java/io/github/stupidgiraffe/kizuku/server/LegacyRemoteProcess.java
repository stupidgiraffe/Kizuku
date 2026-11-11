package io.github.stupidgiraffe.kizuku.server;

import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import moe.shizuku.server.IRemoteProcess;

/** Stock process Binder with ownership checks on the returned handle and bounded pipe workers. */
final class LegacyRemoteProcess extends IRemoteProcess.Stub {
    private static final String PROCESS_MARKER = "KIZUKU_LEGACY_PROCESS";
    private final KizukuService host;
    final ClientIdentity owner;
    final int ownerPid;
    private final String marker = UUID.randomUUID().toString();
    private final java.lang.Process child;
    private final AtomicBoolean waiting = new AtomicBoolean();
    private volatile boolean closed;
    private boolean stdinTaken;
    private boolean stdoutTaken;
    private boolean stderrTaken;

    LegacyRemoteProcess(KizukuService host, ClientIdentity owner, int ownerPid,
            String[] argv, String[] env, String directory) throws IOException {
        this.host = host;
        this.owner = owner;
        this.ownerPid = ownerPid;
        if (argv == null || argv.length == 0 || argv.length > 256)
            throw new IllegalArgumentException("A command is required");
        for (String arg : argv) {
            if (arg == null || arg.length() > 32_768 || arg.indexOf(0) >= 0)
                throw new IllegalArgumentException("Invalid command argument");
        }
        ProcessBuilder builder = new ProcessBuilder(argv);
        if (directory != null) builder.directory(new File(directory));
        Map<String, String> environment = builder.environment();
        if (env != null) {
            if (env.length > 256) throw new IllegalArgumentException("Too many environment variables");
            environment.clear();
            for (String entry : env) {
                int separator = entry == null ? -1 : entry.indexOf('=');
                if (separator <= 0 || entry.indexOf(0) >= 0)
                    throw new IllegalArgumentException("Invalid environment assignment");
                environment.put(entry.substring(0, separator), entry.substring(separator + 1));
            }
        }
        environment.put(PROCESS_MARKER, marker);
        child = builder.start();
    }

    private void enforceOwner() {
        if (closed || Binder.getCallingUid() != owner.uid() || !host.activeIdentity(owner))
            throw new SecurityException("This Shizuku process does not belong to the caller");
    }

    boolean running() { return child.isAlive(); }

    void stop() {
        if (closed) return;
        closed = true;
        child.destroyForcibly();
        stopMarkedChildren();
    }

    // Shell commands may leave background descendants holding their inherited process marker.
    void reapDescendants() { stopMarkedChildren(); }

    private void stopMarkedChildren() { reapMarkedChildren(PROCESS_MARKER + "=" + marker, false); }
    static void reapMarkedChildren(String marker, boolean prefix) { scanMarkedChildren(marker, prefix, true); }
    static boolean hasMarkedChildren(String marker) { return scanMarkedChildren(marker, false, false); }

    private static boolean scanMarkedChildren(String marker, boolean prefix, boolean kill) {
        File[] processes = new File("/proc").listFiles();
        if (processes == null) return false;
        int uid = android.os.Process.myUid();
        for (File process : processes) {
            try {
                int pid = Integer.parseInt(process.getName());
                if (pid == android.os.Process.myPid() || Os.stat(process.getPath()).st_uid != uid) continue;
                try (InputStream input = new java.io.FileInputStream(new File(process, "environ"))) {
                    byte[] buffer = new byte[32 * 1024];
                    int used = 0;
                    while (used < buffer.length) {
                        int count = input.read(buffer, used, buffer.length - used);
                        if (count <= 0) break;
                        used += count;
                    }
                    for (String entry : new String(buffer, 0, used, StandardCharsets.UTF_8).split("\u0000")) {
                        if (prefix ? entry.startsWith(marker) : entry.equals(marker)) {
                            if (!kill) return true;
                            Os.kill(pid, OsConstants.SIGKILL);
                            break;
                        }
                    }
                }
            } catch (Exception goneOrInaccessible) {
                // Processes can disappear or forbid inspection between enumeration and use.
            }
        }
        return false;
    }

    @Override public synchronized ParcelFileDescriptor getOutputStream() {
        enforceOwner();
        if (stdinTaken) throw new IllegalStateException("Process stdin is already open");
        ParcelFileDescriptor[] pipe = pipe();
        stdinTaken = true;
        pump(new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]), child.getOutputStream());
        return pipe[1];
    }

    @Override public synchronized ParcelFileDescriptor getInputStream() {
        enforceOwner();
        if (stdoutTaken) throw new IllegalStateException("Process stdout is already open");
        stdoutTaken = true;
        return output(child.getInputStream());
    }

    @Override public synchronized ParcelFileDescriptor getErrorStream() {
        enforceOwner();
        if (stderrTaken) throw new IllegalStateException("Process stderr is already open");
        stderrTaken = true;
        return output(child.getErrorStream());
    }

    private static ParcelFileDescriptor output(InputStream source) {
        ParcelFileDescriptor[] pipe = pipe();
        pump(source, new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]));
        return pipe[0];
    }

    private static ParcelFileDescriptor[] pipe() {
        try { return ParcelFileDescriptor.createPipe(); }
        catch (IOException failure) { throw new IllegalStateException("Cannot open a process stream", failure); }
    }

    private static void pump(InputStream from, OutputStream to) {
        Thread worker = new Thread(() -> {
            try (from; to) {
                byte[] buffer = new byte[8192];
                for (int count; (count = from.read(buffer)) != -1;) {
                    to.write(buffer, 0, count);
                    to.flush();
                }
            } catch (IOException closed) {
                // A caller or child can close a stream independently.
            }
        }, "kizuku-legacy-pipe");
        worker.setDaemon(true);
        worker.start();
    }

    @Override public int waitFor() {
        enforceOwner();
        if (!waiting.compareAndSet(false, true)) throw new IllegalStateException("Already waiting for this process");
        try { return child.waitFor(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for process", interrupted);
        } finally { waiting.set(false); }
    }

    @Override public boolean waitForTimeout(long timeout, String unitName) {
        enforceOwner();
        if (!waiting.compareAndSet(false, true)) throw new IllegalStateException("Already waiting for this process");
        try { return child.waitFor(timeout, TimeUnit.valueOf(unitName)); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for process", interrupted);
        } finally { waiting.set(false); }
    }

    @Override public int exitValue() { enforceOwner(); return child.exitValue(); }
    @Override public boolean alive() { enforceOwner(); return child.isAlive(); }
    @Override public void destroy() { enforceOwner(); stop(); }
}
