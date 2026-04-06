package com.kache;

import com.kache.network.KacheServer;
import com.kache.store.KacheStore;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end protocol tests using raw TCP sockets.
 *
 * No client library — just raw socket I/O to prove the protocol
 * actually works as documented. This is how you'd test with telnet or nc.
 *
 * Each test uses a unique key prefix (t1:, t2:, etc.) to prevent
 * cross-test state pollution since the store is shared across the class.
 */
class ProtocolTest {

    /** Use a non-standard port to avoid conflicts with a running Kache instance */
    private static final int TEST_PORT = 17379;

    private static KacheStore store;
    private static KacheServer server;
    private Socket socket;
    private BufferedWriter writer;
    private BufferedReader reader;

    @BeforeAll
    static void startServer() throws InterruptedException {
        store = new KacheStore();
        server = new KacheServer(TEST_PORT, store);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @BeforeEach
    void connect() throws IOException {
        socket = new Socket("localhost", TEST_PORT);
        socket.setSoTimeout(3000); // 3 second timeout for reads
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    }

    @AfterEach
    void disconnect() throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    /**
     * Sends a command over the socket and reads the response.
     */
    private String send(String command) throws IOException {
        writer.write(command + "\n");
        writer.flush();
        return reader.readLine();
    }

    @Test
    @DisplayName("PING returns PONG")
    void ping_returnsPong() throws IOException {
        assertEquals("PONG", send("PING"));
    }

    @Test
    @DisplayName("SET and GET roundtrip over protocol")
    void setAndGet_overProtocol() throws IOException {
        assertEquals("OK", send("SET t1:key t1:value"));
        assertEquals("VALUE t1:value", send("GET t1:key"));
    }

    @Test
    @DisplayName("GET missing key returns NULL")
    void getMissingKey_returnsNull() throws IOException {
        assertEquals("NULL", send("GET t2:nonexistent"));
    }

    @Test
    @DisplayName("DEL returns count including cascaded keys")
    void del_returnsCascadeCount() throws IOException {
        send("SET t3:parent parentval");
        send("SET t3:child childval DEPENDS t3:parent");

        // Delete parent — should cascade to child
        assertEquals("COUNT 2", send("DEL t3:parent"));

        // Both should be gone
        assertEquals("NULL", send("GET t3:parent"));
        assertEquals("NULL", send("GET t3:child"));
    }

    @Test
    @DisplayName("DEL missing key returns COUNT 0")
    void delMissingKey_returnsZero() throws IOException {
        assertEquals("COUNT 0", send("DEL t4:ghost"));
    }

    @Test
    @DisplayName("SET with TTL and DEPENDS works")
    void setWithTtlAndDepends() throws IOException {
        assertEquals("OK", send("SET t5:base baseval"));
        assertEquals("OK", send("SET t5:derived derivedval TTL 60 DEPENDS t5:base"));
        assertEquals("VALUE derivedval", send("GET t5:derived"));
    }

    @Test
    @DisplayName("DEPS shows dependent keys")
    void deps_showsDependents() throws IOException {
        send("SET t6:root rootval");
        send("SET t6:leaf leafval DEPENDS t6:root");

        String response = send("DEPS t6:root");
        assertTrue(response.startsWith("DEPS "));
        assertTrue(response.contains("t6:leaf"));
    }

    @Test
    @DisplayName("STATS returns formatted stats line")
    void stats_returnsFormattedLine() throws IOException {
        String response = send("STATS");
        assertTrue(response.startsWith("STATS "));
        assertTrue(response.contains("keyCount="));
        assertTrue(response.contains("hits="));
        assertTrue(response.contains("misses="));
        assertTrue(response.contains("hitRate="));
        assertTrue(response.contains("cascades="));
    }

    @Test
    @DisplayName("Unknown command returns ERROR")
    void unknownCommand_returnsError() throws IOException {
        String response = send("FOOBAR");
        assertTrue(response.startsWith("ERROR"));
    }

    @Test
    @DisplayName("SET without enough args returns ERROR")
    void setMissingArgs_returnsError() throws IOException {
        String response = send("SET onlykey");
        assertTrue(response.startsWith("ERROR"));
    }

    @Test
    @DisplayName("GET without key returns ERROR")
    void getMissingArgs_returnsError() throws IOException {
        String response = send("GET");
        assertTrue(response.startsWith("ERROR"));
    }

    @Test
    @DisplayName("Commands are case-insensitive")
    void caseInsensitiveCommands() throws IOException {
        assertEquals("PONG", send("ping"));
        assertEquals("PONG", send("Ping"));
        assertEquals("PONG", send("pInG"));
    }

    @Test
    @DisplayName("TTL returns remaining seconds for key with TTL")
    void ttl_returnsRemainingSeconds() throws IOException {
        send("SET t7:session token TTL 300");

        String response = send("TTL t7:session");
        assertTrue(response.startsWith("TTL "));

        // Should be between 298-300 (accounting for test execution time)
        long ttl = Long.parseLong(response.substring(4).trim());
        assertTrue(ttl >= 295 && ttl <= 300, "TTL should be ~300, got " + ttl);
    }

    @Test
    @DisplayName("TTL returns -1 for key without TTL")
    void ttl_returnsMinusOneForNoTtl() throws IOException {
        send("SET t8:permanent value");
        assertEquals("TTL -1", send("TTL t8:permanent"));
    }

    @Test
    @DisplayName("TTL returns -2 for nonexistent key")
    void ttl_returnsMinusTwoForMissing() throws IOException {
        assertEquals("TTL -2", send("TTL t9:ghost"));
    }
}
