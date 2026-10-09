package dev.petrov.mediamonitor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Only immutable exported .txt copies are shared; the private live journal is never served. */
public final class ReportProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File resolve(Uri uri) throws FileNotFoundException {
        if(uri.getPathSegments().size()!=1 || uri.getLastPathSegment()==null ||
            !uri.getLastPathSegment().matches("MediaMonitor-[0-9]{8}-[0-9]{6}-[0-9]{3}\\.txt")) throw new FileNotFoundException();
        File dir=new File(getContext().getCacheDir(),"reports"), file=new File(dir,uri.getLastPathSegment());
        try { if(!file.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile()) || !file.isFile()) throw new FileNotFoundException(); }
        catch(IOException e) { throw new FileNotFoundException(); }
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!mode.equals("r")) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        try {
            File f=resolve(uri); String[] cols=projection==null ? new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE} : projection;
            MatrixCursor cursor=new MatrixCursor(cols); Object[] row=new Object[cols.length];
            for(int i=0;i<cols.length;i++) {
                if(cols[i].equals(OpenableColumns.DISPLAY_NAME)) row[i]=f.getName();
                if(cols[i].equals(OpenableColumns.SIZE)) row[i]=f.length();
            }
            cursor.addRow(row); return cursor;
        } catch(FileNotFoundException e) { return null; }
    }
    @Override public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
}
