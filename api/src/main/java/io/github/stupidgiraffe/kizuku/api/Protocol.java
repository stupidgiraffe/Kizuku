package io.github.stupidgiraffe.kizuku.api;

import io.github.stupidgiraffe.kizuku.core.Capability;

public final class Protocol {
    private Protocol() {}
    public static final String MANAGER = "io.github.stupidgiraffe.kizuku";
    public static final String AUTHORITY = MANAGER + ".core";
    public static final int VERSION = 1;
    public static final long SUPPORTED = Capability.SHIZUKU | Capability.DIAGNOSTICS;
    public static final int MAX_IPC_BYTES = 256 * 1024;
    public static final int MAX_CLIENTS = 256;
    public static final String BINDER = "core_binder";
}
