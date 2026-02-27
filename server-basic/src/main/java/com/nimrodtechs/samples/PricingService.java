package com.nimrodtechs.samples;

import com.nimrodtechs.samples.dto.PriceRequest;
import com.nimrodtechs.samples.dto.PriceResponse;
import com.nimrodtechs.samples.rmiservices.pricing.PricingServiceRmiInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PricingService implements PricingServiceRmiInterface {
    private static final Logger logger = LoggerFactory.getLogger(PricingService.class);
    public PricingService() {
        logger.info("PricingService Created. Ready to serve.");
    }

    @Override
    public PriceResponse getPrice(PriceRequest request) throws Exception {
        return new PriceResponse(request.ccyPair(),request.tenor(),"1.1234",request.timeSent(),(request.timeSent() != 0 ? System.nanoTime() : 0));
    }
}
