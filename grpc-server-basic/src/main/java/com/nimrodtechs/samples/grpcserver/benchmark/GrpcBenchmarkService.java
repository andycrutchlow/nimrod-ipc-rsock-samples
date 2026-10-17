package com.nimrodtechs.samples.grpcserver.benchmark;

import com.nimrodtechs.samples.benchmark.common.*;
import com.nimrodtechs.samples.grpc.benchmark.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.context.annotation.Profile;
import java.util.concurrent.ExecutorService;

@GrpcService
@Profile("benchmark")
public class GrpcBenchmarkService extends BenchmarkServiceGrpc.BenchmarkServiceImplBase {
    private final BenchmarkApplications.Server server;
    private final ExecutorService executor;
    public GrpcBenchmarkService(BenchmarkApplications.Server server,ExecutorService benchmarkExecutor) { this.server=server;executor=benchmarkExecutor; }
    @Override public void echo(RpcMessage request,StreamObserver<RpcMessage> observer) {
        try {
            executor.execute(()->{
                try { observer.onNext(BenchmarkMapping.toWire(server.invoke(BenchmarkMapping.fromWire(request))));observer.onCompleted(); }
                catch(Exception e) { observer.onError(Status.INTERNAL.withDescription(e.toString()).asRuntimeException()); }
            });
        } catch(java.util.concurrent.RejectedExecutionException e) { observer.onError(Status.RESOURCE_EXHAUSTED.asRuntimeException()); }
    }
}
