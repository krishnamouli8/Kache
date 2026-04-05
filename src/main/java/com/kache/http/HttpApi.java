package com.kache.http;

import com.kache.store.KacheStore;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight HTTP API for health checks and cache observability.
 *
 * Endpoints:
 *   GET /health        → "OK" (for load balancers and container orchestrators)
 *   GET /stats         → JSON object with cache metrics
 *   GET /deps/{key}    → JSON showing direct dependents of a key
 *
 * Built on Javalin (embedded Jetty) — no Spring, no annotations, no DI.
 * The entire HTTP layer is intentionally under 60 lines.
 */
public class HttpApi {

    private static final Logger logger = LoggerFactory.getLogger(HttpApi.class);

    /** The Javalin app instance, stored for clean shutdown. */
    private static Javalin app;

    /**
     * Starts the HTTP API server on the given port.
     *
     * @param store the shared KacheStore instance (same one used by the TCP server)
     * @param port  the HTTP port to listen on
     */
    public static void start(KacheStore store, int port) {
        app = Javalin.create(config -> {
            // Disable Javalin's default request logging to avoid noise — we have SLF4J
            config.showJavalinBanner = false;
        });

        // Health check — returns 200 "OK" for liveness probes
        app.get("/health", ctx -> ctx.result("OK"));

        // Cache statistics — returns a JSON snapshot of all metrics
        app.get("/stats", ctx -> {
            var s = store.getStats();
            // Using LinkedHashMap to preserve key insertion order in JSON output
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("keys", s.keyCount());
            stats.put("hits", s.hits());
            stats.put("misses", s.misses());
            stats.put("hitRate", s.hitRate());
            stats.put("cascadeInvalidations", s.cascadeInvalidations());
            stats.put("depEdges", s.depEdgeCount());
            ctx.json(stats);
        });

        // Dependency lookup — shows which keys depend on the given key
        app.get("/deps/{key}", ctx -> {
            String key = ctx.pathParam("key");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", key);
            result.put("dependents", store.getDependents(key));
            ctx.json(result);
        });

        app.start(port);
        logger.info("Kache HTTP API listening on port {}", port);
    }

    /**
     * Stops the HTTP server gracefully. Called from shutdown hooks.
     */
    public static void stop() {
        if (app != null) {
            app.stop();
            logger.info("Kache HTTP API stopped");
        }
    }
}
