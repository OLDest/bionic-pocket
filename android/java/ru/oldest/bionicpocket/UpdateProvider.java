package ru.oldest.bionicpocket;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public final class UpdateProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        if (!"/update.apk".equals(uri.getPath())) throw new FileNotFoundException();
        File file = new File(getContext().getCacheDir(), "update.apk");
        if (!file.isFile()) throw new FileNotFoundException();
        return file;
    }
    public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File file = file(uri);
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[]{"BionicPocket.apk", file.length()}); return cursor;
        } catch (FileNotFoundException e) { return null; }
    }
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
