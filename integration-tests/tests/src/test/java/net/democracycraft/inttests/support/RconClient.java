package net.democracycraft.inttests.support;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Tiny stdlib-only RCON client for admin-side seeding inside integration tests.
 *
 * <p>Use this for things the mock player cannot do — op'ing players, giving
 * items, teleporting, triggering scheduled tax cycles. The mock player handles
 * the player-side perspective; RCON handles the operator-side perspective.
 *
 * <p>Connection details default to {@link DevServer#HOST} / port 25575
 * (server.properties' {@code rcon.port}) / password "dev". Override with
 * system properties {@code -Drcon.port}, {@code -Drcon.password} or env vars
 * {@code RCON_PORT}, {@code RCON_PASSWORD} if your dev box differs.
 */
public final class RconClient {

    private static final int RCON_PORT = Integer.parseInt(
            firstNonNull(System.getProperty("rcon.port"), System.getenv("RCON_PORT"), "25575"));
    private static final String RCON_PASSWORD =
            firstNonNull(System.getProperty("rcon.password"), System.getenv("RCON_PASSWORD"), "dev");

    private static final int TYPE_LOGIN = 3;
    private static final int TYPE_COMMAND = 2;

    /**
     * Send a single RCON command and return the server's response.
     *
     * @throws RuntimeException if connection / authentication / I/O fails
     */
    public static String send(String command) {
        try (Socket s = new Socket(DevServer.HOST, RCON_PORT)) {
            s.setSoTimeout(5_000);
            // Log in
            writePacket(s, 1, TYPE_LOGIN, RCON_PASSWORD);
            int loginId = readPacket(s).requestId;
            if (loginId == -1) {
                throw new RuntimeException("RCON authentication failed");
            }
            // Command
            writePacket(s, 2, TYPE_COMMAND, command);
            return readPacket(s).body;
        } catch (IOException e) {
            throw new RuntimeException("RCON " + DevServer.HOST + ":" + RCON_PORT + " failed: " + e.getMessage(), e);
        }
    }

    private static void writePacket(Socket s, int requestId, int type, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.US_ASCII);
        int length = 4 + 4 + payload.length + 2;
        ByteBuffer buf = ByteBuffer.allocate(4 + length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(length);
        buf.putInt(requestId);
        buf.putInt(type);
        buf.put(payload);
        buf.put((byte) 0);
        buf.put((byte) 0);
        s.getOutputStream().write(buf.array());
        s.getOutputStream().flush();
    }

    private static Packet readPacket(Socket s) throws IOException {
        byte[] header = readN(s, 4);
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] payload = readN(s, length);
        ByteBuffer pb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int requestId = pb.getInt();
        int type = pb.getInt();
        // body is everything except the trailing two NUL bytes
        String body = new String(payload, 8, length - 10, StandardCharsets.UTF_8);
        return new Packet(requestId, type, body);
    }

    private static byte[] readN(Socket s, int n) throws IOException {
        byte[] out = new byte[n];
        int read = 0;
        while (read < n) {
            int r = s.getInputStream().read(out, read, n - read);
            if (r < 0) throw new IOException("rcon socket closed mid-packet");
            read += r;
        }
        return out;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) if (v != null) return v;
        return null;
    }

    private record Packet(int requestId, int type, String body) {}

    private RconClient() {}
}
