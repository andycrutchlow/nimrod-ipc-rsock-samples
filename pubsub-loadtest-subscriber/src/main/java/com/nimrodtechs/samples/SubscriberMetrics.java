package com.nimrodtechs.samples;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class SubscriberMetrics {

    private final LongAdder totalMessages = new LongAdder();
    private final LongAdder intervalMessages = new LongAdder();
    private final LongAdder intervalLatencyNanos = new LongAdder();

    private final LongAdder totalLatencyNanos = new LongAdder();
    private final AtomicLong maxLatencyNanos = new AtomicLong();

    private final AtomicLong lastSequence = new AtomicLong(-1);
    private final LongAdder gapCount = new LongAdder();

    public void record(long sequence, long sendTimeNanos) {
        long now = System.nanoTime();
        long latency = now - sendTimeNanos;

        totalMessages.increment();
        intervalMessages.increment();
        totalLatencyNanos.add(latency);

        maxLatencyNanos.accumulateAndGet(latency, Math::max);
        intervalLatencyNanos.add(latency);

        // gap detection
        long prev = lastSequence.getAndSet(sequence);
        if (prev != -1 && sequence != prev + 1) {
            gapCount.increment();
        }
    }

    public Snapshot snapshotAndResetInterval() {
        long count = intervalMessages.sumThenReset();
        long total = totalMessages.sum();
        long totalLatency = totalLatencyNanos.sum();
        long maxLatency = maxLatencyNanos.get();
        long gaps = gapCount.sum();

        double avgLatencyMicros = count == 0
                ? 0
                : (intervalLatencyNanos.sumThenReset() / (double) count) / 1_000.0;

        return new Snapshot(count, total, avgLatencyMicros, maxLatency / 1_000, gaps);
    }

    public record Snapshot(
            long msgsPerSec,
            long total,
            double avgLatencyMicros,
            long maxLatencyMicros,
            long gapCount
    ) {}
}