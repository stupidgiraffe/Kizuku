package io.github.stupidgiraffe.kizuku.core;

/** Native scopes are independent of the broad legacy Shizuku wire. */
public final class Capability {
    private Capability() {}
    public static final long SHIZUKU = 1L;
    public static final long SHELL = 1L << 1;
    public static final long PACKAGES = 1L << 2;
    public static final long FILES = 1L << 3;
    public static final long SETTINGS = 1L << 4;
    public static final long NETWORK = 1L << 5;
    public static final long OVERLAYS = 1L << 6;
    public static final long AUTOMATION = 1L << 7;
    public static final long DEVICE_OWNER = 1L << 8;
    public static final long AXERON = 1L << 9;
    public static final long ROOT = 1L << 10;
    public static final long DIAGNOSTICS = 1L << 11;
    public static final long KNOWN = (1L << 12) - 1;
    public static void validate(long mask) {
        if (mask <= 0 || (mask & ~KNOWN) != 0) throw new IllegalArgumentException("Unknown capability mask");
    }
}
