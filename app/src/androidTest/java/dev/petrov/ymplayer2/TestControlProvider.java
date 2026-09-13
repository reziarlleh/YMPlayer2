package dev.petrov.ymplayer2;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Test APK control channel. The actual SAF provider keeps MANAGE_DOCUMENTS protection. */
public class TestControlProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("equalizer-report")) {
            android.content.SharedPreferences p = getContext().getSharedPreferences("equalizer-probe", 0);
            Bundle result = new Bundle(); result.putString("action", p.getString("action", ""));
            result.putInt("session", p.getInt("session", -1)); result.putString("package", p.getString("package", ""));
            result.putInt("content", p.getInt("content", -1)); return result;
        }
        long identity = Binder.clearCallingIdentity();
        try { return getContext().getContentResolver().call(TestMusicProvider.tree, method, arg, extras); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
