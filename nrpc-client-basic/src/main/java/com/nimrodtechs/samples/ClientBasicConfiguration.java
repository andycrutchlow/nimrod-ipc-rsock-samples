package com.nimrodtechs.samples;

import com.nimrodtechs.samples.rmiservices.pricing.PricingServiceRmiInterface;
import com.nimrodtechs.samples.rmiservices.pricing.PricingServiceRmiInterface__NimrodRmiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = {"com.nimrodtechs.ipcrsock","com.nimrodtechs.samples.rmiservices.pricing","com.nimrodtechs.samples"})

class ClientBasicConfiguration {


}
