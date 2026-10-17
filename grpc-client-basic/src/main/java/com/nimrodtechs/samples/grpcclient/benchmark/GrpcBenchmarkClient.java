package com.nimrodtechs.samples.grpcclient.benchmark;

import com.nimrodtechs.samples.benchmark.common.*;
import com.nimrodtechs.samples.grpc.benchmark.*;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.boot.ApplicationRunner;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Configuration
@Profile("benchmark")
public class GrpcBenchmarkClient {
    @GrpcClient("pricing") private BenchmarkServiceGrpc.BenchmarkServiceBlockingStub stub;
    @Bean ApplicationRunner benchmarkClient(Environment env) {
        long timeout=env.getProperty("benchmark.timeout-millis",Long.class,5000L);
        return BenchmarkApplications.client(env,request->BenchmarkMapping.fromWire(stub.withDeadlineAfter(timeout,TimeUnit.MILLISECONDS).echo(BenchmarkMapping.toWire(request))),
            Map.of("library","grpc","protocol","grpc-http2-tcp","serializer","protobuf","nativeClientDeadline",true,
                "target",env.getProperty("grpc.client.pricing.address","static://127.0.0.1:9090"),"channelSharing","one channel, multiplexed RPCs"));
    }
}
