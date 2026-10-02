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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Platform-only: provider runs in the test APK process, without target Kotlin dependencies. */
public class TestMusicProvider extends DocumentsProvider {
    public static final Uri tree = DocumentsContract.buildTreeDocumentUri("dev.petrov.ymplayer2.test.music", "music");
    public static final Uri secondaryTree = DocumentsContract.buildTreeDocumentUri("dev.petrov.ymplayer2.test.music", "secondary");
    private volatile boolean unavailable;
    private final AtomicBoolean holdScan = new AtomicBoolean();
    private volatile CountDownLatch scanEntered = new CountDownLatch(0);
    private volatile CountDownLatch scanReleased = new CountDownLatch(0);
    private boolean secondaryUnavailable;
    private boolean duplicateFirst;
    private boolean corrupt;
    private boolean extra;
    private boolean artwork;
    private boolean missingSecond;
    private int bulkCount;
    private boolean unsupportedFormats;
    private File directory() { File dir = new File(getContext().getCacheDir(), "test-music"); dir.mkdirs(); return dir; }
    @Override public boolean onCreate() { return true; }
    @Override public MatrixCursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS});
        cursor.newRow().add(Root.COLUMN_ROOT_ID, "music").add(Root.COLUMN_DOCUMENT_ID, "music").add(Root.COLUMN_TITLE, "YM2 Test Music").add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD);
        return cursor;
    }
    @Override public MatrixCursor queryDocument(String id, String[] projection) { MatrixCursor cursor = cursor(projection); addDocument(cursor, id); return cursor; }
    @Override public MatrixCursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        boolean disconnectedAtStart = unavailable;
        if (holdScan.compareAndSet(true, false)) {
            scanEntered.countDown();
            try { if (!scanReleased.await(15, TimeUnit.SECONDS)) throw new FileNotFoundException("Fixture scan gate timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new FileNotFoundException("Fixture scan interrupted"); }
        }
        if (disconnectedAtStart) throw new FileNotFoundException("Fixture storage disconnected");
        if (parent.equals("secondary") && secondaryUnavailable) throw new FileNotFoundException("Secondary fixture disconnected");
        MatrixCursor cursor = cursor(projection);
        if (parent.equals("music")) { addDocument(cursor, "one.wav"); if (duplicateFirst) addDocument(cursor, "one.wav"); addDocument(cursor, "nested"); if (artwork) addDocument(cursor, "cover.mp3"); if (corrupt) addDocument(cursor, "broken.wav"); if (extra) { addDocument(cursor, "three.wav"); addDocument(cursor, "four.wav"); } for (int i = 0; i < bulkCount; i++) addDocument(cursor, String.format(java.util.Locale.ROOT, "bulk-%03d.wav", i)); }
        if (parent.equals("music") && unsupportedFormats) {
            for (String id : new String[]{"ignored.aiff", "ignored.aif", "ignored.aifc", "audio-aiff", "fallback.WAV"}) addDocument(cursor, id);
        }
        else if (parent.equals("nested") && !missingSecond) addDocument(cursor, "two.wav");
        else if (parent.equals("secondary")) addDocument(cursor, "two.wav");
        return cursor;
    }
    @Override public boolean isChildDocument(String parent, String id) { return parent.equals("music") || (parent.equals("nested") || parent.equals("secondary")) && id.equals("two.wav"); }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        boolean bulk = id.matches("bulk-\\d{3,4}\\.wav") && Integer.parseInt(id.substring(5, id.length() - 4)) < bulkCount;
        if (unavailable || missingSecond && id.equals("two.wav") || !mode.equals("r") || !bulk && !Arrays.asList("one.wav", "two.wav", "three.wav", "four.wav", "broken.wav", "cover.mp3", "fallback.WAV").contains(id)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(new File(directory(), bulk ? "one.wav" : id), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("fixtures")) {
            holdScan.set(false); scanReleased.countDown();
            unavailable = false; corrupt = false; extra = false; artwork = false; missingSecond = false; bulkCount = 0;
            secondaryUnavailable = false; unsupportedFormats = false;
            duplicateFirst = false;
            try {
                wave(new File(directory(), "one.wav"), 440);
                wave(new File(directory(), "two.wav"), 660);
                wave(new File(directory(), "three.wav"), 550);
                wave(new File(directory(), "four.wav"), 330);
                wave(new File(directory(), "fallback.WAV"), 220);
                Files.write(new File(directory(), "broken.wav").toPath(), "not an audio file".getBytes());
            } catch (IOException error) { throw new IllegalStateException(error); }
            grant(); return Bundle.EMPTY;
        }
        if (method.equals("grant")) { grant(); return Bundle.EMPTY; }
        if (method.equals("grantSecondary")) { getContext().grantUriPermission("dev.petrov.ymplayer2.dev", secondaryTree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION); return Bundle.EMPTY; }
        if (method.equals("secondaryUnavailable")) { secondaryUnavailable = "true".equals(arg); return Bundle.EMPTY; }
        if (method.equals("duplicateFirst")) { duplicateFirst = "true".equals(arg); return Bundle.EMPTY; }
        if (method.equals("unavailable")) { unavailable = "true".equals(arg); return Bundle.EMPTY; }
        if (method.equals("holdScan")) { scanEntered = new CountDownLatch(1); scanReleased = new CountDownLatch(1); holdScan.set(true); return Bundle.EMPTY; }
        if (method.equals("waitScan")) {
            Bundle result = new Bundle();
            try { result.putBoolean("entered", scanEntered.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return result;
        }
        if (method.equals("releaseScan")) { scanReleased.countDown(); return Bundle.EMPTY; }
        if (method.equals("unsupportedFormats")) { unsupportedFormats = "true".equals(arg); return Bundle.EMPTY; }
        if (method.equals("corrupt")) { corrupt = true; return Bundle.EMPTY; }
        if (method.equals("extra")) { extra = true; return Bundle.EMPTY; }
        if (method.equals("bulk")) { bulkCount = Math.max(0, Math.min(5000, Integer.parseInt(arg))); return Bundle.EMPTY; }
        if (method.equals("missingSecond")) { missingSecond = "true".equals(arg); return Bundle.EMPTY; }
        if (method.equals("notify")) { getContext().getContentResolver().notifyChange(DocumentsContract.buildChildDocumentsUriUsingTree(tree, "music"), null); return Bundle.EMPTY; }
        if (method.equals("artwork")) {
            artwork = true;
            try (java.io.InputStream input = getContext().getAssets().open("cover.mp3")) {
                Files.copy(input, new File(directory(), "cover.mp3").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException error) { throw new IllegalStateException(error); }
            return Bundle.EMPTY;
        }
        return super.call(method, arg, extras);
    }
    private void grant() {
        getContext().grantUriPermission("dev.petrov.ymplayer2.dev", tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }
    private MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection != null ? projection : new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS});
    }
    private void addDocument(MatrixCursor cursor, String id) {
        boolean folder = id.equals("music") || id.equals("nested") || id.equals("secondary");
        File file = new File(directory(), id.startsWith("bulk-") ? "one.wav" : id);
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, id).add(Document.COLUMN_DISPLAY_NAME, id.equals("music") ? "Test Music" : id)
            .add(Document.COLUMN_MIME_TYPE, folder ? Document.MIME_TYPE_DIR : id.equals("audio-aiff") ? "audio/x-AIFF" : id.equals("fallback.WAV") || id.endsWith("aifc") ? "application/octet-stream" : id.endsWith("mp3") ? "audio/mpeg" : "audio/wav").add(Document.COLUMN_SIZE, folder ? 0L : file.length())
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
