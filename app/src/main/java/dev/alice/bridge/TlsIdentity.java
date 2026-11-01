package dev.alice.bridge;

import java.io.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;

public final class TlsIdentity {
    public record Contexts(SSLContext server, SSLContext upstream) {}
    public static Contexts load(File identity) throws Exception {
        return load(identity, null);
    }
    public static Contexts load(File identity, File originPins) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(identity)) { keys.load(in, new char[0]); }
        String alias = null;
        for (Enumeration<String> names = keys.aliases(); names.hasMoreElements();) {
            String name = names.nextElement(); if (keys.isKeyEntry(name)) { alias = name; break; }
        }
        if (alias == null) throw new GeneralSecurityException("В PKCS12 нет приватного ключа");
        X509Certificate leaf = (X509Certificate) keys.getCertificate(alias); leaf.checkValidity();
        PrivateKey key = (PrivateKey) keys.getKey(alias, new char[0]);
        Signature signer = Signature.getInstance(key.getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA");
        byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
        signer.initSign(key); signer.update(challenge); byte[] signed = signer.sign();
        signer.initVerify(leaf.getPublicKey()); signer.update(challenge);
        if (!signer.verify(signed)) throw new GeneralSecurityException("Сертификат и ключ не совпадают");
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, new char[0]);
        SSLContext server = SSLContext.getInstance("TLS"); server.init(km.getKeyManagers(), null, null);
        // Upstream TLS remains authenticated: platform CAs or the exact user-imported leaf certificate.
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init((KeyStore) null);
        X509TrustManager system = Arrays.stream(tm.getTrustManagers()).filter(t -> t instanceof X509TrustManager)
            .map(t -> (X509TrustManager) t).findFirst().orElseThrow();
        byte[] pinned = leaf.getEncoded();
        List<X509Certificate> trustedOrigins = new ArrayList<>();
        if (originPins != null) for (String host : List.of("quasar.yandex.net", "quasar.yandex.ru", "scbh.yandex.net")) {
            File file = new File(originPins, host + ".crt");
            if (!file.isFile()) continue;
            try (InputStream in = new FileInputStream(file)) {
                X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
                certificate.checkValidity(); verifyHostname(certificate, host); trustedOrigins.add(certificate);
            }
        }
        X509TrustManager trust = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { system.checkClientTrusted(chain, auth); }
            @Override public X509Certificate[] getAcceptedIssuers() { return system.getAcceptedIssuers(); }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                try { system.checkServerTrusted(chain, auth); }
                catch (CertificateException failure) {
                    if (chain.length == 0) throw failure;
                    boolean allowed = Arrays.equals(chain[0].getEncoded(), pinned);
                    for (X509Certificate origin : trustedOrigins) allowed |= Arrays.equals(chain[0].getEncoded(), origin.getEncoded());
                    if (!allowed) throw failure;
                    chain[0].checkValidity();
                }
            }
        };
        SSLContext upstream = SSLContext.getInstance("TLS"); upstream.init(null, new TrustManager[]{trust}, null);
        return new Contexts(server, upstream);
    }
    /** Explicit SAN verification also covers Android providers that ignore endpoint identification for a custom trust manager. */
    public static void verifyHostname(X509Certificate certificate, String hostname) throws CertificateException {
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        String host = hostname.toLowerCase(Locale.ROOT);
        if (names != null) for (List<?> entry : names) {
            if (entry.size() < 2 || !Integer.valueOf(2).equals(entry.get(0)) || !(entry.get(1) instanceof String)) continue;
            String pattern = ((String) entry.get(1)).toLowerCase(Locale.ROOT);
            if (host.equals(pattern)) return;
            if (pattern.startsWith("*.")) {
                int dot = host.indexOf('.');
                if (dot > 0 && host.substring(dot + 1).equals(pattern.substring(2))) return;
            }
        }
        throw new CertificateException("Имя сервера не соответствует сертификату: " + hostname);
    }
}
