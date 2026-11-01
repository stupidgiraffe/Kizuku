package io.github.stupidgiraffe.kizuku.core;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Objects;

/** Immutable observation of an Android security principal, including shared UID peers. */
public final class ClientIdentity {
    private final int uid;
    private final String packageName;
    private final Map<String, Set<String>> members;
    private final Map<String, Long> installations;
    public ClientIdentity(int uid, String packageName, Map<String, Set<String>> members) {
        this(uid, packageName, members, java.util.Collections.emptyMap());
    }
    public ClientIdentity(int uid, String packageName, Map<String, Set<String>> members, Map<String, Long> installations) {
        if (uid < 10000 || packageName == null || !members.containsKey(packageName) || members.size() > 64)
            throw new IllegalArgumentException("Package does not belong to an application UID");
        TreeMap<String, Set<String>> copy = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : members.entrySet()) {
            if (entry.getKey().isEmpty() || entry.getValue().isEmpty()) throw new IllegalArgumentException("Missing signing identity");
            copy.put(entry.getKey(), Collections.unmodifiableSet(new TreeSet<>(entry.getValue())));
        }
        this.uid = uid;
        this.packageName = packageName;
        this.members = Collections.unmodifiableMap(copy);
        this.installations = Collections.unmodifiableMap(new TreeMap<>(installations));
    }
    public int uid() { return uid; }
    public String packageName() { return packageName; }
    public Map<String, Set<String>> members() { return members; }
    public Map<String, Long> installations() { return installations; }
    public String fingerprint() {
        try {
            String canonical = uid + ":" + packageName + ":" + members + ":" + installations;
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    @Override public boolean equals(Object o) {
        if (!(o instanceof ClientIdentity)) return false;
        ClientIdentity other = (ClientIdentity) o;
        return uid == other.uid && packageName.equals(other.packageName) && members.equals(other.members) && installations.equals(other.installations);
    }
    @Override public int hashCode() { return Objects.hash(uid, packageName, members, installations); }
}
