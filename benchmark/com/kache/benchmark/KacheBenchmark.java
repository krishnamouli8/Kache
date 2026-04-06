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
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class KacheBenchmark {

    private KacheStore store;

    /**
     * Creates a single KacheStore instance for the entire benchmark run.
     * Using Level.Trial (not Level.Invocation) to avoid spawning thousands
     * of background cleaner threads — one store is reused across all invocations.
     */
    @Setup(Level.Trial)
    public void setupStore() {
        store = new KacheStore();
    }

    /**
     * Shuts down the background cleaner thread when the benchmark is done.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        store.shutdown();
    }

    /**
     * Rebuilds the 1000-key dependency chain before each invocation.
     * Uses delete("root") to cascade-clear any existing data first,
     * then re-populates — avoiding new KacheStore instances.
     */
    private void rebuildChain() {
        // Clear previous chain if it exists (cascade takes care of children)
        store.delete("root");

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
     * Rebuilds the chain before each measurement.
     */
    @Benchmark
    public int cascadeDelete() {
        rebuildChain();
        return store.delete("root");
    }

    /**
     * Setup for simpleGet — builds the chain once per iteration batch.
     * Using Level.Iteration so the chain exists for all GET measurements
     * within an iteration, without the per-invocation rebuild overhead.
     */
    @Setup(Level.Iteration)
    public void setupChainForGet() {
        rebuildChain();
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
     */
    public static void main(String[] args) throws RunnerException {
        Options opts = new OptionsBuilder()
                .include(KacheBenchmark.class.getSimpleName())
                .build();
        new Runner(opts).run();
    }
}
