package com.nimrodtechs.samples.grpcclient;

import com.nimrodtechs.samples.dto.PriceResponse;
import com.nimrodtechs.samples.grpc.GrpcPriceRequest;
import com.nimrodtechs.samples.grpc.GrpcPriceResponse;

import com.nimrodtechs.samples.grpc.PricingServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@RestController
@RequestMapping("/grpc")
class ClientGrpcController {

    @GrpcClient("pricing")
    private PricingServiceGrpc.PricingServiceBlockingStub stub;

    @GetMapping("/price")
    public PriceResponse getPrice(
            @RequestParam String ccyPair,
            @RequestParam String tenor) {

        GrpcPriceRequest request = GrpcPriceRequest.newBuilder()
                .setCcyPair(ccyPair)
                .setTenor(tenor)
                .setTimeSent(0)
                .build();
        //Perform warmup to get a representative sample
        for(int i = 0; i < 5000; i++) {
            stub.getPrice(request);
        }
        request = GrpcPriceRequest.newBuilder()
                .setCcyPair(ccyPair)
                .setTenor(tenor)
                .setTimeSent(System.nanoTime())
                .build();
        long sent = System.nanoTime();
        GrpcPriceResponse response = stub.getPrice(request);
        long received = System.nanoTime();
        System.out.println("Stub latency ns = " + (received - sent));
        PriceResponse pr = new PriceResponse();
        //String s, String tenor, BigDecimal bigDecimal, long timeSent, long timeResponded
        //Convert GrpcPriceResponse to a PriceResponse
        pr.setCcyPair(response.getCcyPair());
        pr.setTenor(response.getTenor());
        pr.setPrice(response.getPrice());
        //pr.setAsk(response.getAsk());
        pr.setTimeSent(response.getTimeSent());
        pr.setTimeResponded(response.getTimeResponded());
        pr.setTimeReceived(received);
        return pr;
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
        GrpcPriceRequest warmup = GrpcPriceRequest.newBuilder()
                .setCcyPair(ccyPair)
                .setTenor(tenor)
                .setTimeSent(0)
                .build();

        for (int i = 0; i < 5_000; i++) {
            stub.getPrice(warmup);
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        List<Callable<PriceResponse>> tasks = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {

                long sent = System.nanoTime();

                GrpcPriceRequest request = GrpcPriceRequest.newBuilder()
                        .setCcyPair(ccyPair)
                        .setTenor(tenor)
                        .setTimeSent(sent)
                        .build();

                GrpcPriceResponse response = stub.getPrice(request);

                long received = System.nanoTime();

                PriceResponse pr = new PriceResponse();
                pr.setCcyPair(response.getCcyPair());
                pr.setTenor(response.getTenor());
                pr.setPrice(response.getPrice());
                pr.setTimeSent(response.getTimeSent());
                pr.setTimeResponded(response.getTimeResponded());
                pr.setTimeReceived(received);

                return pr;
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