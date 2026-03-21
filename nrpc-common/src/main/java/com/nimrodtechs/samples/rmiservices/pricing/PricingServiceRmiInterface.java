package com.nimrodtechs.samples.rmiservices.pricing;

import com.nimrodtechs.ipcrsock.annotations.NimrodFireAndForget;
import com.nimrodtechs.ipcrsock.annotations.NimrodRmiInterface;
import com.nimrodtechs.ipcrsock.annotations.SchedulerType;
import com.nimrodtechs.samples.dto.PriceRequest;
import com.nimrodtechs.samples.dto.PriceResponse;
import com.nimrodtechs.samples.dto.PriceUpdateEvent;

@NimrodRmiInterface(serviceName = "server1", concurrency = 8, scheduler = SchedulerType.PARALLEL)
public interface PricingServiceRmiInterface {
    public PriceResponse getPrice(PriceRequest request) throws Exception;

    @NimrodFireAndForget
    void publishPriceUpdate(PriceUpdateEvent event) throws Exception;
}
