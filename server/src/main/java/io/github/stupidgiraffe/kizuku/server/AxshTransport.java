package io.github.stupidgiraffe.kizuku.server;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import frb.axeron.server.IAxshProcess;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.IntFunction;

/** Axeron axsh v1 wire served inside the existing host. Every socket uses SO_PEERCRED. */
final class AxshTransport implements AutoCloseable {
    private final AxeronEndpoint.ProcessLauncher launcher;
    private final IntFunction<ClientIdentity> authorize;
    private final java.util.concurrent.ExecutorService io = Executors.newCachedThreadPool();
    private final Set<LocalSocket> sockets = ConcurrentHashMap.newKeySet();
    private final java.util.Map<ParcelFileDescriptor, ClientIdentity> channels = new ConcurrentHashMap<>();
    private final java.util.Map<LocalSocket, ClientIdentity> owners = new ConcurrentHashMap<>();
    private final java.util.function.Predicate<ClientIdentity> active;
    private final Semaphore slots = new Semaphore(16);
    private volatile boolean closed;
    private LocalServerSocket listener;
    private String name;

    AxshTransport(AxeronEndpoint.ProcessLauncher launcher, IntFunction<ClientIdentity> authorize,
            java.util.function.Predicate<ClientIdentity> active) {
        this.launcher = launcher;
        this.authorize = authorize;
        this.active = active;
    }

    synchronized String socketName() {
        if (closed) return null;
        if (listener != null) return name;
        try {
            name = "kizuku.axsh." + UUID.randomUUID();
            listener = new LocalServerSocket(name);
            io.execute(() -> {
                while (!closed) {
                    try { take(listener.accept(), null); }
                    catch (Exception stopped) { break; }
                }
            });
            return name;
        } catch (IOException failure) { name = null; return null; }
    }

    ParcelFileDescriptor channel(ClientIdentity owner) throws Exception {
        if (closed || channels.size() >= 8) throw new IllegalStateException("Axsh channel limit reached");
        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
        channels.put(pair[0], owner);
        io.execute(() -> {
            try (LocalSocket channel = socket(pair[0].getFileDescriptor())) {
                InputStream input = channel.getInputStream();
                while (!closed && input.read() != -1) {
                    FileDescriptor[] descriptors = channel.getAncillaryFileDescriptors();
                    if (descriptors == null) continue;
                    for (FileDescriptor descriptor : descriptors) {
                        try { take(socket(descriptor), owner); }
                        catch (Exception invalid) { try { Os.close(descriptor); } catch (Exception ignored) {} }
                    }
                }
            } catch (Exception ended) {
                // Closing or revoking the channel ends the helper's connection.
            } finally {
                channels.remove(pair[0]);
                close(pair[0]);
            }
        });
        return pair[1];
    }

    private static LocalSocket socket(FileDescriptor descriptor) throws Exception {
        var constructor = LocalSocket.class.getDeclaredConstructor(FileDescriptor.class);
        constructor.setAccessible(true);
        return constructor.newInstance(descriptor);
    }

    private void take(LocalSocket socket, ClientIdentity expected) throws IOException {
        if (closed || !slots.tryAcquire()) { socket.close(); return; }
        sockets.add(socket);
        io.execute(() -> {
            try (socket) {
                var peer = socket.getPeerCredentials();
                ClientIdentity owner = expected == null ? authorize.apply(peer.getUid()) : expected;
                if (owner == null || owner.uid() != peer.getUid() || !active.test(owner)) return;
                owners.put(socket, owner);
                serve(socket, owner);
            } catch (Exception invalid) {
                // A malformed or disconnected client owns only its own session.
            } finally {
                sockets.remove(socket);
                owners.remove(socket);
                slots.release();
            }
        });
    }

