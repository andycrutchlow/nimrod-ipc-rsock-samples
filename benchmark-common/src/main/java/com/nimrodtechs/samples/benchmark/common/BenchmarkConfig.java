package com.nimrodtechs.samples.benchmark.common;

import org.springframework.core.env.Environment;
import java.nio.file.Path;
import java.util.Set;

public record BenchmarkConfig(String runId, int payloadBytes, int concurrency, double warmupSeconds,
        double measurementSeconds, long timeoutMillis, long drainMillis, long seed, Path output) {
    public BenchmarkConfig {
        if(!Set.of(128,1024,10240,102400).contains(payloadBytes)) throw new IllegalArgumentException("Unsupported payload bytes");
        if(!Set.of(1,8,32).contains(concurrency)) throw new IllegalArgumentException("Concurrency must be 1, 8 or 32");
        if(!Double.isFinite(warmupSeconds) || warmupSeconds<0 || warmupSeconds>3600
                || !Double.isFinite(measurementSeconds) || measurementSeconds<=0 || measurementSeconds>3600)
            throw new IllegalArgumentException("Invalid phase duration");
        if(timeoutMillis<10 || timeoutMillis>60000 || drainMillis<0 || drainMillis>60000)
            throw new IllegalArgumentException("Invalid timeout/drain");
    }
    public static BenchmarkConfig from(Environment env) {
        return new BenchmarkConfig(env.getRequiredProperty("benchmark.run-id"),
            env.getProperty("benchmark.payload-bytes",Integer.class,128),env.getProperty("benchmark.concurrency",Integer.class,1),
            env.getProperty("benchmark.warmup-seconds",Double.class,30.0),env.getProperty("benchmark.measurement-seconds",Double.class,120.0),
            env.getProperty("benchmark.timeout-millis",Long.class,5000L),env.getProperty("benchmark.drain-millis",Long.class,30000L),
            env.getProperty("benchmark.seed",Long.class,42L),Path.of(env.getRequiredProperty("benchmark.output")));
    }
}
