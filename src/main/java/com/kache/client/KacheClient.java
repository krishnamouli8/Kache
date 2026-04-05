package com.kache.client;

import java.io.*;
import java.net.Socket;
import java.util.List;

/**
 * Simple TCP client for the Kache protocol.
 *
 * Connects to a Kache server over a raw TCP socket and sends line-based commands.
 * Intended for demos and integration testing — not for production use.
 *
 * Usage example:
 *   try (KacheClient client = new KacheClient("localhost", 7379)) {
 *       System.out.println(client.ping());         // → PONG
 *       System.out.println(client.set("k", "v"));  // → OK
 *       System.out.println(client.get("k"));        // → VALUE v
 *   }
 */
public class KacheClient implements AutoCloseable {

    private final Socket socket;
    private final BufferedWriter writer;
    private final BufferedReader reader;

    /**
     * Opens a TCP connection to the Kache server.
     *
     * @param host server hostname or IP
     * @param port server TCP port (default 7379)
     * @throws IOException if connection fails
     */
    public KacheClient(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.socket.setSoTimeout(5000); // 5 second read timeout to avoid hanging
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
    }

    /**
     * Sends a raw command string and reads the single-line response.
     *
     * @param command the full command string (e.g., "SET key value TTL 60")
     * @return the server's response line
     * @throws IOException if the connection is broken
     */
    public String send(String command) throws IOException {
        writer.write(command);
        writer.newLine();
        writer.flush();
        return reader.readLine();
    }

    /** PING → PONG */
    public String ping() throws IOException {
        return send("PING");
    }

    /** SET key value (no TTL, no dependencies) */
    public String set(String key, String value) throws IOException {
        return send("SET " + key + " " + value);
    }

    /** SET key value TTL seconds */
    public String set(String key, String value, long ttlSeconds) throws IOException {
        return send("SET " + key + " " + value + " TTL " + ttlSeconds);
    }

    /** SET key value DEPENDS parent1,parent2,... */
    public String setWithDeps(String key, String value, List<String> dependsOn) throws IOException {
        return send("SET " + key + " " + value + " DEPENDS " + String.join(",", dependsOn));
    }

    /** SET key value TTL seconds DEPENDS parent1,parent2,... */
    public String setWithTtlAndDeps(String key, String value, long ttlSeconds, List<String> dependsOn) throws IOException {
        return send("SET " + key + " " + value + " TTL " + ttlSeconds + " DEPENDS " + String.join(",", dependsOn));
    }

    /** GET key → VALUE <data> or NULL */
    public String get(String key) throws IOException {
        return send("GET " + key);
    }

    /** DEL key → COUNT <n> */
    public String delete(String key) throws IOException {
        return send("DEL " + key);
    }

    /** DEPS key → DEPS <child1,child2,...> */
    public String deps(String key) throws IOException {
        return send("DEPS " + key);
    }

    /** STATS → full stats line */
    public String stats() throws IOException {
        return send("STATS");
    }

    /**
     * Closes the TCP connection and releases resources.
     */
    @Override
    public void close() throws IOException {
        writer.close();
        reader.close();
        socket.close();
    }

    /**
     * Demo: connects to localhost:7379 and demonstrates cascade invalidation.
     * Run this after starting the Kache server.
     */
    public static void main(String[] args) {
        try (KacheClient client = new KacheClient("localhost", 7379)) {
            System.out.println("--- Kache Client Demo ---");
            System.out.println();

            // Health check
            System.out.println("PING  → " + client.ping());

            // Set up a user profile cache hierarchy
            System.out.println("SET user:123         → " + client.set("user:123", "krishna"));
            System.out.println("SET user:123:feed    → " + client.setWithDeps("user:123:feed", "feed_data", List.of("user:123")));
            System.out.println("SET user:123:recs    → " + client.setWithDeps("user:123:recs", "recs_data", List.of("user:123")));
            System.out.println("SET user:123:badges  → " + client.setWithDeps("user:123:badges", "badge_data", List.of("user:123")));
            System.out.println();

            // Check dependencies
            System.out.println("DEPS user:123 → " + client.deps("user:123"));
            System.out.println();

            // Verify all keys exist
            System.out.println("GET user:123         → " + client.get("user:123"));
            System.out.println("GET user:123:feed    → " + client.get("user:123:feed"));
            System.out.println("GET user:123:recs    → " + client.get("user:123:recs"));
            System.out.println("GET user:123:badges  → " + client.get("user:123:badges"));
            System.out.println();

            // CASCADE: delete the parent — all children should be invalidated
            System.out.println("--- Cascade Invalidation ---");
            System.out.println("DEL user:123 → " + client.delete("user:123"));
            System.out.println();

            // Verify all keys are gone
            System.out.println("GET user:123         → " + client.get("user:123"));
            System.out.println("GET user:123:feed    → " + client.get("user:123:feed"));
            System.out.println("GET user:123:recs    → " + client.get("user:123:recs"));
            System.out.println("GET user:123:badges  → " + client.get("user:123:badges"));
            System.out.println();

            // Stats
            System.out.println("STATS → " + client.stats());

        } catch (IOException e) {
            System.err.println("Failed to connect to Kache server: " + e.getMessage());
            System.err.println("Make sure the server is running on localhost:7379");
        }
    }
}
