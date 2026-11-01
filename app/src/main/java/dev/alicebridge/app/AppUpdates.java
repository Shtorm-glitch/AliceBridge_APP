package dev.alicebridge.app;

import android.content.*;
import android.content.pm.*;
import dev.alicebridge.bridge.Streams;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class AppUpdates {
    interface Progress {
        void update(long downloaded, long total);
    }

    static long installedCode(Context c) throws Exception {
        return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
    }

    static UpdateInfo.Release check() throws Exception {
        HttpURLConnection connection = connect(UpdateInfo.ORIGIN + "/AliceBridge-update.json");
        try {
            if (connection.getResponseCode() != 200)
                throw new IOException("Сервер обновлений: HTTP " + connection.getResponseCode());
            byte[] data;
            try (InputStream in = connection.getInputStream()) {
                data = Streams.readBounded(in, 65537);
            }
            if (data.length > 65536) throw new IOException("Слишком большой ответ сервера");
            return UpdateInfo.parse(new String(data, StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection connect(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setUseCaches(false);
        return connection;
    }

    static File download(Context c, UpdateInfo.Release release, Progress progress)
            throws Exception {
        File directory = UpdateProvider.directory(c);
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Нет места для обновления");
        File ready = new File(directory, "AliceBridge-update-" + release.code() + ".apk");
        // Serialize writes across recreated screens; never hand an incomplete file to the
        // installer.
        synchronized (AppUpdates.class) {
            if (ready.exists()) {
                try {
                    verify(c, ready, release);
                    return ready;
                } catch (Exception invalid) {
                    ready.delete();
                }
            }
            File temporary = new File(directory, "AliceBridge-update-" + release.code() + ".part");
            long received = temporary.isFile() ? temporary.length() : 0;
            if (received > release.size()) {
                temporary.delete();
                received = 0;
            }
            long lastUi = 0;
            String lastError = "";
            try {
                for (int attempt = 0; attempt < 5; attempt++) {
                    if (received >= release.size()) break;
                    HttpURLConnection connection = connect(release.url());
                    connection.setReadTimeout(60000);
                    if (received > 0)
                        connection.setRequestProperty("Range", "bytes=" + received + "-");
                    try {
                        int code = connection.getResponseCode();
                        if (code == 416 && received == release.size()) break;
                        if (code == 200) received = 0;
                        else if (code != 206) throw new IOException("Загрузка: HTTP " + code);
                        try (InputStream in = connection.getInputStream();
                                OutputStream out = new FileOutputStream(temporary, received > 0)) {
                            byte[] buffer = new byte[65536];
                            int n;
                            while ((n = in.read(buffer)) != -1) {
                                if (Thread.currentThread().isInterrupted())
                                    throw new InterruptedIOException("Загрузка отменена");
                                received += n;
                                if (received > release.size())
                                    throw new IOException("Размер APK не совпадает");
                                out.write(buffer, 0, n);
                                long now = System.currentTimeMillis();
                                if (now - lastUi > 150) {
                                    progress.update(received, release.size());
                                    lastUi = now;
                                }
                            }
                        }
                    } catch (IOException error) {
                        lastError =
                                error.getMessage() == null
                                        ? error.getClass().getSimpleName()
                                        : error.getMessage();
                        if (Thread.currentThread().isInterrupted()) throw error;
                        if (attempt < 4) Thread.sleep(1500L * (attempt + 1));
                    } finally {
                        connection.disconnect();
                    }
                }
                if (received != release.size())
                    throw new IOException(
                            "APK скачан не полностью: "
                                    + received
                                    + " из "
                                    + release.size()
                                    + " байт"
                                    + (lastError.isEmpty() ? "" : " · " + lastError));
                verify(c, temporary, release);
                if (!temporary.renameTo(ready))
                    throw new IOException("Не удалось подготовить установку");
                progress.update(received, release.size());
                return ready;
            } catch (Exception failure) {
                if (temporary.length() > release.size()) temporary.delete();
                throw failure;
            }
        }
    }

    static void verify(Context c, File file, UpdateInfo.Release release) throws Exception {
        if (file.length() != release.size()) throw new IOException("Размер APK не совпадает");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] b = new byte[32768];
            int n;
            while ((n = input.read(b)) != -1) hash.update(b, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : hash.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!hex.toString().equals(release.sha256()))
            throw new IOException("Контрольная сумма APK не совпадает");
        PackageManager pm = c.getPackageManager();
        PackageInfo installed =
                pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo candidate =
                pm.getPackageArchiveInfo(
                        file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null
                || !c.getPackageName().equals(candidate.packageName)
                || candidate.getLongVersionCode() != release.code()
                || !release.version().equals(candidate.versionName)
                || candidate.getLongVersionCode() <= installed.getLongVersionCode())
            throw new IOException("APK не является новой версией AliceBridge");
        if (candidate.signingInfo == null
                || installed.signingInfo == null
                || !signaturesMatch(
                        installed.signingInfo.getApkContentsSigners(),
                        candidate.signingInfo.getApkContentsSigners()))
            throw new IOException("Подпись APK не совпадает с установленным AliceBridge");
    }

    static boolean signaturesMatch(
            android.content.pm.Signature[] installed, android.content.pm.Signature[] candidate) {
        if (installed.length == 0 || installed.length != candidate.length) return false;
        return new HashSet<>(Arrays.asList(installed))
                .equals(new HashSet<>(Arrays.asList(candidate)));
    }
}
