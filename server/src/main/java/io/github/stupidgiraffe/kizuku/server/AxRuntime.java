package io.github.stupidgiraffe.kizuku.server;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import frb.axeron.server.IAxshProcess;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Runtime jobs execute inside the existing host's UID, with a separate state directory per UID. */
final class AxRuntime {
    static final String SHELL = "/system/bin/sh";
    private static final String MARKER = "KIZUKU_AXERON_PROCESS";
    private static final int MAX_OUTPUT = 32 * 1024;
    private static final String ROLE = android.os.Process.myUid() == 0 ? "root-" : "shell-";
    private static final OutputStream DISCARD = new OutputStream() {
        @Override public void write(int unused) {}
        @Override public void write(byte[] unused, int offset, int count) {}
    };
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Set<Job> jobs = new HashSet<>();
    final File home;
    final File cache;
    final File bins;
    final RuntimePlugins plugins;
    private boolean paused;

    AxRuntime() throws Exception {
        home = new File(android.os.Process.myUid() == 0 ? "/data/adb/kizuku" : "/data/user_de/0/com.android.shell/kizuku");
        cache = new File(home, "cache");
        bins = new File(home, "xbin");
        for (File directory : new File[]{home, cache, bins, new File(home, "bin")}) secureDirectory(directory);
        LegacyRemoteProcess.reapMarkedChildren(MARKER + "=" + ROLE, true);
        plugins = new RuntimePlugins(this);
    }

