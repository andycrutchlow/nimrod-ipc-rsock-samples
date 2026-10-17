package com.nimrodtechs.samples.benchmark.common;

import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

public final class BenchmarkApplications {
    private BenchmarkApplications() { }
    public static ApplicationRunner client(Environment env,BenchmarkRunner.Rpc rpc,Map<String,Object> identity) {
        return client(env,rpc,identity,java.util.Map::of);
    }
    public static ApplicationRunner client(Environment env,BenchmarkRunner.Rpc rpc,Map<String,Object> identity,java.util.function.Supplier<Map<String,Object>> observation) {
        return args->{
            int exit=2;
            try {
                var result=new BenchmarkRunner(BenchmarkConfig.from(env),rpc,identity).run();
                Results.write(Path.of(env.getRequiredProperty("benchmark.output")).resolve("transport-observation.json"),observation.get());
                exit=Boolean.TRUE.equals(result.get("valid"))?0:2;
            } catch(Throwable e) {
                e.printStackTrace();
                try { Results.write(Path.of(env.getRequiredProperty("benchmark.output")).resolve("fatal.json"),Map.of("valid",false,"error",e.toString())); }
                catch(Exception ignored) { }
            }
            // Finite benchmark clients must not remain alive in transport/background threads.
            System.exit(exit);
        };
    }
    public static Server server(Environment env,Map<String,Object> identity) throws Exception { return new Server(env,identity); }
    public static final class Server implements AutoCloseable {
        private final Path output;
        private final ResourceSampler sampler;
        private final long delayMillis;
        private final LongAdder processed=new LongAdder(),errors=new LongAdder();
        public Server(Environment env,Map<String,Object> identity) throws Exception {
            output=Path.of(env.getRequiredProperty("benchmark.output"));
            delayMillis=env.getProperty("benchmark.server-delay-millis",Long.class,0L);
            if(delayMillis<0 || delayMillis>60000)throw new IllegalArgumentException("Invalid fault-injection delay");
            Map<String,Object> manifest=new LinkedHashMap<>(Results.provenance());manifest.put("implementation",identity);
            manifest.put("faultInjectionDelayMillis",delayMillis);Results.write(output.resolve("manifest.json"),manifest);
            sampler=new ResourceSampler(output);sampler.phase("server");
        }
        public BenchmarkMessage invoke(BenchmarkMessage request) throws Exception {
            try {
                if(delayMillis>0)Thread.sleep(delayMillis);
                BenchmarkMessage response=Workload.process(request);processed.increment();return response;
            } catch(Exception e) { errors.increment();throw e; }
        }
        @Override public void close() throws Exception {
            sampler.close();Results.write(output.resolve("server-summary.json"),Map.of("processed",processed.sum(),"errors",errors.sum(),"resourceSamplingError",sampler.failed()));
        }
    }
}
