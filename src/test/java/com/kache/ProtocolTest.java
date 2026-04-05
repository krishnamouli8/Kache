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
 * The server starts on a random-ish test port before each test
 * and shuts down after.
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
        assertEquals("OK", send("SET proto:key proto:value"));
        assertEquals("VALUE proto:value", send("GET proto:key"));
    }

    @Test
    @DisplayName("GET missing key returns NULL")
    void getMissingKey_returnsNull() throws IOException {
        assertEquals("NULL", send("GET proto:nonexistent"));
    }

    @Test
    @DisplayName("DEL returns count including cascaded keys")
    void del_returnsCascadeCount() throws IOException {
        send("SET proto:parent parentval");
        send("SET proto:child childval DEPENDS proto:parent");

        // Delete parent — should cascade to child
        assertEquals("COUNT 2", send("DEL proto:parent"));

        // Both should be gone
        assertEquals("NULL", send("GET proto:parent"));
        assertEquals("NULL", send("GET proto:child"));
    }

    @Test
    @DisplayName("DEL missing key returns COUNT 0")
    void delMissingKey_returnsZero() throws IOException {
        assertEquals("COUNT 0", send("DEL proto:ghost"));
    }

    @Test
    @DisplayName("SET with TTL and DEPENDS works")
    void setWithTtlAndDepends() throws IOException {
        assertEquals("OK", send("SET proto:base baseval"));
        assertEquals("OK", send("SET proto:derived derivedval TTL 60 DEPENDS proto:base"));
        assertEquals("VALUE derivedval", send("GET proto:derived"));
    }

    @Test
    @DisplayName("DEPS shows dependent keys")
    void deps_showsDependents() throws IOException {
        send("SET proto:root rootval");
        send("SET proto:leaf leafval DEPENDS proto:root");

        String response = send("DEPS proto:root");
        assertTrue(response.startsWith("DEPS "));
        assertTrue(response.contains("proto:leaf"));
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
}
