import dev.alice.bridge.TlsIdentity;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

public final class TlsIdentityTest {
    public static void main(String[] args) throws Exception {
        TlsIdentity.Contexts contexts = TlsIdentity.load(new File(args[0]));
        handshake(contexts, "quasar.yandex.net", true);
        handshake(contexts, "unrelated.invalid", false);
        System.out.println("PASS: PKCS12 key/certificate match, pinned upstream TLS, wrong hostname rejected");
    }
    private static void handshake(TlsIdentity.Contexts contexts, String host, boolean expected) throws Exception {
        try (SSLServerSocket listener = (SSLServerSocket) contexts.server().getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> server = CompletableFuture.runAsync(() -> {
                try (SSLSocket peer = (SSLSocket) listener.accept()) { peer.setSoTimeout(5000); peer.startHandshake(); }
                catch (IOException e) { if (expected) throw new RuntimeException(e); }
            });
            boolean passed = false;
            try (Socket raw = new Socket("127.0.0.1", listener.getLocalPort());
                 SSLSocket client = (SSLSocket) contexts.upstream().getSocketFactory().createSocket(raw, host, listener.getLocalPort(), true)) {
                SSLParameters parameters = client.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
                client.setSSLParameters(parameters); client.setSoTimeout(5000); client.startHandshake(); passed = true;
            } catch (SSLHandshakeException e) { if (expected) throw e; }
            if (passed != expected) throw new AssertionError("TLS hostname verification: " + host);
            server.get(6, TimeUnit.SECONDS);
        }
    }
}
