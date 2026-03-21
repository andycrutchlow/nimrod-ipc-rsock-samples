package com.nimrodtechs.samples;

import com.nimrodtechs.samples.dto.PriceRequest;
import com.nimrodtechs.samples.dto.PriceResponse;
import com.nimrodtechs.samples.dto.PriceUpdateEvent;
import com.nimrodtechs.samples.rmiservices.pricing.PricingServiceRmiInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PricingService implements PricingServiceRmiInterface {
    private static final Logger logger = LoggerFactory.getLogger(PricingService.class);
    @Value("${benchmark.serverDelayMs:0}")
    private long delayMs;

    private final FnfBenchmarkState state;

    public PricingService(FnfBenchmarkState state) {
        this.state = state;
        logger.info("PricingService Created. Ready to serve.");
    }
    @Override
    public PriceResponse getPrice(PriceRequest request) throws Exception {
        return new PriceResponse(request.ccyPair(),request.tenor(),"1.1234",request.timeSent(),(request.timeSent() != 0 ? System.nanoTime() : 0));
    }

    @Override
    public void publishPriceUpdate(PriceUpdateEvent event) throws Exception {
        long start = System.nanoTime();

        // Simulate I/O delay if configured
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }

        // Count processed
        state.processed.increment();

        // Optional latency tracking (based on event timeSent)
        if (event.timeSentNs() > 0) {
            long latency = System.nanoTime() - event.timeSentNs();
            state.latencies.add(latency);
        }
    }
}
