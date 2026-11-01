package dev.alice.bridge;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Narrow read-only grant for the Android package installer. */
public final class UpdateProvider extends ContentProvider {
    static File directory(Context c){return new File(c.getCacheDir(),"app-updates");}
    static Uri uri(Context c,File file){return new Uri.Builder().scheme("content").authority(c.getPackageName()+".updates").appendPath(file.getName()).build();}
    @Override public boolean onCreate(){return true;}
    private File file(Uri uri){
        if(!(getContext().getPackageName()+".updates").equals(uri.getAuthority())||uri.getPathSegments().size()!=1||uri.getLastPathSegment()==null||!uri.getLastPathSegment().matches("AliceBridge-update-[0-9]+\\.apk"))throw new SecurityException("Invalid update URI");
        File file=new File(directory(getContext()),uri.getLastPathSegment());if(!file.isFile())throw new IllegalArgumentException("Update unavailable");return file;
    }
    @Override public String getType(Uri uri){file(uri);return "application/vnd.android.package-archive";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){File file=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns,1);Object[] values=new Object[columns.length];for(int i=0;i<values.length;i++)if(columns[i].equals(OpenableColumns.DISPLAY_NAME))values[i]=file.getName();else if(columns[i].equals(OpenableColumns.SIZE))values[i]=file.length();cursor.addRow(values);return cursor;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new SecurityException("Read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
