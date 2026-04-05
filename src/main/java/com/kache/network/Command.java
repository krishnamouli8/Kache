package com.kache.network;

import java.util.List;

/**
 * Represents a parsed client command received over the TCP protocol.
 *
 * The protocol is line-based and space-delimited:
 *   COMMAND_NAME arg1 arg2 ... argN
 *
 * The name is always uppercased during parsing (case-insensitive input).
 * Args retain their original casing.
 *
 * @param name the command name (e.g., SET, GET, DEL, PING, STATS, DEPS)
 * @param args the command arguments (may be empty)
 */
public record Command(String name, List<String> args) {

    @Override
    public String toString() {
        return "Command{" + name + " " + String.join(" ", args) + "}";
    }
}
