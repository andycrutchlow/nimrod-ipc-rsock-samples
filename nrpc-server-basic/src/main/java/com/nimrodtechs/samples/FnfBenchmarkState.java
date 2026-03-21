package com.nimrodtechs.samples;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

@Component
public class FnfBenchmarkState {

    public final LongAdder processed = new LongAdder();
    public final LongAdder errors = new LongAdder();

    // optional latency capture
    public final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

}