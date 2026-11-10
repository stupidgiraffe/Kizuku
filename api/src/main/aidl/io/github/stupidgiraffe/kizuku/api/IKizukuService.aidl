package io.github.stupidgiraffe.kizuku.api;

/** Native v1: negotiated scopes; no implicit shell or runtime permission. */
interface IKizukuService {
    Bundle status();
    Bundle negotiate(String packageName, int minimumVersion, int maximumVersion);
    void requestCapabilities(String packageName, long capabilities);
    Bundle diagnostics(String packageName);
    Bundle[] applications();
    void grant(String packageName, int uid, long capabilities, int lifetime, String expectedIdentity);
    void revoke(String packageName, int uid, long capabilities);
    void deliverLegacy(String packageName);
    IBinder legacyBinder();
    void setSafeMode(boolean enabled);
    void shutdown();
    Bundle runtimeCommand(String command);
    Bundle[] runtimePlugins();
    Bundle installRuntimePlugin(in ParcelFileDescriptor archive);
    Bundle controlRuntimePlugin(String id, String operation);
    ParcelFileDescriptor openRuntimeWebAsset(String id, String path);
    Bundle runtimeWebCommand(String id, String command, String directory);
    Bundle systemSetting(String namespace, String key, String value, String operation);
    Bundle setPackageEnabled(String packageName, boolean enabled);
    void requestAxeronAccess(String packageName, int uid);
}
