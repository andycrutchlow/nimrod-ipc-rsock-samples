package com.nimrodtechs.samples;

import com.nimrodtechs.samples.model.BasicClass1;
import com.nimrodtechs.samples.model.BasicClass2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = {"com.nimrodtechs.ipcrsock","com.nimrodtechs.samples.rmiservices.pricing","com.nimrodtechs.samples"})
class ServerBasicConfiguration {

    public ServerBasicConfiguration() {
        System.out.println("HERE");
    }

    @Bean
    BasicClass1 bean1() {
        return new BasicClass1("Hello World! I'm Bean 1");
    }
    @Bean
    BasicClass2 bean2() {
        return new BasicClass2("Hello World! I'm Bean 2");
    }
}
