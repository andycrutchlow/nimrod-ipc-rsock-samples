package com.nimrodtechs.samples;

import com.nimrodtechs.samples.dto.PriceRequest;
import com.nimrodtechs.samples.dto.PriceResponse;
import com.nimrodtechs.samples.dto.PriceUpdateEvent;
import com.nimrodtechs.samples.rmiservices.pricing.PricingServiceRmiInterface;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

@RestController
@RequestMapping("/")
class ClientBasicController {
    private final PricingServiceRmiInterface service;
    public ClientBasicController(PricingServiceRmiInterface service) {
        this.service = service;
    }

    @GetMapping("/price")
    public PriceResponse getPrice(
            @RequestParam String ccyPair,
            @RequestParam String tenor) throws Exception {

        PriceRequest request = new PriceRequest(ccyPair, tenor, 0);
        //Perform warmup to get a representative sample
        for(int i = 0; i < 5000; i++) {
            service.getPrice(request);
        }
        //Do the actual request/response call.
        PriceRequest requestActual = new PriceRequest(ccyPair, tenor, System.nanoTime());
        PriceResponse response = service.getPrice(requestActual);
        response.setTimeReceived(System.nanoTime());
        return response;
    }

    record ParallelPriceResponse(
            String ccyPair,
            String tenor,
            int parallelism,
            List<PriceResponse> responses
    ) {}

    @GetMapping("/parallel-price")
    public ParallelPriceResponse getPriceParallel(
            @RequestParam String ccyPair,
            @RequestParam String tenor,
            @RequestParam(defaultValue = "1") int threads
    ) throws Exception {

        // --- Warm-up (once, not per thread) ---
        PriceRequest warmup = new PriceRequest(ccyPair, tenor, 0);
        for (int i = 0; i < 5_000; i++) {
            service.getPrice(warmup);
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        List<Callable<PriceResponse>> tasks = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                long sent = System.nanoTime();
                PriceRequest req = new PriceRequest(ccyPair, tenor, sent);

                PriceResponse resp = service.getPrice(req);
                resp.setTimeReceived(System.nanoTime());

                return resp;
            });
        }

        List<Future<PriceResponse>> futures = executor.invokeAll(tasks);

        List<PriceResponse> results = new ArrayList<>(threads);
        for (Future<PriceResponse> f : futures) {
            results.add(f.get());
        }

        executor.shutdown();

        return new ParallelPriceResponse(
                ccyPair,
                tenor,
                threads,
                results
        );
    }
    record FnfBenchmarkResult(
            String transport,
            int messages,
            int threads,
            long success,
            long errors,
            long durationMs,
            double throughputPerSec,
            long p50Micros,
            long p95Micros,
            long p99Micros,
            String errorMessage
    ) {}

    @GetMapping("/burst")
    public FnfBenchmarkResult burst(
            @RequestParam int messages,
            @RequestParam int threads,
            @RequestParam(defaultValue = "128") int payloadBytes
    ) throws Exception {

        // Quick sanity
        if (messages <= 0 || threads <= 0) {
            return new FnfBenchmarkResult("nimrod", messages, threads, 0, 0, 0,
                    0, 0, 0, 0, "messages and threads must be > 0");
        }

        // Warm-up a single FNF to fail fast if server is down
        // (Do this BEFORE spinning up threads / starting timers)
        byte[] payload = new byte[payloadBytes];
        try {
            service.publishPriceUpdate(new PriceUpdateEvent("EUR/USD", 0, System.nanoTime(), payload));
        } catch (Exception e) {
            return new FnfBenchmarkResult("nimrod", messages, threads, 0, 0, 0,
                    0, 0, 0, 0, "Not connected: " + safeMessage(e));
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        LongAdder success = new LongAdder();
        LongAdder errors = new LongAdder();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        // Stop-all flag if we detect connection loss mid-run
        AtomicBoolean stop = new AtomicBoolean(false);
        // Capture first fatal error message (optional)
        ConcurrentLinkedQueue<String> fatal = new ConcurrentLinkedQueue<>();

        int perThread = messages / threads;
        int remainder = messages % threads;

        long startAll = System.nanoTime();

        try {
            List<Callable<Void>> tasks = new ArrayList<>(threads);

            for (int t = 0; t < threads; t++) {
                final int count = perThread + (t < remainder ? 1 : 0);
                final long baseSeq = (long) t * 1_000_000_000L; // avoid seq collisions across threads

                tasks.add(() -> {
                    for (int i = 0; i < count && !stop.get(); i++) {

                        long sent = System.nanoTime();
                        PriceUpdateEvent event = new PriceUpdateEvent(
                                "EUR/USD",
                                baseSeq + i,
                                sent,
                                payload
                        );

                        try {
                            service.publishPriceUpdate(event);

                            long ack = System.nanoTime();
                            latencies.add(ack - sent);
                            success.increment();

                        } catch (Exception e) {
                            errors.increment();

                            // Treat connection loss as fatal: stop all threads ASAP.
                            if (isFatalConnectionError(e)) {
                                stop.set(true);
                                fatal.add("Connection lost: " + safeMessage(e));
                                break;
                            }
                        }
                    }
                    return null;
                });
            }

            executor.invokeAll(tasks);

        } finally {
            executor.shutdown();
        }

        long endAll = System.nanoTime();
        long durationNs = endAll - startAll;

        // If we aborted early, report as “not connected” style result but with partial counts
        if (stop.get()) {
            return new FnfBenchmarkResult(
                    "nimrod",
                    messages,
                    threads,
                    success.sum(),
                    errors.sum(),
                    durationNs / 1_000_000,
                    0,
                    0, 0, 0,
                    fatal.peek() == null ? "Aborted due to connection loss" : fatal.peek()
            );
        }

        double throughput = (messages * 1_000_000_000.0) / durationNs;

        // Compute percentiles
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);

        long p50 = percentile(sorted, 50) / 1_000;
        long p95 = percentile(sorted, 95) / 1_000;
        long p99 = percentile(sorted, 99) / 1_000;

        return new FnfBenchmarkResult(
                "nimrod",
                messages,
                threads,
                success.sum(),
                errors.sum(),
                durationNs / 1_000_000,
                throughput,
                p50,
                p95,
                p99,
                null
        );
    }

    /**
     * Decide which exceptions should stop the entire benchmark early.
     * Keep this broad and NOT dependent on exact message strings.
     */
    private boolean isFatalConnectionError(Exception e) {
        // If your Nimrod client wraps a specific exception type (recommended),
        // check instanceof here. For now, do a conservative message scan.
        String msg = safeMessage(e).toLowerCase();
        return msg.contains("channel was closed")
                || msg.contains("connection")
                || msg.contains("disconnected")
                || msg.contains("refused")
                || msg.contains("unavailable");
    }

    private String safeMessage(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private long percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil((pct / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(index, 0));
    }
}
