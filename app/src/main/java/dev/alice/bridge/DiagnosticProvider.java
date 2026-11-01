package dev.alice.bridge;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants read access only to completed diagnostic ZIP files selected for sharing. */
public final class DiagnosticProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    static File directory(Context c){return new File(c.getCacheDir(),"diagnostic-exports");}
    static Uri uri(Context c,File file){return new Uri.Builder().scheme("content").authority(c.getPackageName()+".diagnostics").appendPath(file.getName()).build();}
    private File file(Uri uri){
        if(!"content".equals(uri.getScheme())||!(getContext().getPackageName()+".diagnostics").equals(uri.getAuthority())||uri.getPathSegments().size()!=1)throw new SecurityException("Invalid diagnostic URI");
        String name=uri.getLastPathSegment();
        if(name==null||!name.matches("AliceBridge-diagnostics-[0-9]{8}-[0-9]{6}-[0-9]+\\.zip"))throw new SecurityException("Invalid diagnostic filename");
        File file=new File(directory(getContext()),name);
        if(!file.isFile())throw new IllegalArgumentException("Diagnostic file is unavailable");return file;
    }
    @Override public String getType(Uri uri){file(uri);return "application/zip";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
        File file=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor result=new MatrixCursor(columns,1);Object[] row=new Object[columns.length];
        for(int i=0;i<columns.length;i++){if(columns[i].equals(OpenableColumns.DISPLAY_NAME))row[i]=file.getName();else if(columns[i].equals(OpenableColumns.SIZE))row[i]=file.length();}result.addRow(row);return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!mode.equals("r"))throw new SecurityException("Diagnostics are read-only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] selectionArgs){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){throw new UnsupportedOperationException();}
}
