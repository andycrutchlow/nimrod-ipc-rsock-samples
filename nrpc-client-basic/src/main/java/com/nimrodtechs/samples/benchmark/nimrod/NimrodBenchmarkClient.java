package com.nimrodtechs.samples.benchmark.nimrod;

import com.nimrodtechs.samples.benchmark.common.*;
import com.nimrodtechs.benchmarks.nimrod.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.boot.ApplicationRunner;
import java.util.*;

@Configuration
@Profile("benchmark")
@Import(BenchmarkRmi__NimrodRmiClient.class)
public class NimrodBenchmarkClient {
    @Bean ApplicationRunner benchmarkClient(Environment env,BenchmarkRmi rpc) {
        Map<String,Object> identity=new LinkedHashMap<>();
        identity.put("library","nimrod");identity.put("version","4.3.1-SNAPSHOT");identity.put("protocol","rsocket-tcp");identity.put("serializer","kryo5");
        identity.put("timeoutEnforcement","benchmark deadline monitor interrupts blocking proxy; run aborts; remote cancellation not guaranteed");
        identity.put("nativeClientDeadline",false);identity.put("requesterPoolSize",env.getRequiredProperty("benchmark.concurrency"));
        identity.put("host",env.getProperty("benchmark.host","127.0.0.1"));identity.put("port",env.getProperty("benchmark.port","40280"));
        return BenchmarkApplications.client(env,rpc::benchmarkEcho,identity);
    }
}
