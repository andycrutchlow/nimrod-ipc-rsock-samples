package com.nimrodtechs.samples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan("com.nimrodtechs.ipcrsock")
class ClientBasicApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClientBasicApplication.class, args);
    }

}
