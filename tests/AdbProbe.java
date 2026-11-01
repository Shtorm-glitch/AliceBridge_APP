package dev.alice.bridge;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Read-only protocol handshake. Does not execute commands, enable adbd or claim root. */
public final class AdbProbe {
    public static String check(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            try { socket.connect(new InetSocketAddress(host, port), 2500); }
            catch (SocketTimeoutException e) { return "TCP 5555 · тайм-аут подключения; ADB ещё не доступен"; }
            catch (ConnectException e) { return "TCP 5555 · подключение отклонено или адрес недоступен"; }
            socket.setSoTimeout(5000);
            byte[] body = "host::\0".getBytes(StandardCharsets.US_ASCII);
            int command = 0x4e584e43; // CNXN, little endian
            int sum = 0; for (byte b : body) sum += b & 255;
            byte[] header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(0x01000000).putInt(4096).putInt(body.length).putInt(sum).putInt(command ^ -1).array();
            socket.getOutputStream().write(header); socket.getOutputStream().write(body); socket.getOutputStream().flush();
            byte[] received = new byte[24];
            try { new DataInputStream(socket.getInputStream()).readFully(received); }
            catch (SocketTimeoutException e) { return "TCP 5555 открыт · нет ответа ADB на CNXN"; }
            catch (EOFException e) { return "TCP 5555 открыт · соединение закрыто до ответа ADB"; }
            ByteBuffer reply = ByteBuffer.wrap(received).order(ByteOrder.LITTLE_ENDIAN);
            int cmd = reply.getInt(); reply.getInt(); reply.getInt(); int length = reply.getInt();
            int checksum = reply.getInt(); int magic = reply.getInt();
            if (magic != (cmd ^ -1) || length < 0 || length > 1024 * 1024)
                throw new IOException("Порт открыт, но некорректный заголовок ADB");
            byte[] payload = new byte[length];
            try { new DataInputStream(socket.getInputStream()).readFully(payload); }
            catch (SocketTimeoutException e) { return "Ответ ADB начался · тайм-аут чтения тела"; }
            int actual = 0; for (byte b : payload) actual += b & 255;
            if (checksum != 0 && checksum != actual) throw new IOException("Некорректная контрольная сумма ADB");
            return switch (cmd) {
                case 0x4e584e43 -> "ADB доступен · CNXN";
                case 0x48545541 -> "ADB найден · требуется авторизация (AUTH)";
                case 0x534c5453 -> "ADB найден · требуется TLS (STLS)";
                default -> throw new IOException("Порт открыт · неизвестный ответ ADB");
            };
        }
    }
}
