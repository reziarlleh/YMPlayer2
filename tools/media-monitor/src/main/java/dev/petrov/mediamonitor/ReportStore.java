package dev.petrov.mediamonitor;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

final class ReportStore {
    static final String[] PACKAGES = {"ru.fmplay", "dev.petrov.ymplayer2", "ru.yandex.music"};
    static final String[] LABELS = {"FMPLAY", "YMPlayer 2", "Яндекс Музыка"};
    static final long MAX_BYTES = 4L * 1024 * 1024, MAX_TIME = 30 * 60 * 1000L;
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("report", Context.MODE_PRIVATE); }
    static boolean allowed(String p) { return Arrays.asList(PACKAGES).contains(p); }
    static File file(Context c) { return new File(c.getFilesDir(), "media-report.txt"); }
    static synchronized boolean recording(Context c) { return prefs(c).getBoolean("recording", false); }
    static synchronized boolean expired(Context c) {
        long start = prefs(c).getLong("elapsedStart", 0), now = SystemClock.elapsedRealtime();
        return now < start || now - start >= MAX_TIME || file(c).length() >= MAX_BYTES - 32768;
    }
    static synchronized void start(Context c) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file(c))) { out.write(new byte[0]); }
        prefs(c).edit().clear().putBoolean("recording", true).putLong("elapsedStart", SystemClock.elapsedRealtime()).commit();
        JSONObject env = json();
        put(env, "schema", 1); put(env, "toolVersion", "1.0.0beta-build1");
        put(env, "sdk", Build.VERSION.SDK_INT); put(env, "android", Build.VERSION.RELEASE);
        put(env, "manufacturer", Build.MANUFACTURER); put(env, "model", Build.MODEL);
        ActivityManager am = (ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
        put(env, "lowRam", am != null && am.isLowRamDevice());
        put(env, "scope", "Only three players. No network, control commands, account data, raw URIs or image bytes.");
        write(c, "ENVIRONMENT", null, env);
        for (String p : PACKAGES) {
            JSONObject app = json();
            try {
                PackageInfo info = c.getPackageManager().getPackageInfo(p, 0);
                put(app, "installed", true); put(app, "version", info.versionName);
                put(app, "versionCode", Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
            } catch (Exception e) { put(app, "installed", false); }
            write(c, "APP", p, app);
        }
    }
    static synchronized void stop(Context c, String reason) {
        if (recording(c)) write(c, "STOP", null, with("reason", reason));
        prefs(c).edit().putBoolean("recording", false).commit();
    }
    static synchronized void write(Context c, String type, String pkg, JSONObject data) {
        if (!recording(c) || (pkg != null && !allowed(pkg))) return;
        if (expired(c) && !type.equals("STOP")) { stop(c, "30-minute or 4-MiB limit / reboot"); return; }
        JSONObject row = json();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        put(row, "utc", fmt.format(new Date())); put(row, "elapsedMs", SystemClock.elapsedRealtime());
        put(row, "event", type); if (pkg != null) put(row, "package", pkg); put(row, "data", data);
        byte[] bytes = (row.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (file(c).length() + bytes.length > MAX_BYTES) { prefs(c).edit().putBoolean("recording", false).commit(); return; }
        try (FileOutputStream out = new FileOutputStream(file(c), true)) {
            out.write(bytes);
            SharedPreferences.Editor ed = prefs(c).edit();
            ed.putInt("events", prefs(c).getInt("events", 0) + 1).putString("last", type + (pkg == null ? "" : " · " + pkg));
            if (pkg != null && !type.equals("APP")) ed.putInt(pkg, prefs(c).getInt(pkg, 0) + 1);
            ed.apply();
        } catch (IOException e) { prefs(c).edit().putBoolean("recording", false).putString("last", "Ошибка записи файла").commit(); }
    }
    static synchronized String status(Context c) {
        StringBuilder s = new StringBuilder(recording(c) ? "Запись идёт" : "Запись остановлена");
        s.append(" · ").append(file(c).length()/1024).append(" КБ\n");
        for (int i=0; i<PACKAGES.length; i++) s.append(LABELS[i]).append(": ").append(prefs(c).getInt(PACKAGES[i], 0)).append(" событий\n");
        return s.append("Последнее: ").append(prefs(c).getString("last", "—")).toString();
    }
    static synchronized File export(Context c) throws IOException {
        File dir = new File(c.getCacheDir(), "reports");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("directory");
        String name = "MediaMonitor-" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date()) + ".txt";
        File target = new File(dir, name);
        try (InputStream in = new FileInputStream(file(c)); OutputStream out = new FileOutputStream(target)) { copy(in,out); }
        return target;
    }
    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer=new byte[8192]; int count;
        while((count=in.read(buffer))!=-1) out.write(buffer,0,count);
    }
    static JSONObject json() { return new JSONObject(); }
    static JSONObject with(String key, Object value) { JSONObject o=json(); put(o,key,value); return o; }
    static void put(JSONObject o, String key, Object value) { try { o.put(key, value == null ? JSONObject.NULL : value); } catch (Exception ignored) { } }
    static String text(CharSequence value) { return value == null ? null : value.toString().substring(0, Math.min(512, value.length())); }
}
