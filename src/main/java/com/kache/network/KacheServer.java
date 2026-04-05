package com.kache.network;

import com.kache.store.KacheStore;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.string.StringEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Netty-based TCP server for the Kache protocol.
 *
 * Pipeline layout (per connection):
 *   Inbound:  bytes → CommandDecoder (LineBasedFrameDecoder + parser) → CommandHandler
 *   Outbound: String → StringEncoder → bytes
 *
 * Boss group accepts connections (1 thread is enough).
 * Worker group handles I/O for established connections (defaults to 2 × CPU cores).
 *
 * The server does NOT block the calling thread after start() — Main.java manages lifecycle.
 */
public class KacheServer {

    private static final Logger logger = LoggerFactory.getLogger(KacheServer.class);

    private final int port;
    private final KacheStore store;

    /** Boss group: accepts incoming connections */
    private EventLoopGroup bossGroup;

    /** Worker group: handles I/O on established connections */
    private EventLoopGroup workerGroup;

    /** The bound server channel, used for clean shutdown */
    private Channel serverChannel;

    public KacheServer(int port, KacheStore store) {
        this.port = port;
        this.store = store;
    }

    /**
     * Starts the TCP server and binds to the configured port.
     * Returns after the port is bound — does not block.
     *
     * @throws InterruptedException if binding is interrupted
     */
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                // Inbound: decode lines → parse into Command objects
                                .addLast("decoder", new CommandDecoder())
                                // Outbound: encode String responses to bytes
                                .addLast("encoder", new StringEncoder(StandardCharsets.UTF_8))
                                // Business logic: execute commands against the store
                                .addLast("handler", new CommandHandler(store));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        // Bind and wait for completion
        ChannelFuture future = bootstrap.bind(port).sync();
        serverChannel = future.channel();

        logger.info("Kache TCP server listening on port {}", port);
    }

    /**
     * Gracefully shuts down the server: closes the listening socket,
     * then shuts down both event loop groups.
     */
    public void stop() {
        logger.info("Shutting down Kache TCP server...");
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while closing server channel", e);
            Thread.currentThread().interrupt();
        } finally {
            if (workerGroup != null) workerGroup.shutdownGracefully();
            if (bossGroup != null) bossGroup.shutdownGracefully();
        }
        logger.info("Kache TCP server stopped");
    }
}
