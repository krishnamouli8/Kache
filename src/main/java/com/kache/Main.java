package com.kache;

import com.kache.http.HttpApi;
import com.kache.network.KacheServer;
import com.kache.store.KacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for Kache — starts both the TCP and HTTP servers
 * against a single shared {@link KacheStore} instance.
 *
 * Default ports:
 *   TCP  → 7379 (for cache protocol commands via telnet/nc/client)
 *   HTTP → 8080 (for health checks and stats API)
 *
 * Both servers share the same KacheStore, so data written via TCP
 * is immediately visible via the HTTP stats endpoint, and vice versa.
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /** Default TCP port for the Kache protocol server */
    private static final int DEFAULT_TCP_PORT = 7379;

    /** Default HTTP port for the stats/health API */
    private static final int DEFAULT_HTTP_PORT = 8080;

    public static void main(String[] args) {
        logger.info("=== Kache starting ===");

        // Single shared store — both TCP and HTTP operate on the same data
        KacheStore store = new KacheStore();

        // --- Start TCP server (Netty) ---
        int tcpPort = DEFAULT_TCP_PORT;
        KacheServer tcpServer = new KacheServer(tcpPort, store);
        try {
            tcpServer.start();
        } catch (InterruptedException e) {
            logger.error("Failed to start TCP server on port {}", tcpPort, e);
            Thread.currentThread().interrupt();
            System.exit(1);
        } catch (Exception e) {
            logger.error("TCP server startup failed", e);
            System.exit(1);
        }

        // --- Start HTTP API (Javalin) ---
        int httpPort = DEFAULT_HTTP_PORT;
        try {
            HttpApi.start(store, httpPort);
        } catch (Exception e) {
            logger.error("Failed to start HTTP API on port {}", httpPort, e);
            tcpServer.stop(); // Clean up TCP server if HTTP fails
            System.exit(1);
        }

        // --- Register shutdown hook for graceful cleanup ---
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("=== Kache shutting down ===");
            HttpApi.stop();
            tcpServer.stop();
            store.shutdown(); // Stop background TTL cleaner
            logger.info("=== Kache stopped ===");
        }, "kache-shutdown"));

        logger.info("=== Kache is ready ===");
        logger.info("  TCP server : port {}", tcpPort);
        logger.info("  HTTP API   : port {}", httpPort);
        logger.info("  Try: telnet localhost {}", tcpPort);
        logger.info("  Try: curl http://localhost:{}/stats", httpPort);
    }
}
