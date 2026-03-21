package com.nimrodtechs.samples;

import com.nimrodtechs.ipcrsock.common.SubscriptionListener;
import com.nimrodtechs.ipcrsock.common.SubscriptionRequest;
import com.nimrodtechs.ipcrsock.publisher.PublisherSocketImpl;
import com.nimrodtechs.samples.dto.MarketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.util.concurrent.ThreadLocalRandom;

@SpringBootApplication
@ConfigurationPropertiesScan("com.nimrodtechs.ipcrsock")
@ComponentScan(basePackages = {"com.nimrodtechs.ipcrsock.publisher"})

public class PublisherBasicApplication implements SubscriptionListener {
    private static final Logger log = LoggerFactory.getLogger(PublisherBasicApplication.class);
    @Autowired
    PublisherSocketImpl publisherSocket;

    public static void main(String[] args) {
        SpringApplication.run(PublisherBasicApplication.class, args);
    }

    @Bean
    CommandLineRunner runPublisher() {
        return args -> {

            log.info("Publisher starting...");
            publisherSocket.addSubscriptionListener(this);
            while (true) {
                MarketData msg = new MarketData("EURUSD", "SPOT", randomBid("1.123"), randomAsk("1.123"), System.nanoTime());
                publisherSocket.publish(
                        msg.ccyPair() + "." + msg.tenor(),
                        msg
                );
                //log.info("Published message : " + msg.toString());
                Thread.sleep(1000);

                msg = new MarketData("EURUSD", "1W", randomBid("1.123")+"1", randomAsk("1.123")+"2",System.nanoTime());
                publisherSocket.publish(
                        msg.ccyPair() + "." + msg.tenor(),
                        msg
                );
                //log.info("Published message : " + msg.toString());
                Thread.sleep(1000);
            }
        };
    }

    public static String randomBid(String baseValue) {
        int n = ThreadLocalRandom.current().nextInt(1, 50);   // 1–49
        return baseValue + String.format("%02d", n);
    }

    public static String randomAsk(String baseValue) {
        int n = ThreadLocalRandom.current().nextInt(50, 100); // 50–99
        return baseValue + String.format("%02d", n);
    }
    @Override
    public void onSubscription(SubscriptionRequest subscriptionRequest) {
        log.info("Subscription received : " + subscriptionRequest.toString());
    }

    @Override
    public void onSubscriptionRemove(SubscriptionRequest subscriptionRequest) {
        log.info("Subscription removed : " + subscriptionRequest.toString());
    }


}


