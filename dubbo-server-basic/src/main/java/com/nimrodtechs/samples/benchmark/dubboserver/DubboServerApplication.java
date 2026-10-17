package com.nimrodtechs.samples.benchmark.dubboserver;

import com.nimrodtechs.samples.benchmark.common.*;
import com.nimrodtechs.samples.benchmark.dubbo.*;
import org.apache.dubbo.config.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import java.util.*;

@SpringBootApplication
public class DubboServerApplication {
    public static void main(String[] args) { SpringApplication.run(DubboServerApplication.class,args); }
    @Bean AutoCloseable serializerObservation(Environment env) {
        return ()->Results.write(java.nio.file.Path.of(env.getRequiredProperty("benchmark.output")).resolve("transport-observation.json"),ObservedSerialization.snapshot());
    }
    @Bean ApplicationRunner keepProviderAlive() { return args->new java.util.concurrent.CountDownLatch(1).await(); }
    @Bean BenchmarkApplications.Server benchmarkServerState(Environment env) throws Exception {
        return BenchmarkApplications.server(env,DubboSettings.identity(env));
    }
    @Bean(destroyMethod="unexport") ServiceConfig<DubboBenchmark> benchmarkProvider(Environment env,BenchmarkApplications.Server server) {
        ProtocolConfig protocol=new ProtocolConfig("dubbo",env.getProperty("benchmark.port",Integer.class,20880));
        protocol.setHost(env.getProperty("benchmark.host","127.0.0.1"));protocol.setSerialization("kryo");protocol.setThreads(8);protocol.setQueues(32);protocol.setThreadpool("fixed");
        protocol.setParameters(Map.of("heartbeat","60000","heartbeat.timeout","180000","payload","1048576","tcp.nodelay","true","keepalive","true"));
        ServiceConfig<DubboBenchmark> service=new ServiceConfig<>();service.setApplication(DubboSettings.application("server"));service.setRegistry(DubboSettings.registry());
        service.setInterface(DubboBenchmark.class);service.setRef(server::invoke);service.setProtocol(protocol);service.setScope("remote");service.setRetries(0);
        service.setTimeout(env.getProperty("benchmark.timeout-millis",Integer.class,5000));service.export();return service;
    }
}
