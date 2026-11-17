package io.github.stupidgiraffe.kizuku.dhizukucompat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

public final class LegacyDhizukuProvider extends ContentProvider {
    private static final String MANAGER = "io.github.stupidgiraffe.kizuku";
    private static final Uri PROVIDER = Uri.parse("content://" + MANAGER + ".dhizuku_server.provider");

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"client".equals(method) || extras == null || extras.getBinder("client") == null) return null;
        var context = getContext();
        String client = getCallingPackage();
        if (context == null || client == null || context.getPackageManager().checkSignatures(
                context.getPackageName(), MANAGER) != PackageManager.SIGNATURE_MATCH) return null;

        var request = new Bundle();
        request.putInt("uid", Binder.getCallingUid());
        request.putString("package", client);
        request.putBinder("client", extras.getBinder("client"));
        long token = Binder.clearCallingIdentity();
        try { return context.getContentResolver().call(PROVIDER, "legacy-client", null, request); }
        finally { Binder.restoreCallingIdentity(token); }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