    static void secureDirectory(File directory) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
        if (!directory.getCanonicalFile().equals(directory.getAbsoluteFile())
                || Os.lstat(directory.getPath()).st_uid != android.os.Process.myUid())
            throw new SecurityException("Runtime directory ownership mismatch");
        Os.chmod(directory.getPath(), 0700);
    }

    private synchronized Job start(ClientIdentity owner, String plugin, String[] argv, File directory, Map<String, String> extra) throws IOException {
        if (paused) throw new IllegalStateException("Runtime is suspended");
        for (Job job : new ArrayList<>(jobs)) {
            if (!job.child.isAlive() && !LegacyRemoteProcess.hasMarkedChildren(MARKER + "=" + job.tag)) jobs.remove(job);
        }
        if (jobs.size() >= 16) throw new IllegalStateException("Runtime process limit reached");
        if (argv == null || argv.length == 0 || argv.length > 256) throw new IllegalArgumentException("Invalid command arguments");
        for (String arg : argv) {
            if (arg == null || arg.length() > 32_768 || arg.indexOf(0) >= 0) throw new IllegalArgumentException("Invalid argument");
        }
        ProcessBuilder builder = new ProcessBuilder(argv).directory(directory);
        Map<String, String> env = builder.environment();
        if (extra != null) env.putAll(extra);
        env.put("AXERON", "true");
        env.put("AXERONVER", "30000");
        env.put("AXERONDIR", home.getPath());
        env.put("AXERONBIN", new File(home, "bin").getPath());
        env.put("AXERONXBIN", bins.getPath());
        env.put("AXERONLIB", new File(home, "bin").getPath());
        env.put("HOME", home.getPath());
        env.put("TMPDIR", cache.getPath());
        env.put("PATH", bins + ":" + new File(home, "bin") + ":/system/bin:/system/xbin");
        if (plugin == null) env.remove("AXERON_PLUGIN"); else env.put("AXERON_PLUGIN", plugin);
        String tag = ROLE + UUID.randomUUID();
        env.put(MARKER, tag);
        Job job = new Job(owner, plugin, tag, builder.start());
        jobs.add(job);
        return job;
    }

    private record Job(ClientIdentity owner, String plugin, String tag, java.lang.Process child) {
        void stop() {
            if (child.isAlive()) child.destroyForcibly();
            LegacyRemoteProcess.reapMarkedChildren(MARKER + "=" + tag, false);
        }
    }

    Bundle shell(String command) throws Exception {
        return run(null, new String[]{SHELL, "-c", validate(command)}, home, null, 15);
    }

    static String validate(String command) {
        if (command == null || command.trim().isEmpty() || command.length() > 32_768 || command.indexOf(0) >= 0)
            throw new IllegalArgumentException("Enter a command of up to 32,768 characters");
        return command;
    }

    Bundle run(String plugin, String[] argv, File directory, Map<String, String> env, int seconds) throws Exception {
        Job job = start(null, plugin, argv, directory, env);
        try {
            job.child.getOutputStream().close();
            Future<byte[]> out = io.submit(() -> capture(job.child.getInputStream()));
            Future<byte[]> err = io.submit(() -> capture(job.child.getErrorStream()));
            boolean completed = job.child.waitFor(seconds, TimeUnit.SECONDS);
            if (!completed) job.stop();
            byte[] stdout = collect(out);
            byte[] stderr = collect(err);
            Bundle result = new Bundle();
            result.putInt("exit", completed ? job.child.exitValue() : 124);
            result.putBoolean("timed_out", !completed);
            result.putBoolean("truncated", stdout.length > MAX_OUTPUT || stderr.length > MAX_OUTPUT);
            result.putString("stdout", new String(stdout, 0, Math.min(stdout.length, MAX_OUTPUT), StandardCharsets.UTF_8));
            result.putString("stderr", new String(stderr, 0, Math.min(stderr.length, MAX_OUTPUT), StandardCharsets.UTF_8));
            return result;
        } finally {
            stop(job::equals);
        }
    }

    void service(String id, File script, File directory) throws IOException {
        Job job = start(null, id, new String[]{SHELL, script.getPath()}, directory, null);
        job.child.getOutputStream().close();
        io.execute(() -> copy(job.child.getInputStream(), DISCARD));
        io.execute(() -> copy(job.child.getErrorStream(), DISCARD));
    }

    IAxshProcess launch(ClientIdentity owner, String[] argv, String[] env, String dir,
            ParcelFileDescriptor stdin, ParcelFileDescriptor stdout, ParcelFileDescriptor stderr,
            boolean pty, long size, ParcelFileDescriptor status) throws IOException {
        if (pty) throw new UnsupportedOperationException("Interactive Axeron PTY is unavailable");
        File work = dir == null || dir.isEmpty() ? home : new File(dir);
        if (!work.isAbsolute() || !work.isDirectory()) throw new IllegalArgumentException("Invalid working directory");
        Map<String, String> extra = new HashMap<>();
        if (env != null) for (String variable : env) {
            int separator = variable == null ? -1 : variable.indexOf('=');
            if (separator < 1 || variable.length() > 8192 || variable.indexOf(0) >= 0)
                throw new IllegalArgumentException("Invalid shell environment");
            extra.put(variable.substring(0, separator), variable.substring(separator + 1));
        }
        Job job = start(owner, null, argv, work, extra);
        var input = new ParcelFileDescriptor.AutoCloseInputStream(stdin);
        io.execute(() -> copy(input, job.child.getOutputStream()));
        Future<?> out = io.submit(() -> copy(job.child.getInputStream(), new ParcelFileDescriptor.AutoCloseOutputStream(stdout)));
        Future<?> err = io.submit(() -> copy(job.child.getErrorStream(), new ParcelFileDescriptor.AutoCloseOutputStream(stderr)));
        io.execute(() -> {
            try {
                int exit = job.child.waitFor();
                job.stop();
                input.close();
                if (status != null) try (var stream = new ParcelFileDescriptor.AutoCloseOutputStream(status)) {
                    stream.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(exit).array());
                }
                waitForStream(out, stdout);
                waitForStream(err, stderr);
            } catch (IOException | InterruptedException ignored) {
                if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            } finally {
                closeDescriptor(stdin); closeDescriptor(stdout); closeDescriptor(stderr); closeDescriptor(status);
                stop(job::equals);
            }
        });
        return new IAxshProcess.Stub() {
            private void enforceOwner() {
                if (android.os.Binder.getCallingUid() != owner.uid()) throw new SecurityException("Shell session belongs to another UID");
            }
            @Override public void setWindowSize(long size) { enforceOwner(); }
            @Override public int waitFor() {
                enforceOwner();
                try { return job.child.waitFor(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return 130; }
            }
            @Override public void destroy() { enforceOwner(); stop(job::equals); }
        };
    }

    Bundle setting(String table, String key, String value, String operation) throws Exception {
        if (!"system".equals(table) && !"secure".equals(table) && !"global".equals(table))
            throw new IllegalArgumentException("Invalid settings table");
        if (key == null || !key.matches("[A-Za-z0-9_][A-Za-z0-9_.:-]{0,159}"))
            throw new IllegalArgumentException("Invalid settings key");
        if (!"get".equals(operation) && !"put".equals(operation) && !"delete".equals(operation))
            throw new IllegalArgumentException("Invalid settings operation");
        if ("put".equals(operation) && (value == null || value.length() > 4096 || value.indexOf(0) >= 0))
            throw new IllegalArgumentException("Invalid setting value");
        String[] argv = "put".equals(operation)
            ? new String[]{"/system/bin/settings", "--user", "0", operation, table, key, value}
            : new String[]{"/system/bin/settings", "--user", "0", operation, table, key};
        return run(null, argv, home, null, 8);
    }

    Bundle setPackageEnabled(String name, boolean enable) throws Exception {
        return run(null, new String[]{"/system/bin/pm", enable ? "enable" : "disable-user", "--user", "0", name}, home, null, 8);
    }

    synchronized void pause() { paused = true; stop(job -> true); }
    synchronized void resume() { paused = false; }
    void stopOwner(ClientIdentity owner) { stop(job -> owner.equals(job.owner)); }
    void stopPlugin(String plugin) { stop(job -> plugin.equals(job.plugin)); }
    synchronized void close() { pause(); io.shutdownNow(); }
    private synchronized void stop(Predicate<Job> matches) {
        for (Job job : new ArrayList<>(jobs)) if (matches.test(job) && jobs.remove(job)) job.stop();
    }

    void sweepOwners(Predicate<ClientIdentity> installed) {
        Set<ClientIdentity> owners = new HashSet<>();
        synchronized (this) {
            for (Job job : jobs) if (job.owner != null) owners.add(job.owner);
        }
        for (ClientIdentity owner : owners) if (!installed.test(owner)) stopOwner(owner);
    }

    private static byte[] capture(InputStream stream) throws IOException {
        try (stream; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int count; (count = stream.read(buffer)) != -1;)
                output.write(buffer, 0, Math.min(count, MAX_OUTPUT + 1 - output.size()));
            return output.toByteArray();
        }
    }

    private static byte[] collect(Future<byte[]> result) {
        try { return result.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {}
        result.cancel(true);
        return new byte[0];
    }

    private static void copy(InputStream in, OutputStream out) {
        try (in; out) {
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) != -1;) out.write(buffer, 0, count);
        } catch (IOException closed) {}
    }

    private static void waitForStream(Future<?> work, ParcelFileDescriptor descriptor) {
        try { work.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {}
        closeDescriptor(descriptor);
        work.cancel(true);
    }

    private static void closeDescriptor(ParcelFileDescriptor descriptor) {
        if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) {}
    }
}
