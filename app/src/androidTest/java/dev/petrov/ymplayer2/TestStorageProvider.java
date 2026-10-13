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

/** Writable SAF fixture. Source, destination, capacity and provider failures are controlled independently. */
public class TestStorageProvider extends DocumentsProvider {
    public static final String AUTHORITY = "dev.petrov.ymplayer2.test.storage";
    public static final Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "store");
    public static final Uri exportTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "export");
    private long available = 2L * 1024 * 1024 * 1024;
    private boolean unavailable, failWrite;
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
            else if (col.equals(DocumentsContract.Document.COLUMN_LAST_MODIFIED)) value = file.lastModified();
            else if (col.equals(DocumentsContract.Document.COLUMN_FLAGS)) value = DocumentsContract.Document.FLAG_SUPPORTS_DELETE | DocumentsContract.Document.FLAG_SUPPORTS_RENAME | DocumentsContract.Document.FLAG_SUPPORTS_WRITE | (file.isDirectory() ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : 0);
            row.add(value);
        }
    }
    private MatrixCursor cursor(String[] projection) { return new MatrixCursor(projection == null ? new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS} : projection); }
    @Override public MatrixCursor queryDocument(String id, String[] projection) throws FileNotFoundException { MatrixCursor c = cursor(projection); File f = file(id); if (f.exists()) row(c, id, f); return c; }
    @Override public MatrixCursor queryChildDocuments(String id, String[] projection, String sort) throws FileNotFoundException {
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
        if (failWrite && !mode.equals("r")) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("storage-reset")) {
            unavailable = false; failWrite = false; available = 2L * 1024 * 1024 * 1024; delete(base());
            new File(base(), "store").mkdirs(); new File(base(), "export").mkdirs();
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
            getContext().grantUriPermission("dev.petrov.ymplayer2.dev", tree, flags);
            getContext().grantUriPermission("dev.petrov.ymplayer2.dev", exportTree, flags);
            getContext().grantUriPermission("dev.petrov.ymplayer2.dev", DocumentsContract.buildRootsUri(AUTHORITY), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if (method.equals("storage-space")) available = Long.parseLong(arg);
        else if (method.equals("storage-unavailable")) unavailable = Boolean.parseBoolean(arg);
        else if (method.equals("storage-fail-write")) failWrite = Boolean.parseBoolean(arg);
        else return super.call(method, arg, extras);
        return Bundle.EMPTY;
    }
}
