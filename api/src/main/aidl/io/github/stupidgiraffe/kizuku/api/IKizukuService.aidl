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
}
