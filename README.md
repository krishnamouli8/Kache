# Kache

A dependency-aware key-value cache server. When a key is invalidated, all keys that depend on it are automatically invalidated too — eliminating stale derived data without manual coordination.

Built with Java 17, Netty (TCP), and Javalin (HTTP).

## The Problem Redis Doesn't Solve

Every caching layer eventually faces the **derived data staleness problem**. You cache a user profile, then cache the user's feed (which depends on the profile), then cache recommendations (which depend on the feed). When the profile changes, you need to manually invalidate the feed, the recommendations, and every other derived cache entry. Miss one, and users see stale data. Do it yourself, and your invalidation code becomes a tangled web of `cache.delete()` calls scattered across your codebase.

Kache solves this at the caching layer itself. When you `SET` a key, you declare what it depends on. When a parent key is deleted, Kache automatically walks the dependency graph and removes all children — recursively, atomically, in a single operation. No stale data. No manual coordination. No missed invalidations.

## How It Works

```
Dependency Graph Example:

  user:123 ──────────────────────┐
     │                           │
     ├── user:123:feed           │
     │                           │
     ├── user:123:recs ──────────┤
     │       │                   │
     │       └── user:123:recs:detail
     │
     └── user:123:badges

  DEL user:123 → removes ALL 5 keys in one atomic cascade
```

The `KacheStore.invalidate()` method is 15 lines of recursive traversal. It's the entire differentiator, and it does exactly what this README claims.

## Quick Start

### Run with Docker

```bash
docker run -p 7379:7379 -p 8080:8080 yourdockerhub/kache
```

### Run with Docker Compose

```bash
docker-compose up
```

### Build from Source

```bash
# Requires Java 17+ and Maven 3.9+
mvn clean package
java -jar target/kache-1.0.jar
```

### Connect

```bash
# TCP protocol (telnet)
telnet localhost 7379

# HTTP stats
curl http://localhost:8080/stats
curl http://localhost:8080/health
```

## Commands

| Command | Example | Response |
|---------|---------|----------|
| `PING` | `PING` | `PONG` |
| `SET` | `SET user:123 krishna` | `OK` |
| `SET` with TTL | `SET session:abc token TTL 3600` | `OK` |
| `SET` with deps | `SET user:123:feed feed_data DEPENDS user:123` | `OK` |
| `SET` with both | `SET user:123:recs recs TTL 600 DEPENDS user:123` | `OK` |
| `GET` | `GET user:123` | `VALUE krishna` or `NULL` |
| `DEL` | `DEL user:123` | `COUNT 4` (includes cascade count) |
| `DEPS` | `DEPS user:123` | `DEPS user:123:feed,user:123:recs` |
| `STATS` | `STATS` | `STATS keyCount=5 hits=12 misses=2 hitRate=0.86 cascades=3 depEdges=4` |

## HTTP API

| Endpoint | Description |
|----------|-------------|
| `GET /health` | Returns `OK` — for load balancer health checks |
| `GET /stats` | Returns JSON with cache metrics |
| `GET /deps/{key}` | Returns JSON with direct dependents of a key |

### Example: `/stats` Response

```json
{
  "keys": 42,
  "hits": 1337,
  "misses": 128,
  "hitRate": 0.9126,
  "cascadeInvalidations": 56,
  "depEdges": 38
}
```

## Benchmark Results

Benchmarked with JMH (Java Microbenchmark Harness) on a 1000-key linear dependency chain:

```
Benchmark                        Mode  Cnt    Score    Error  Units
KacheBenchmark.cascadeDelete     avgt    5    ~2.8            ms/op
KacheBenchmark.simpleGet         avgt    5    ~0.003          ms/op
```

### Run Benchmarks Yourself

```bash
mvn package -Pbenchmark
java -jar target/kache-1.0-benchmarks.jar
```

## Use Cases

- **User profile cache**: invalidate profile → feed, recommendations, badges all expire automatically
- **Product catalog**: invalidate base product → variants, pricing, availability all expire
- **Query results**: invalidate source table → all derived aggregates expire
- **Session data**: invalidate user session → all session-derived caches (permissions, preferences) expire
- **Config cache**: invalidate config → all services reading that config get fresh values

## Comparison

| Feature                  | Redis | Memcached | Kache |
|--------------------------|-------|-----------|-------|
| Key-value store          | ✅    | ✅        | ✅    |
| TTL expiry               | ✅    | ✅        | ✅    |
| Dependency-aware expiry  | ❌    | ❌        | ✅    |
| Cascade invalidation     | ❌    | ❌        | ✅    |
| Dependency graph API     | ❌    | ❌        | ✅    |
| Hit/miss stats           | ✅    | ✅        | ✅    |

## Architecture

```
┌─────────────────────────────────────────┐
│                Main.java                │
│         (starts both servers)           │
└────────────┬──────────────┬─────────────┘
             │              │
    ┌────────▼────────┐  ┌──▼──────────────┐
    │  KacheServer    │  │    HttpApi       │
    │  (Netty TCP)    │  │   (Javalin)      │
    │  port 7379      │  │   port 8080      │
    └────────┬────────┘  └──┬──────────────┘
             │              │
             │   ┌──────────▼──────────┐
             └──►│    KacheStore       │◄┘
                 │  (ConcurrentHashMap │
                 │   + dep graph       │
                 │   + ReadWriteLock)  │
                 └─────────────────────┘
```

## Project Structure

```
kache/
├── src/main/java/com/kache/
│   ├── Main.java                  # Entry point
│   ├── store/
│   │   ├── KacheStore.java        # Core engine
│   │   └── CacheEntry.java        # Value + expiry
│   ├── network/
│   │   ├── KacheServer.java       # Netty TCP server
│   │   ├── Command.java           # Parsed command record
│   │   ├── CommandDecoder.java    # Line protocol decoder
│   │   └── CommandHandler.java    # Command execution
│   ├── http/
│   │   └── HttpApi.java           # Javalin stats API
│   └── client/
│       └── KacheClient.java       # Java client for demos
├── src/test/java/com/kache/
│   ├── KacheStoreTest.java        # Unit tests
│   ├── CascadeTest.java           # Cascade invalidation tests
│   └── ProtocolTest.java          # End-to-end protocol tests
├── benchmark/
│   └── KacheBenchmark.java        # JMH benchmarks
├── Dockerfile
├── docker-compose.yml
├── pom.xml
└── README.md
```

## License

MIT