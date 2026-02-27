package com.nimrodtechs.samples.grpcserver;

import com.nimrodtechs.samples.grpc.GrpcPriceRequest;
import com.nimrodtechs.samples.grpc.GrpcPriceResponse;
import com.nimrodtechs.samples.grpc.PricingServiceGrpc;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class PricingGrpcService extends PricingServiceGrpc.PricingServiceImplBase {

    @Override
    public void getPrice(
            GrpcPriceRequest request,
            StreamObserver<GrpcPriceResponse> responseObserver) {

        long received = System.nanoTime();
        GrpcPriceResponse response = GrpcPriceResponse.newBuilder()
                .setCcyPair(request.getCcyPair())
                .setTenor(request.getTenor())
                .setPrice("1.2345")
                .setTimeSent(request.getTimeSent())
                .setTimeResponded((request.getTimeSent() != 0 ? System.nanoTime() : 0))
                .build();
        //System.out.println(Thread.currentThread().getName());
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}