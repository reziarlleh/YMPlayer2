package dev.petrov.ymplayer2;

import android.content.Intent;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;

/** Platform-only: provider runs in the test APK process, without target Kotlin dependencies. */
public class TestMusicProvider extends DocumentsProvider {
    public static final Uri tree = DocumentsContract.buildTreeDocumentUri("dev.petrov.ymplayer2.test.music", "music");
    private boolean unavailable;
    private boolean corrupt;
    private File directory() { File dir = new File(getContext().getCacheDir(), "test-music"); dir.mkdirs(); return dir; }
    @Override public boolean onCreate() { return true; }
    @Override public MatrixCursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS});
        cursor.newRow().add(Root.COLUMN_ROOT_ID, "music").add(Root.COLUMN_DOCUMENT_ID, "music").add(Root.COLUMN_TITLE, "YM2 Test Music").add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD);
        return cursor;
    }
    @Override public MatrixCursor queryDocument(String id, String[] projection) { MatrixCursor cursor = cursor(projection); addDocument(cursor, id); return cursor; }
    @Override public MatrixCursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        if (unavailable) throw new FileNotFoundException("Fixture storage disconnected");
        MatrixCursor cursor = cursor(projection);
        if (parent.equals("music")) { addDocument(cursor, "one.wav"); addDocument(cursor, "nested"); if (corrupt) addDocument(cursor, "broken.wav"); }
        else if (parent.equals("nested")) addDocument(cursor, "two.wav");
        return cursor;
    }
    @Override public boolean isChildDocument(String parent, String id) { return parent.equals("music") || parent.equals("nested") && id.equals("two.wav"); }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (unavailable || !mode.equals("r") || !Arrays.asList("one.wav", "two.wav", "broken.wav").contains(id)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(new File(directory(), id), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("fixtures")) {
            unavailable = false; corrupt = false;
            try {
                wave(new File(directory(), "one.wav"), 440);
                wave(new File(directory(), "two.wav"), 660);
                Files.write(new File(directory(), "broken.wav").toPath(), "not an audio file".getBytes());
            } catch (IOException error) { throw new IllegalStateException(error); }
            grant(); return Bundle.EMPTY;
        }
        if (method.equals("grant")) { grant(); return Bundle.EMPTY; }
        if (method.equals("unavailable")) { unavailable = "true".equals(arg); return Bundle.EMPTY; }
        if (method.equals("corrupt")) { corrupt = true; return Bundle.EMPTY; }
        return super.call(method, arg, extras);
    }
    private void grant() {
        getContext().grantUriPermission("dev.petrov.ymplayer2.dev", tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }
    private MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS});
    }
    private void addDocument(MatrixCursor cursor, String id) {
        boolean folder = id.equals("music") || id.equals("nested");
        File file = new File(directory(), id);
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, id.equals("music") ? "Test Music" : id)
            .add(Document.COLUMN_MIME_TYPE, folder ? Document.MIME_TYPE_DIR : "audio/wav").add(Document.COLUMN_SIZE, folder ? 0L : file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified()).add(Document.COLUMN_FLAGS, 0);
    }
    private void wave(File file, double frequency) throws IOException {
        int rate = 16000, count = rate * 30;
        ByteBuffer buffer = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes()).putInt(36 + count * 2).put("WAVEfmt ".getBytes()).putInt(16)
            .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
            .put("data".getBytes()).putInt(count * 2);
        for (int i = 0; i < count; i++) buffer.putShort((short) (Math.sin(2 * Math.PI * frequency * i / rate) * 1800));
        Files.write(file.toPath(), buffer.array());
    }
}
