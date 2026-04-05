package com.kache.network;

import com.kache.store.KacheStore;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handles parsed {@link Command} objects by dispatching to the {@link KacheStore}.
 *
 * Supported commands:
 *   SET key value [TTL seconds] [DEPENDS parent1,parent2,...]
 *   GET key
 *   DEL key
 *   DEPS key
 *   STATS
 *   PING
 *
 * Response format (one line per response):
 *   OK                              — successful SET
 *   VALUE <data>                    — successful GET
 *   NULL                            — GET on missing/expired key
 *   COUNT <n>                       — DEL result (includes cascade count)
 *   DEPS <k1,k2,...>               — dependency list
 *   STATS keyCount=N hits=N ...    — store statistics
 *   PONG                            — health check
 *   ERROR <message>                 — any error
 */
public class CommandHandler extends SimpleChannelInboundHandler<Command> {

    private static final Logger logger = LoggerFactory.getLogger(CommandHandler.class);

    private final KacheStore store;

    public CommandHandler(KacheStore store) {
        this.store = store;
    }

    /**
     * Routes each command to its handler method and writes the response back.
     * Unknown commands get an ERROR response (connection stays open).
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Command cmd) {
        String response = switch (cmd.name()) {
            case "PING"  -> handlePing();
            case "SET"   -> handleSet(cmd);
            case "GET"   -> handleGet(cmd);
            case "DEL"   -> handleDel(cmd);
            case "DEPS"  -> handleDeps(cmd);
            case "STATS" -> handleStats();
            default      -> "ERROR unknown command: " + cmd.name() + "\n";
        };

        ctx.writeAndFlush(response);
    }

    /** PING → PONG. Simple health/connectivity check. */
    private String handlePing() {
        return "PONG\n";
    }

    /**
     * SET key value [TTL seconds] [DEPENDS parent1,parent2,...]
     *
     * Parses optional TTL and DEPENDS modifiers from the argument list.
     * TTL is in seconds. DEPENDS is a comma-separated list of parent keys.
     */
    private String handleSet(Command cmd) {
        List<String> args = cmd.args();
        if (args.size() < 2) {
            return "ERROR SET requires at least: SET <key> <value>\n";
        }

        String key = args.get(0);
        String value = args.get(1);
        long ttl = -1;
        List<String> dependsOn = new ArrayList<>();

        // Parse optional modifiers: TTL <seconds> and DEPENDS <parent1,parent2>
        try {
            for (int i = 2; i < args.size(); i++) {
                switch (args.get(i).toUpperCase()) {
                    case "TTL" -> {
                        if (i + 1 >= args.size()) {
                            return "ERROR TTL requires a value in seconds\n";
                        }
                        ttl = Long.parseLong(args.get(++i));
                        if (ttl <= 0) {
                            return "ERROR TTL must be a positive number\n";
                        }
                    }
                    case "DEPENDS" -> {
                        if (i + 1 >= args.size()) {
                            return "ERROR DEPENDS requires comma-separated parent keys\n";
                        }
                        String[] parents = args.get(++i).split(",");
                        dependsOn.addAll(Arrays.asList(parents));
                    }
                    default -> {
                        return "ERROR unknown modifier: " + args.get(i) + "\n";
                    }
                }
            }
        } catch (NumberFormatException e) {
            return "ERROR TTL must be a valid number\n";
        }

        store.set(key, value, ttl, dependsOn);
        return "OK\n";
    }

    /**
     * GET key → VALUE <data> or NULL
     */
    private String handleGet(Command cmd) {
        if (cmd.args().isEmpty()) {
            return "ERROR GET requires a key\n";
        }

        String value = store.get(cmd.args().get(0));
        return value != null ? "VALUE " + value + "\n" : "NULL\n";
    }

    /**
     * DEL key → COUNT <n>
     * The count includes all cascaded child invalidations.
     */
    private String handleDel(Command cmd) {
        if (cmd.args().isEmpty()) {
            return "ERROR DEL requires a key\n";
        }

        int removed = store.delete(cmd.args().get(0));
        return "COUNT " + removed + "\n";
    }

    /**
     * DEPS key → DEPS <child1,child2,...> or DEPS (none)
     * Shows which keys directly depend on the given key.
     */
    private String handleDeps(Command cmd) {
        if (cmd.args().isEmpty()) {
            return "ERROR DEPS requires a key\n";
        }

        Set<String> dependents = store.getDependents(cmd.args().get(0));
        if (dependents.isEmpty()) {
            return "DEPS (none)\n";
        }
        return "DEPS " + dependents.stream().sorted().collect(Collectors.joining(",")) + "\n";
    }

    /**
     * STATS → single-line stats dump with all metrics.
     */
    private String handleStats() {
        var s = store.getStats();
        return String.format("STATS keyCount=%d hits=%d misses=%d hitRate=%.2f cascades=%d depEdges=%d\n",
                s.keyCount(), s.hits(), s.misses(), s.hitRate(), s.cascadeInvalidations(), s.depEdgeCount());
    }

    /**
     * Logs and handles unexpected exceptions during command processing.
     * Sends an error response instead of dropping the connection.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Unexpected error processing command from {}", ctx.channel().remoteAddress(), cause);
        ctx.writeAndFlush("ERROR internal server error\n");
    }

    /** Logs new client connections for debugging. */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Client connected: {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    /** Logs client disconnections for debugging. */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Client disconnected: {}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }
}
