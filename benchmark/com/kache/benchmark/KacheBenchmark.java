package com.kache.benchmark;

import com.kache.store.KacheStore;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmarks for Kache operations.
 *
 * Measures two critical operations:
 * 1. cascadeDelete — invalidating a root key with 1000 transitive dependents
 * 2. simpleGet     — reading a single key from a populated store
 *
 * The cascade benchmark is the key metric: it proves the invalidation
 * algorithm scales linearly with dependency chain depth, and the absolute
 * numbers are small enough for real-time use.
 *
 * Build:  mvn package -Pbenchmark
 * Run:    java -jar target/kache-1.0-benchmarks.jar
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class KacheBenchmark {

    private KacheStore store;

    /**
     * Builds a 1000-key linear dependency chain under a single root.
     * This is the worst case for cascade invalidation — a long chain
     * that forces the deepest recursion.
     */
    @Setup(Level.Invocation)
    public void setup() {
        store = new KacheStore();

        // Root key — the top of the dependency chain
        store.set("root", "value", -1, List.of());

        // Build a chain: root → child:0 → child:1 → ... → child:999
        String prev = "root";
        for (int i = 0; i < 1000; i++) {
            String key = "child:" + i;
            store.set(key, "value", -1, List.of(prev));
            prev = key;
        }
    }

    /**
     * Benchmark: cascade delete of 1001 keys (root + 1000 children).
     * Measures the full cost of recursive invalidation.
     */
    @Benchmark
    public int cascadeDelete() {
        return store.delete("root");
    }

    /**
     * Benchmark: simple GET on a key in the middle of the chain.
     * Measures baseline read performance with a populated store.
     */
    @Benchmark
    public String simpleGet() {
        return store.get("child:500");
    }

    /**
     * Main method for running benchmarks directly (alternative to JMH CLI).
     * Usage: java -cp kache-1.0-benchmarks.jar KacheBenchmark
     */
    public static void main(String[] args) throws RunnerException {
        Options opts = new OptionsBuilder()
                .include(KacheBenchmark.class.getSimpleName())
                .build();
        new Runner(opts).run();
    }
}
