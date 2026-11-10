package io.github.stupidgiraffe.kizuku.server;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;
import io.github.stupidgiraffe.kizuku.api.Protocol;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;

/** Stock rish transactions. Shells are owned by the attached app and calling process. */
final class LegacyRish {
    private static final int CREATE = 30000;
    private static final int RESIZE = 30001;
    private static final int EXIT_CODE = 30002;
    private static final int RUNNING = Integer.MAX_VALUE;
    private static final class Session {
        final ClientIdentity identity;
        long handle;
        int exit = RUNNING;
        Session(ClientIdentity identity, long handle) {
            this.identity = identity;
            this.handle = handle;
        }

        void close() {
            if (handle != 0) destroy(handle);
            handle = 0;
        }
    }
    private final Map<Integer, Session> sessions = new HashMap<>();
    private final KizukuService host;
    private boolean loaded;

    LegacyRish(KizukuService host) { this.host = host; }

    static boolean handles(int code) { return code >= CREATE && code <= EXIT_CODE; }

    void stop(Predicate<ClientIdentity> matches) {
        for (Iterator<Session> iterator = sessions.values().iterator(); iterator.hasNext();) {
            Session session = iterator.next();
            if (matches.test(session.identity)) {
                session.close();
                iterator.remove();
            }
        }
    }

    void stopCaller(int uid, int pid) {
        Session session = sessions.get(pid);
        if (session != null && session.identity.uid() == uid) {
            session.close();
            sessions.remove(pid);
        }
    }

    void transact(ClientIdentity identity, int pid, int code, Parcel data, Parcel reply) {
        data.enforceInterface("moe.shizuku.server.IShizukuService");
        Session session = sessions.get(pid);
        if (session != null && !session.identity.equals(identity)) throw new SecurityException("Shell owner changed");
        if (code == CREATE) {
            if (reply == null) return;
            if (session != null && exitCode(session) != RUNNING) {
                session.close();
                sessions.remove(pid);
                session = null;
            }
            if (session != null || sessions.values().stream().filter(item -> item.handle != 0).count() >= 12)
                throw new IllegalStateException("Shell session limit reached");
            sessions.put(pid, new Session(identity, create(data)));
            reply.writeNoException();
        } else if (code == RESIZE) {
            long size = data.readLong();
            if (session != null && session.handle != 0) resize(session.handle, size);
            if (reply != null) reply.writeNoException();
        } else if (reply != null) {
            int result = session == null ? -1 : exitCode(session);
            reply.writeNoException();
            reply.writeInt(result);
        }
    }

    private static int exitCode(Session session) {
        if (session.handle != 0) {
            session.exit = status(session.handle);
            if (session.exit != RUNNING) session.close();
        }
        return session.exit;
    }

    private static void enforcePipe(ParcelFileDescriptor fd, int direction) throws ErrnoException {
        if (!OsConstants.S_ISFIFO(Os.fstat(fd.getFileDescriptor()).st_mode)
            || (Os.fcntlInt(fd.getFileDescriptor(), OsConstants.F_GETFL, 0) & OsConstants.O_ACCMODE) != direction)
            throw new IllegalArgumentException("Shell transport requires directional pipes");
    }

    private long create(Parcel data) {
        byte tty = data.readByte();
        if ((tty & ~7) != 0) throw new IllegalArgumentException("Invalid terminal mode");
        try (ParcelFileDescriptor in = data.readFileDescriptor();
             ParcelFileDescriptor out = data.readFileDescriptor();
             ParcelFileDescriptor err = (tty & 4) == 0 ? data.readFileDescriptor() : null) {
            String[] args = data.createStringArray();
            String[] env = data.createStringArray();
            String dir = data.readString();
            if (in == null || out == null || (err == null && (tty & 4) == 0) || args == null)
                throw new IllegalArgumentException("Missing shell arguments or descriptors");
            enforcePipe(in, OsConstants.O_RDONLY);
            enforcePipe(out, OsConstants.O_WRONLY);
            if (err != null) enforcePipe(err, OsConstants.O_WRONLY);
            boolean preserve = Process.myUid() == 0;
            if (env != null) {
                for (String value : env) {
                    if ("RISH_PRESERVE_ENV=1".equals(value)) { preserve = true; break; }
                    if ("RISH_PRESERVE_ENV=0".equals(value)) { preserve = false; break; }
                }
            }
            if (!loaded) {
                String path = host.platform.packageInfo(Protocol.MANAGER, 0).applicationInfo.nativeLibraryDir;
                System.load(new File(path, "librish.so").getCanonicalPath());
                loaded = true;
            }
            long handle = start(encode(args), preserve ? encode(env) : null, encode(dir), tty,
                in.getFd(), out.getFd(), err == null ? -1 : err.getFd());
            if (handle == 0) throw new IllegalStateException("Could not create shell");
            return handle;
        } catch (Exception failure) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Could not create shell", failure);
        }
    }

    private static byte[] encode(String text) {
        if (text == null) return null;
        if (text.indexOf(0) != -1) throw new IllegalArgumentException("Shell argument contains NUL");
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[][] encode(String[] array) {
        if (array == null) return null;
        byte[][] result = new byte[array.length][];
        for (int i = 0; i < result.length; i++) {
            if (array[i] == null) throw new IllegalArgumentException("Shell argument is null");
            result[i] = encode(array[i]);
        }
        return result;
    }

    private static native long start(byte[][] args, byte[][] env, byte[] dir, byte tty, int in, int out, int err);
    private static native void resize(long shell, long size);
    private static native int status(long shell);
    private static native void destroy(long shell);
}
