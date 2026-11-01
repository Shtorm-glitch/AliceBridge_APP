package dev.alicebridge.station;

import dev.alicebridge.bridge.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Station 1's reviewed boot exposes a root PTY on 2323. Reached only through our ADB forward. */
public final class StationRootProcess extends Process {
    private final Socket socket = new Socket();
    private final InputStream raw;
    private final OutputStream wire;
    private volatile boolean closed;
    private final InputStream input =
            new InputStream() {
                @Override
                public int read() throws IOException {
                    while (true) {
                        int b = raw.read();
                        if (b < 0) {
                            destroy();
                            return -1;
                        }
                        if (b != 255) return b;
                        int command = raw.read();
                        if (command == 255) return 255;
                        if (command >= 251 && command <= 254) {
                            int option = raw.read();
                            if (option < 0) throw new EOFException();
                            if (command == 253) reply(252, option);
                            else if (command == 251)
                                reply(option == 1 || option == 3 ? 253 : 254, option);
                        } else if (command == 250) {
                            int previous = -1, current;
                            do {
                                current = raw.read();
                                if (current < 0) throw new EOFException();
                                if (previous == 255 && current == 240) break;
                                previous = current;
                            } while (true);
                        } else if (command < 0) throw new EOFException();
                    }
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (length == 0) return 0;
                    int first = read();
                    if (first < 0) return -1;
                    bytes[offset] = (byte) first;
                    int count = 1;
                    while (count < length && raw.available() > 0) {
                        int value = read();
                        if (value < 0) break;
                        bytes[offset + count++] = (byte) value;
                    }
                    return count;
                }

                @Override
                public void close() {
                    destroy();
                }
            };
    private final OutputStream output =
            new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    synchronized (wire) {
                        wire.write(value);
                        if ((value & 255) == 255) wire.write(255);
                    }
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    synchronized (wire) {
                        for (int i = offset; i < offset + length; i++) {
                            int value = bytes[i] & 255;
                            wire.write(value);
                            if (value == 255) wire.write(255);
                        }
                    }
                }

                @Override
                public void flush() throws IOException {
                    synchronized (wire) {
                        wire.flush();
                    }
                }

                @Override
                public void close() {
                    destroy();
                }
            };

    private void reply(int command, int option) throws IOException {
        synchronized (wire) {
            wire.write(new byte[] {(byte) 255, (byte) command, (byte) option});
            wire.flush();
        }
    }

    private void awaitPrompt() throws IOException {
        StringBuilder banner = new StringBuilder();
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            int value = input.read();
            if (value < 0) throw new EOFException("Root-оболочка закрылась");
            banner.append((char) value);
            if (banner.length() > 4096 || System.currentTimeMillis() > deadline)
                throw new IOException("Нет приглашения root-оболочки");
            if (banner.toString().endsWith("# ")) return;
        }
    }

    StationRootProcess(int port, String command, boolean interactive) throws IOException {
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            raw = socket.getInputStream();
            wire = socket.getOutputStream();
            awaitPrompt();
            output.write("/system/bin/busybox-smp stty -echo\r\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            awaitPrompt();
            socket.setSoTimeout(interactive ? 0 : 90000);
            output.write((command + "; exit\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException error) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw error;
        }
    }

    @Override
    public InputStream getInputStream() {
        return input;
    }

    @Override
    public OutputStream getOutputStream() {
        return output;
    }

    @Override
    public InputStream getErrorStream() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() throws InterruptedException {
        while (!closed) Thread.sleep(50);
        return 0;
    }

    @Override
    public int exitValue() {
        if (!closed) throw new IllegalThreadStateException();
        return 0;
    }

    @Override
    public boolean isAlive() {
        return !closed;
    }

    @Override
    public void destroy() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public Process destroyForcibly() {
        destroy();
        return this;
    }
}
