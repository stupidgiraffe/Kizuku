package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Termux presentation contract backed by Kizuku's existing remote PTY. No local subprocess engine. */
public final class TerminalSession extends TerminalOutput {
    public interface Transport {
        InputStream input();
        OutputStream output();
        int awaitExit() throws Exception;
        void resize(long size) throws Exception;
        void destroy() throws Exception;
        void close();
    }

    public final String mHandle = UUID.randomUUID().toString();
    public String mSessionName;
    private final Transport transport;
    private final ByteQueue received = new ByteQueue(64 * 1024);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.atomic.AtomicBoolean drainPending = new java.util.concurrent.atomic.AtomicBoolean();
    private final ThreadPoolExecutor writes = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32));
    private final byte[] screenBuffer = new byte[8192];
    private TerminalSessionClient client;
    private TerminalEmulator emulator;
    private volatile boolean running = true;
    private volatile boolean readerDone;
    private volatile Integer result;
    private boolean started;
    private int exitStatus;

    public TerminalSession(Transport transport, TerminalSessionClient client) {
        this.transport = transport;
        this.client = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient next) {
        client = next;
        if (emulator != null) emulator.updateTerminalSessionClient(next);
    }

    public void updateSize(int columns, int rows, int cellWidth, int cellHeight) {
        if (emulator == null) {
            emulator = new TerminalEmulator(this, columns, rows, cellWidth, cellHeight, 5000, client);
        } else emulator.resize(columns, rows, cellWidth, cellHeight);
        long size = (rows & 0xffffL) | ((columns & 0xffffL) << 16);
        submit(() -> transport.resize(size));
        if (!started) {
            started = true;
            new Thread(this::read, "kizuku-terminal-output").start();
            new Thread(() -> {
                try { result = transport.awaitExit(); }
                catch (Exception failure) {
                    result = -1;
                    main.post(() -> client.logError("Terminal", "Terminal connection ended: " + failure.getMessage()));
                }
                scheduleDrain();
            }, "kizuku-terminal-exit").start();
        }
    }

    private void read() {
        try (InputStream input = transport.input()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) {
                if (!received.write(buffer, 0, count)) break;
                scheduleDrain();
            }
        } catch (IOException failure) {
            if (running) main.post(() -> client.logWarn("Terminal", "Output ended: " + failure.getMessage()));
        } finally {
            readerDone = true;
            scheduleDrain();
        }
    }

    private final Runnable drainTask = () -> { drainPending.set(false); drain(); };

    private void scheduleDrain() {
        if (drainPending.compareAndSet(false, true)) main.post(drainTask);
    }

    private void drain() {
        if (emulator == null) return;
        long deadline = SystemClock.uptimeMillis() + 8;
        int count;
        boolean changed = false;
        do {
            count = received.read(screenBuffer, false);
            if (count > 0) { emulator.append(screenBuffer, count); changed = true; }
        } while (count > 0 && SystemClock.uptimeMillis() < deadline);
        if (changed) client.onTextChanged(this);
        if (count > 0) scheduleDrain();
        else if (readerDone && result != null && running) {
            exitStatus = result;
            running = false;
            received.close();
            writes.shutdownNow();
            transport.close();
            client.onSessionFinished(this);
        }
    }

    private interface WriteAction { void run() throws Exception; }

    private void submit(WriteAction action) {
        if (!running) return;
        try {
            writes.execute(() -> {
                try { action.run(); }
                catch (Exception failure) {
                    main.post(() -> client.logError("Terminal", "Input failed: " + failure.getMessage()));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            client.logWarn("Terminal", "Terminal input is busy. Try again.");
        }
    }

    @Override public void write(byte[] bytes, int offset, int count) {
        if (count > 65536) {
            client.logWarn("Terminal", "Paste up to 64 KiB at a time.");
            return;
        }
        byte[] copy = Arrays.copyOfRange(bytes, offset, offset + count);
        submit(() -> { transport.output().write(copy); transport.output().flush(); });
    }

    public void writeCodePoint(boolean escape, int codePoint) {
        if (!Character.isValidCodePoint(codePoint)) return;
        write((escape ? "\033" : "") + new String(Character.toChars(codePoint)));
    }

    public TerminalEmulator getEmulator() { return emulator; }
    public String getTitle() { return emulator == null ? null : emulator.getTitle(); }
    public boolean isRunning() { return running; }
    public int getExitStatus() { return exitStatus; }
    public int getPid() { return -1; }
    public String getCwd() { return null; }
    public void reset() { if (emulator != null) { emulator.reset(); client.onTextChanged(this); } }

    public void finishIfRunning() {
        if (!running) return;
        new Thread(() -> {
            try { transport.destroy(); }
            catch (Exception failure) {
                main.post(() -> client.logError("Terminal", "Could not terminate the session: " + failure.getMessage()));
            }
        }, "kizuku-terminal-stop").start();
    }

    @Override public void titleChanged(String oldTitle, String newTitle) { client.onTitleChanged(this); }
    // OSC 52 clipboard read/write is deliberately controlled by the visible UI.
    @Override public void onCopyTextToClipboard(String text) { client.onCopyTextToClipboard(this, text); }
    @Override public void onPasteTextFromClipboard() { client.onPasteTextFromClipboard(this); }
    @Override public void onBell() { client.onBell(this); }
    @Override public void onColorsChanged() { client.onColorsChanged(this); }
}
