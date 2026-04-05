package com.kache.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LineBasedFrameDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes raw TCP bytes into {@link Command} objects.
 *
 * Extends {@link LineBasedFrameDecoder} to handle line-based framing (splitting on \n or \r\n),
 * then parses each line into a Command record by splitting on whitespace.
 *
 * Pipeline position: first handler in the Netty pipeline.
 * Inbound flow: raw bytes → LineBasedFrameDecoder → CommandDecoder → Command object
 *
 * Max line length is 1024 bytes — more than enough for cache commands.
 * Malformed or empty lines are silently dropped (returns null).
 */
public class CommandDecoder extends LineBasedFrameDecoder {

    private static final Logger logger = LoggerFactory.getLogger(CommandDecoder.class);

    /** Maximum allowed line length in bytes. Commands exceeding this are rejected. */
    private static final int MAX_LINE_LENGTH = 1024;

    public CommandDecoder() {
        super(MAX_LINE_LENGTH);
    }

    /**
     * Decodes a single line from the TCP stream into a Command object.
     *
     * @param ctx    channel context
     * @param buffer incoming byte buffer
     * @return a Command object, or null if the line is empty/incomplete
     */
    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        // Let the parent LineBasedFrameDecoder extract a complete line
        ByteBuf frame = (ByteBuf) super.decode(ctx, buffer);
        if (frame == null) {
            return null; // Incomplete line — wait for more data
        }

        try {
            String line = frame.toString(StandardCharsets.UTF_8).trim();
            if (line.isEmpty()) {
                return null; // Skip empty lines (e.g., from telnet double-enter)
            }

            // Split on whitespace: first token is command name, rest are args
            String[] parts = line.split("\\s+");
            String commandName = parts[0].toUpperCase();

            List<String> args = new ArrayList<>(parts.length - 1);
            for (int i = 1; i < parts.length; i++) {
                args.add(parts[i]);
            }

            Command cmd = new Command(commandName, args);
            logger.debug("Decoded command: {}", cmd);
            return cmd;

        } finally {
            // Release the ByteBuf to prevent memory leaks (Netty uses reference counting)
            frame.release();
        }
    }

    /**
     * Handles decoding errors — malformed frames, lines exceeding max length, etc.
     * Sends an error response to the client instead of closing the connection.
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.warn("Error decoding command from {}: {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.writeAndFlush("ERROR malformed command: " + cause.getMessage() + "\n");
    }
}
