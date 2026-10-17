package com.nimrodtechs.samples.grpcserver.benchmark;

import com.nimrodtechs.samples.benchmark.common.*;
import com.nimrodtechs.samples.grpc.benchmark.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.util.*;
import java.util.concurrent.*;

@Configuration
@Profile("benchmark")
public class GrpcBenchmarkServer {
    @Bean BenchmarkApplications.Server benchmarkServerState(Environment env) throws Exception {
        return BenchmarkApplications.server(env,Map.of("library","grpc","protocol","grpc-http2-tcp","serializer","protobuf","serverWorkers",8));
    }
    @Bean(destroyMethod="shutdownNow") ExecutorService benchmarkExecutor() {
        return new ThreadPoolExecutor(8,8,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),new ThreadPoolExecutor.AbortPolicy());
    }
}
