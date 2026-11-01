package io.github.stupidgiraffe.kizuku.core;

public final class RecoveryPolicy {
    private RecoveryPolicy() {}
    /** -1 means do not restart; never infer death merely from a stale heartbeat. */
    public static long retryDelay(boolean desiredRunning, boolean binderResponsive, boolean transportAvailable, int attempt) {
        if (!desiredRunning || binderResponsive || !transportAvailable || attempt < 0 || attempt >= 5) return -1;
        return Math.min(60000L, 2000L << attempt);
    }
}
