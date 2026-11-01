package io.github.stupidgiraffe.kizuku.api;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import io.github.stupidgiraffe.kizuku.core.ClientIdentity;
import java.util.*;
import java.security.MessageDigest;
import org.json.*;

/** Shared identity codec; the manager independently verifies the identity shown for approval. */
public final class AndroidIdentity {
    private AndroidIdentity() {}
    public static Set<String> signers(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null ? info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signatures == null || signatures.length == 0) throw new SecurityException("No signing certificate");
        Set<String> result = new TreeSet<>();
        for (Signature signature : signatures) {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            result.add(hex.toString());
        }
        return result;
    }
    public static ClientIdentity resolve(PackageManager manager, int uid, String packageName) throws Exception {
        String[] names = manager.getPackagesForUid(uid);
        if (names == null || names.length == 0 || names.length > 64) throw new SecurityException("Unknown caller UID");
        Map<String,Set<String>> members = new TreeMap<>(); Map<String,Long> installs = new TreeMap<>();
        for (String name : names) {
            PackageInfo info = manager.getPackageInfo(name, Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
            if (info.applicationInfo == null || info.applicationInfo.uid != uid) throw new SecurityException("UID changed");
            members.put(name, signers(info)); installs.put(name, info.firstInstallTime);
        }
        return new ClientIdentity(uid, packageName, members, installs);
    }
    public static JSONObject encode(ClientIdentity identity) throws JSONException {
        JSONObject result = new JSONObject(); JSONObject members = new JSONObject(); JSONObject installs = new JSONObject();
        for (Map.Entry<String,Set<String>> e : identity.members().entrySet()) members.put(e.getKey(), new JSONArray(e.getValue()));
        for (Map.Entry<String,Long> e : identity.installations().entrySet()) installs.put(e.getKey(), e.getValue());
        result.put("package",identity.packageName()); result.put("uid",identity.uid()); result.put("members",members); result.put("installs",installs);
        return result;
    }
    public static ClientIdentity decode(JSONObject json) throws JSONException {
        JSONObject peers=json.getJSONObject("members"), epochs=json.getJSONObject("installs");
        Map<String,Set<String>> members=new TreeMap<>(); Map<String,Long> installs=new TreeMap<>();
        Iterator<String> keys=peers.keys();
        while(keys.hasNext()) {
            String name=keys.next(); JSONArray certs=peers.getJSONArray(name); Set<String> values=new TreeSet<>();
            for(int i=0;i<certs.length();i++) values.add(certs.getString(i));
            members.put(name,values); installs.put(name,epochs.getLong(name));
        }
        return new ClientIdentity(json.getInt("uid"),json.getString("package"),members,installs);
    }
}
