package dev.petrov.ymplayer2;

import android.content.Intent;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Writable SAF fixture. Source, destination, capacity and provider failures are controlled independently. */
public class TestStorageProvider extends DocumentsProvider {
    public static final String AUTHORITY = "dev.petrov.ymplayer2.test.storage";
    public static final Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "store");
    public static final Uri exportTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "export");
    private long available = 2L * 1024 * 1024 * 1024;
    private boolean unavailable, failWrite;
    private volatile CountDownLatch audioTailGate;
    private volatile CountDownLatch catalogQueryGate, catalogJsonGate, catalogChildrenGate;
    private volatile boolean unknownModified, constantModified;
    private final AtomicInteger catalogQueries = new AtomicInteger(), catalogFileQueries = new AtomicInteger(), catalogChildren = new AtomicInteger(), catalogJsonOpens = new AtomicInteger(), catalogWaiters = new AtomicInteger();
    private final AtomicInteger audioOpens = new AtomicInteger(), audioTailWaiters = new AtomicInteger(), audioPipes = new AtomicInteger();
    private static final int AUDIO_PREFIX_BYTES = 256 * 1024;
    private File base() { File root = new File(getContext().getCacheDir(), "transfer-storage"); root.mkdirs(); return root; }
    private File file(String id) throws FileNotFoundException {
        try {
            File root = base().getCanonicalFile(), file = new File(root, id).getCanonicalFile();
            if (unavailable || !file.toPath().startsWith(root.toPath()) || !(id.equals("store") || id.startsWith("store/") || id.equals("export") || id.startsWith("export/"))) throw new FileNotFoundException();
            return file;
        } catch (IOException e) { throw new FileNotFoundException(); }
    }
    @Override public boolean onCreate() { return true; }
    @Override public boolean isChildDocument(String parent, String child) { return child.startsWith(parent + "/"); }
    @Override public MatrixCursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection == null ? new String[]{DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES} : projection);
        for (String id : new String[]{"store", "export"}) {
            MatrixCursor.RowBuilder row = c.newRow();
            for (String column : c.getColumnNames()) row.add(column.equals(DocumentsContract.Root.COLUMN_ROOT_ID) || column.equals(DocumentsContract.Root.COLUMN_DOCUMENT_ID) ? id : column.equals(DocumentsContract.Root.COLUMN_TITLE) ? "YMPlayer transfer " + id : column.equals(DocumentsContract.Root.COLUMN_FLAGS) ? DocumentsContract.Root.FLAG_SUPPORTS_CREATE : column.equals(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES) ? available : null);
        }
        return c;
    }
    private void row(MatrixCursor c, String id, File file) {
        MatrixCursor.RowBuilder row = c.newRow();
        for (String col : c.getColumnNames()) {
            Object value = null;
            if (col.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) value = id;
            else if (col.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) value = file.getName();
            else if (col.equals(DocumentsContract.Document.COLUMN_MIME_TYPE)) value = file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
            else if (col.equals(DocumentsContract.Document.COLUMN_SIZE)) value = file.length();
            else if (col.equals(DocumentsContract.Document.COLUMN_LAST_MODIFIED)) value = unknownModified ? 0L : constantModified ? 1700000000000L : file.lastModified();
            else if (col.equals(DocumentsContract.Document.COLUMN_FLAGS)) value = DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME | DocumentsContract.Document.FLAG_SUPPORTS_WRITE | (file.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : 0);
            row.add(value);
        }
    }
    private MatrixCursor cursor(String[] projection) { return new MatrixCursor(projection == null ? new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS} : projection); }
    private boolean catalogFile(String id) { return id.contains("/v1/") && id.matches(".*\\.(json|audio|cover|sha256)"); }
    private void catalogAwait(CountDownLatch gate) throws FileNotFoundException {
        if (gate == null || gate.getCount() == 0) return;
        catalogWaiters.incrementAndGet();
        try { if (!gate.await(30, TimeUnit.SECONDS)) throw new FileNotFoundException("Catalog fixture gate timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new FileNotFoundException("Catalog fixture interrupted"); }
        finally { catalogWaiters.decrementAndGet(); }
    }
    @Override public MatrixCursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        catalogQueries.incrementAndGet();
        if (catalogFile(id)) { catalogFileQueries.incrementAndGet(); catalogAwait(catalogQueryGate); }
        MatrixCursor c = cursor(projection); File f = file(id); if (f.exists()) row(c, id, f); return c;
    }
    @Override public MatrixCursor queryChildDocuments(String id, String[] projection, String sort) throws FileNotFoundException {
        catalogChildren.incrementAndGet();
        if (id.contains("/v1/")) catalogAwait(catalogChildrenGate);
        MatrixCursor c = cursor(projection); File[] children = file(id).listFiles(); if (children == null) throw new FileNotFoundException();
        for (File f : children) row(c, id + "/" + f.getName(), f); return c;
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (failWrite || name.contains("/") || name.equals("..")) throw new FileNotFoundException();
        // Model the system provider's MIME/extension adjustment, which a byte-only fake misses.
        if (name.endsWith(".json") && !mime.equals("application/json")) name += ".bin";
        if (name.endsWith(".mp3") && !mime.equals("audio/mpeg")) name += ".bin";
        File next = file(parent + "/" + name); if (next.exists()) throw new FileNotFoundException();
        try { if (mime.equals(DocumentsContract.Document.MIME_TYPE_DIR)) { if (!next.mkdir()) throw new IOException(); } else if (!next.createNewFile()) throw new IOException(); }
        catch (IOException e) { throw new FileNotFoundException(); }
        return parent + "/" + name;
    }
    @Override public String renameDocument(String id, String name) throws FileNotFoundException {
        File old = file(id), target = new File(old.getParentFile(), name);
        if (target.exists() || !old.renameTo(target)) throw new FileNotFoundException();
        return id.substring(0, id.lastIndexOf('/') + 1) + name;
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (!id.contains("/")) throw new FileNotFoundException(); delete(file(id));
    }
    private void delete(File file) { File[] children = file.listFiles(); if (children != null) for (File f : children) delete(f); file.delete(); }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (mode.equals("r") && id.contains("/v1/") && id.endsWith(".json")) { catalogJsonOpens.incrementAndGet(); catalogAwait(catalogJsonGate); }
        if (failWrite && !mode.equals("r")) throw new FileNotFoundException();
        File source = file(id);
        if (mode.equals("r") && id.endsWith(".audio")) {
            audioOpens.incrementAndGet();
            CountDownLatch gate = audioTailGate;
            if (gate != null) return gatedAudio(source, gate);
        }
        return ParcelFileDescriptor.open(source, ParcelFileDescriptor.parseMode(mode));
    }
    /** A real SAF stream: the first eight seconds of the 30s WAV are available immediately.
     * Decoder startup can proceed, but a second whole-file integrity pass must await its tail. */
    private ParcelFileDescriptor gatedAudio(File source, CountDownLatch gate) throws FileNotFoundException {
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createReliablePipe(); }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        audioPipes.incrementAndGet();
        Thread writer = new Thread(() -> {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                byte[] buffer = new byte[32 * 1024];
                int total = 0, count;
                boolean waited = false;
                while ((count = input.read(buffer)) >= 0) {
                    if (!waited && total >= AUDIO_PREFIX_BYTES) {
                        waited = true; audioTailWaiters.incrementAndGet();
                        try {
                            if (!gate.await(30, TimeUnit.SECONDS)) throw new IOException("Fixture audio tail was not released");
                        } finally { audioTailWaiters.decrementAndGet(); }
                    }
                    output.write(buffer, 0, count); total += count;
                }
            } catch (Exception ignored) {
                // The real decoder may close a prefetch/old stream before the tail is released.
                try { pipe[1].close(); } catch (IOException ignoredClose) { }
            } finally { audioPipes.decrementAndGet(); }
        }, "YMPlayer-test-SAF-audio");
        writer.setDaemon(true); writer.start();
        return pipe[0];
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("storage-reset")) {
            releaseCatalogGates(); unknownModified = false; constantModified = false; resetCatalogCounters();
            CountDownLatch previous = audioTailGate; audioTailGate = null;
            if (previous != null) previous.countDown();
            audioOpens.set(0);
            unavailable = false; failWrite = false; available = 2L * 1024 * 1024 * 1024; delete(base());
            new File(base(), "store").mkdirs(); new File(base(), "export").mkdirs();
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
            getContext().grantUriPermission("dev.petrov.ymplayer2.dev", tree, flags);
            getContext().grantUriPermission("dev.petrov.ymplayer2.dev", exportTree, flags);
            getContext().grantUriPermission("dev.petrov.ymplayer2.dev", DocumentsContract.buildRootsUri(AUTHORITY), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if (method.equals("storage-space")) available = Long.parseLong(arg);
        else if (method.equals("storage-unavailable")) unavailable = Boolean.parseBoolean(arg);
        else if (method.equals("storage-fail-write")) failWrite = Boolean.parseBoolean(arg);
        else if (method.equals("storage-catalog-gate")) {
            releaseCatalogGates();
            if ("query".equals(arg)) catalogQueryGate = new CountDownLatch(1);
            if ("json".equals(arg)) catalogJsonGate = new CountDownLatch(1);
            if ("children".equals(arg)) catalogChildrenGate = new CountDownLatch(1);
        } else if (method.equals("storage-catalog-counters-reset")) resetCatalogCounters();
        else if (method.equals("storage-catalog-unknown-mtime")) unknownModified = Boolean.parseBoolean(arg);
        else if (method.equals("storage-catalog-constant-mtime")) constantModified = Boolean.parseBoolean(arg);
        else if (method.equals("storage-catalog-stats")) {
            Bundle b = new Bundle(); b.putInt("queries",catalogQueries.get()); b.putInt("fileQueries",catalogFileQueries.get());
            b.putInt("children",catalogChildren.get()); b.putInt("jsonOpens",catalogJsonOpens.get()); b.putInt("waiters",catalogWaiters.get()); return b;
        } else if (method.equals("storage-seed-catalog") || method.equals("storage-edit-catalog")) {
            try {
                String profile = extras.getString("profile", "cold"), account = extras.getString("account", "account");
                File directory = file("store/v1/" + digest(profile + "\u0000" + account)); directory.mkdirs();
                if (method.equals("storage-seed-catalog")) {
                    int count = extras.getInt("count", 400);
                    if (count < 1 || count > 2000) throw new IllegalArgumentException("Fixture count");
                    for (int i=0;i<count;i++) {
                        String id="seed"+i, name=digest(id);
                        writeFixture(new File(directory,name+".json"), metadata(id,"Cached song "+i));
                        writeFixture(new File(directory,name+".audio"), new byte[64]);
                        if (i%2==0) writeFixture(new File(directory,name+".cover"), new byte[32]);
                    }
                } else {
                    String id=extras.getString("id", "seed1"), action=extras.getString("action", "rename");
                    File metadata = new File(directory,digest(id)+".json");
                    if (action.equals("removeAudio")) new File(directory,digest(id)+".audio").delete();
                    else writeFixture(metadata, metadata(id, "Changed title with a different metadata length"));
                }
            } catch (Exception e) { throw new IllegalStateException("Cannot prepare catalog fixture",e); }
        } else if (method.equals("storage-audio-tail")) {
            CountDownLatch previous = audioTailGate;
            audioTailGate = "closed".equals(arg) ? new CountDownLatch(1) : null;
            if (previous != null) previous.countDown();
        } else if (method.equals("storage-audio-stats")) {
            Bundle stats = new Bundle();
            stats.putInt("opens", audioOpens.get()); stats.putInt("tailWaiters", audioTailWaiters.get()); stats.putInt("activePipes", audioPipes.get());
            return stats;
        }
        else return super.call(method, arg, extras);
        return Bundle.EMPTY;
    }
    private void resetCatalogCounters() { catalogQueries.set(0); catalogFileQueries.set(0); catalogChildren.set(0); catalogJsonOpens.set(0); }
    private void releaseCatalogGates() {
        for (CountDownLatch g:new CountDownLatch[]{catalogQueryGate,catalogJsonGate,catalogChildrenGate}) if(g!=null)g.countDown();
        catalogQueryGate=null;catalogJsonGate=null;catalogChildrenGate=null;
    }
    private static String digest(String value) throws Exception {
        byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();
    }
    private static byte[] metadata(String id,String title) throws Exception {
        return new org.json.JSONObject().put("id","yandex:"+id+":7").put("title",title).put("artist","Fixture artist")
            .put("album","Fixture album").put("duration",30).put("genre","").put("artists",new org.json.JSONArray()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static void writeFixture(File file,byte[] data) throws IOException { try(OutputStream out=new FileOutputStream(file)){out.write(data);} }
}
