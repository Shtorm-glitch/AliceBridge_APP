package dev.alicebridge.station;

import android.content.Context;
import dev.alicebridge.bridge.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Presentation only: config lives in /data and never overwrites /etc/fastfetch. */
final class FastfetchAppearance {
    static void apply(Context context, NativeAdb adb, boolean android) throws Exception {
        String bin=android?"/data/local/bin":"/usr/local/bin";
        String stage="/data/local/tmp/ab-fastfetch-style-"+UUID.randomUUID().toString().replace("-","");
        require(adb.shell("mkdir -p "+stage));
        try {
            for(String name:new String[]{"yandexstation.txt","compact.jsonc","wide.jsonc","launcher.sh"}) {
                File local=File.createTempFile("fastfetch-style-",".tmp",context.getCacheDir());
                try {
                    byte[] data;
                    try(InputStream input=context.getAssets().open("fastfetch/"+name)){data=Streams.readBounded(input,32768);}
                    if(name.equals("launcher.sh")) {
                        String text=new String(data,StandardCharsets.UTF_8).replace("__BIN__",bin);
                        if(android)text=text.replace("#!/bin/sh","#!/system/bin/sh");
                        data=text.getBytes(StandardCharsets.UTF_8);
                    }
                    try(OutputStream output=new FileOutputStream(local)){output.write(data);}
                    require(adb.push(local,stage+"/"+name));
                } finally {local.delete();}
            }
            String install="set -e\nBASE=/data/quasar/alicebridge-fastfetch\nBIN="+bin+"\nSRC="+stage+"\n"+(android?"":"""
                ab_ro=0
                case ",$(awk '$2=="/"{print $4;exit}' /proc/mounts)," in *,ro,*) ab_ro=1;mount -o remount,rw /;;esac
                trap '[ "$ab_ro" != 1 ] || mount -o remount,ro /' EXIT
                """)+"""
                [ ! -L "$BASE" ]
                mkdir -p "$BASE"
                for name in yandexstation.txt compact.jsonc wide.jsonc; do
                    [ ! -L "$BASE/$name" ] && [ ! -L "$BASE/$name.new" ]
                    cp "$SRC/$name" "$BASE/$name.new"
                    chmod 644 "$BASE/$name.new"
                    mv "$BASE/$name.new" "$BASE/$name"
                done
                [ ! -L "$BIN/fastfetch" ] && [ ! -L "$BIN/fastfetch.real" ] && [ ! -L "$BIN/fastfetch.new" ] && [ ! -L "$BIN/fastfetch.real.new" ]
                if ! grep -qF '# AliceBridge fastfetch appearance v1' "$BIN/fastfetch"; then
                    [ ! -e "$BIN/fastfetch.real" ] || { echo 'Найден сторонний fastfetch.real';exit 1; }
                    cp "$BIN/fastfetch" "$BIN/fastfetch.real.new"
                    chmod 755 "$BIN/fastfetch.real.new"
                    mv "$BIN/fastfetch.real.new" "$BIN/fastfetch.real"
                fi
                [ -x "$BIN/fastfetch.real" ]
                cp "$SRC/launcher.sh" "$BIN/fastfetch.new"
                chmod 755 "$BIN/fastfetch.new"
                mv "$BIN/fastfetch.new" "$BIN/fastfetch"
                "$BIN/fastfetch" --version
                echo AB_FASTFETCH_APPEARANCE_OK
                """;
            require(MaxCommands.shell(context,adb,install));
        } finally {try{adb.shell("rm -rf "+stage);}catch(Exception ignored){}}
    }
    private static void require(NativeAdb.Result result)throws IOException{if(result.code()!=0)throw new IOException(result.output());}
}
