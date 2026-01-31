package com.nimrodtechs.samples;

import com.nimrodtechs.samples.dto.PriceRequest;
import com.nimrodtechs.samples.dto.PriceResponse;
import com.nimrodtechs.samples.rmiservices.pricing.PricingServiceRmiInterface;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

}
