package com.nimrodtechs.samples.grpcserver;

import com.nimrodtechs.samples.grpc.PriceRequest;
import com.nimrodtechs.samples.grpc.PriceResponse;
import com.nimrodtechs.samples.grpc.PricingServiceGrpc;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class PricingGrpcService extends PricingServiceGrpc.PricingServiceImplBase {

    @Override
    public void getPrice(
            PriceRequest request,
            StreamObserver<PriceResponse> responseObserver) {

        long received = System.nanoTime();

        PriceResponse response = PriceResponse.newBuilder()
                .setCcyPair(request.getCcyPair())
                .setTenor(request.getTenor())
                .setBid(1.2345)
                .setAsk(1.2350)
                .setTimeSent(request.getTimeSent())
                .setTimeReceived(received)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}