    private void serve(LocalSocket socket, ClientIdentity owner) throws Exception {
        socket.setSoTimeout(15_000);
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        FileDescriptor[] incoming = null;
        List<ParcelFileDescriptor> descriptors = new ArrayList<>();
        IAxshProcess process = null;
        try {
            byte[] header = read(input, 6);
            incoming = socket.getAncillaryFileDescriptors();
            if (header[0] != 'A' || header[1] != 'X' || header[2] != 'S' || header[3] != 'H' ||
                    header[4] != 1 || (header[5] & ~3) != 0) throw new IOException("Invalid axsh handshake");
            boolean pty = (header[5] & 1) != 0;
            String[] argv = strings(input);
            String[] requested = strings(input);
            long size = pty ? size(read(input, 4)) : 0;
            if (incoming == null || incoming.length != (pty ? 2 : 3))
                throw new IOException("Axsh requires directional standard stream pipes");
            for (FileDescriptor descriptor : incoming) descriptors.add(ParcelFileDescriptor.dup(descriptor));
            LegacyRish.enforcePipe(descriptors.get(0), OsConstants.O_RDONLY);
            LegacyRish.enforcePipe(descriptors.get(1), OsConstants.O_WRONLY);
            if (!pty) LegacyRish.enforcePipe(descriptors.get(2), OsConstants.O_WRONLY);
            List<String> environment = new ArrayList<>();
            if (pty) for (String entry : requested) {
                String key = entry.substring(0, Math.max(0, entry.indexOf('=')));
                if (key.equals("TERM") || key.equals("COLORTERM") || key.equals("LANG") || key.startsWith("LC_"))
                    environment.add(entry);
            }
            ParcelFileDescriptor[] status = ParcelFileDescriptor.createPipe();
            try {
                process = launcher.launch(owner, argv, environment.toArray(new String[0]), null,
                    descriptors.get(0), descriptors.get(1), pty ? null : descriptors.get(2), pty, size, status[1]);
                descriptors.clear(); // launch transfers these descriptors to the runtime.
                closeRaw(incoming);
                incoming = null;
                output.write(0);
                output.flush();
                socket.setSoTimeout(0);
                IAxshProcess active = process;
                java.util.concurrent.atomic.AtomicBoolean exited = new java.util.concurrent.atomic.AtomicBoolean();
                io.execute(() -> {
                    try {
                        while (input.read() == 3) active.setWindowSize(size(read(input, 4)));
                    } catch (Exception ended) {
                        // EOF or invalid resize ends this client's process.
                    } finally {
                        if (!exited.get()) try { active.destroy(); } catch (Exception ignored) {}
                    }
                });
                byte[] code;
                try (InputStream report = new ParcelFileDescriptor.AutoCloseInputStream(status[0])) {
                    code = read(report, 4);
                }
                exited.set(true);
                output.write(2);
                output.write(code);
                output.flush();
            } finally { close(status[0]); close(status[1]); }
        } catch (Exception failure) {
            if (process == null) {
                byte[] message = "Axsh request failed".getBytes(StandardCharsets.UTF_8);
                try {
                    output.write(failure instanceof SecurityException ? 1 : 2);
                    output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(message.length).array());
                    output.write(message);
                    output.flush();
                } catch (IOException disconnected) {}
            }
            throw failure;
        } finally {
            if (process != null) try { process.destroy(); } catch (Exception ignored) {}
            descriptors.forEach(AxshTransport::close);
            closeRaw(incoming);
        }
    }

    private static String[] strings(InputStream input) throws IOException {
        int count = ByteBuffer.wrap(read(input, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (count < 0 || count > 256) throw new IOException("Too many axsh arguments");
        String[] result = new String[count];
        int remaining = 128 * 1024;
        for (int i = 0; i < count; i++) {
            int length = ByteBuffer.wrap(read(input, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (length < 0 || length > 32768 || length > remaining) throw new IOException("Invalid axsh string");
            result[i] = new String(read(input, length), StandardCharsets.UTF_8);
            if (result[i].indexOf(0) >= 0) throw new IOException("Invalid axsh string");
            remaining -= length;
        }
        return result;
    }

    private static byte[] read(InputStream input, int count) throws IOException {
        byte[] bytes = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = input.read(bytes, offset, count - offset);
            if (read < 0) throw new java.io.EOFException();
            offset += read;
        }
        return bytes;
    }

    private static long size(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return (buffer.getShort() & 0xffffL) | ((buffer.getShort() & 0xffffL) << 16);
    }

    void revoke() {
        sockets.forEach(socket -> {
            try { socket.shutdownInput(); socket.shutdownOutput(); } catch (IOException ignored) {}
        });
        channels.keySet().forEach(descriptor -> {
            try { Os.shutdown(descriptor.getFileDescriptor(), OsConstants.SHUT_RDWR); } catch (Exception ignored) {}
        });
    }

    void revoke(ClientIdentity owner) {
        owners.forEach((socket, identity) -> {
            if (owner.equals(identity)) try { socket.shutdownInput(); socket.shutdownOutput(); } catch (IOException ignored) {}
        });
        channels.forEach((descriptor, identity) -> {
            if (owner.equals(identity)) try { Os.shutdown(descriptor.getFileDescriptor(), OsConstants.SHUT_RDWR); }
                catch (Exception ignored) {}
        });
    }

    @Override public void close() {
        closed = true;
        revoke();
        if (listener != null) try { listener.close(); } catch (IOException ignored) {}
        io.shutdownNow();
    }

    private static void close(ParcelFileDescriptor descriptor) {
        if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) {}
    }
    private static void closeRaw(FileDescriptor[] descriptors) {
        if (descriptors != null) for (FileDescriptor descriptor : descriptors)
            try { Os.close(descriptor); } catch (Exception ignored) {}
    }
}
