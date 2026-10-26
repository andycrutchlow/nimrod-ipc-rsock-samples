package com.nimrodtechs.samples.benchmark.nimrod;

import com.nimrodtechs.samples.benchmark.common.*;
import com.nimrodtechs.benchmarks.nimrod.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.util.Map;

@Configuration
@Profile("benchmark")
@Import(BenchmarkRmi__NimrodRmiController.class)
public class NimrodBenchmarkServer {
    @Bean BenchmarkApplications.Server benchmarkServerState(Environment env) throws Exception {
        return BenchmarkApplications.server(env,Map.of("library","nimrod","version",env.getRequiredProperty("benchmark.nimrod-version"),"protocol","rsocket-tcp","serializer","kryo5","serverWorkers",8));
    }
    @Bean BenchmarkRmi benchmarkService(BenchmarkApplications.Server server) { return server::invoke; }
}
