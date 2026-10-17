package com.nimrodtechs.samples.benchmark.dubboclient;

import com.nimrodtechs.samples.benchmark.common.*;
import com.nimrodtechs.samples.benchmark.dubbo.*;
import org.apache.dubbo.config.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class DubboClientApplication {
    public static void main(String[] args) { SpringApplication.run(DubboClientApplication.class,args); }
    @Bean(destroyMethod="destroy") ReferenceConfig<DubboBenchmark> benchmarkReference(Environment env) {
        ReferenceConfig<DubboBenchmark> reference=new ReferenceConfig<>();reference.setApplication(DubboSettings.application("client"));reference.setRegistry(DubboSettings.registry());
        reference.setInterface(DubboBenchmark.class);reference.setUrl(DubboSettings.url(env));reference.setInjvm(false);reference.setRetries(0);reference.setCluster("failfast");
        reference.setTimeout(env.getProperty("benchmark.timeout-millis",Integer.class,5000));reference.setCheck(true);return reference;
    }
    @Bean ApplicationRunner benchmarkClient(Environment env,ReferenceConfig<DubboBenchmark> reference) {
        DubboBenchmark client=reference.get();return BenchmarkApplications.client(env,client::echo,DubboSettings.identity(env),ObservedSerialization::snapshot);
    }
}
