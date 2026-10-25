package com.carmelita.debloattv;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Cliente mínimo del protocolo ADB por TCP (el mismo que usa "adb connect IP:5555").
 * Una sola operación a la vez: no hace falta más para ir lanzando comandos uno tras otro.
 */
final class AdbConnection implements Closeable {
    static final int DEFAULT_PORT = 5555;

    private static final int CNXN = 0x4e584e43;
    private static final int AUTH = 0x48545541;
    private static final int OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f;
    private static final int CLSE = 0x45534c43;
    private static final int WRTE = 0x45545257;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 256 * 1024;
    private static final int SYNC_CHUNK = 64 * 1024;

    /** Tiempo para pulsar "Permitir" en la TV la primera vez. */
    private static final int AUTH_TIMEOUT_MS = 120_000;
    private static final int COMMAND_TIMEOUT_MS = 120_000;

    interface AuthListener {
        void onWaitingForApproval();
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int maxPayload = 4096;
    private int nextLocalId = 1;

    private AdbConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    static AdbConnection connect(String host, int port, AdbKey key, AuthListener listener) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(AUTH_TIMEOUT_MS);
        AdbConnection c = new AdbConnection(socket);
        try {
            c.handshake(key, listener);
        } catch (IOException e) {
            c.close();
            throw e;
        }
        socket.setSoTimeout(COMMAND_TIMEOUT_MS);
        return c;
    }

    private void handshake(AdbKey key, AuthListener listener) throws IOException {
        send(CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        boolean sentKey = false;
        while (true) {
            Message m;
            try {
                m = read();
            } catch (EOFException e) {
                throw new IOException(sentKey
                        ? "La TV rechazó la conexión. Vuelve a intentarlo y pulsa \"Permitir\"."
                        : "La TV cerró la conexión.", e);
            } catch (SocketTimeoutException e) {
                throw new IOException("No se aceptó el aviso de depuración en la TV a tiempo.", e);
            }
            if (m.command == CNXN) {
                maxPayload = Math.min(m.arg1, MAX_PAYLOAD);
                return;
            }
            if (m.command != AUTH || m.arg0 != AUTH_TOKEN) continue;
            if (!signed) {
                // Si la TV ya conoce nuestra clave, con la firma basta.
                send(AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
                signed = true;
            } else if (!sentKey) {
                send(AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload());
                sentKey = true;
                if (listener != null) listener.onWaitingForApproval();
            } else {
                throw new IOException("La TV no aceptó la clave del teléfono.");
            }
        }
    }

    /** Ejecuta un comando en la TV y devuelve su salida (stdout y stderr juntos). */
    synchronized String shell(String command) throws IOException {
        int local = nextLocalId++;
        send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == WRTE) {
                output.write(m.data);
                send(OKAY, local, m.arg0, null);
            } else if (m.command == CLSE) {
                if (m.arg0 != 0) send(CLSE, local, m.arg0, null);
                return output.toString("UTF-8").replace("\r", "").trim();
            }
        }
    }

    /** Copia un archivo a la TV con el servicio "sync:" (equivalente a "adb push"). */
    synchronized void push(byte[] data, String remotePath, int mode) throws IOException {
        int local = nextLocalId++;
        send(OPEN, local, 0, "sync:\0".getBytes(StandardCharsets.UTF_8));
        int remote = awaitOkay(local);

        ByteArrayOutputStream req = new ByteArrayOutputStream();
        byte[] spec = (remotePath + "," + mode).getBytes(StandardCharsets.UTF_8);
        syncHeader(req, "SEND", spec.length);
        req.write(spec);
        for (int off = 0; off < data.length; off += SYNC_CHUNK) {
            int n = Math.min(SYNC_CHUNK, data.length - off);
            syncHeader(req, "DATA", n);
            req.write(data, off, n);
        }
        syncHeader(req, "DONE", (int) (System.currentTimeMillis() / 1000));

        byte[] bytes = req.toByteArray();
        for (int off = 0; off < bytes.length; off += maxPayload) {
            int n = Math.min(maxPayload, bytes.length - off);
            byte[] piece = new byte[n];
            System.arraycopy(bytes, off, piece, 0, n);
            send(WRTE, local, remote, piece);
            awaitOkay(local);
        }

        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        while (reply.size() < 8) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == WRTE) {
                reply.write(m.data);
                send(OKAY, local, remote, null);
            } else if (m.command == CLSE) {
                throw new IOException("La TV cerró la copia del archivo");
            }
        }
        byte[] r = reply.toByteArray();
        String status = new String(r, 0, 4, StandardCharsets.US_ASCII);
        send(CLSE, local, remote, null);
        if (!"OKAY".equals(status)) {
            String msg = r.length > 8 ? new String(r, 8, r.length - 8, StandardCharsets.UTF_8) : status;
            throw new IOException("No se pudo copiar el archivo: " + msg);
        }
    }

    private int awaitOkay(int local) throws IOException {
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.command == OKAY) return m.arg0;
            if (m.command == CLSE) throw new IOException("La TV rechazó la operación");
        }
    }

    private static void syncHeader(ByteArrayOutputStream out, String id, int value) {
        out.write(id.getBytes(StandardCharsets.US_ASCII), 0, 4);
        byte[] le = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
        out.write(le, 0, 4);
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        int length = data == null ? 0 : data.length;
        int checksum = 0;
        for (int i = 0; i < length; i++) checksum += data[i] & 0xff;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(length).putInt(checksum).putInt(~command);
        out.write(header.array());
        if (length > 0) out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] raw = new byte[24];
        in.readFully(raw);
        ByteBuffer h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int length = h.getInt();
        if (length < 0 || length > 16 * 1024 * 1024) throw new IOException("Respuesta ADB no válida");
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static final class Message {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }
}
