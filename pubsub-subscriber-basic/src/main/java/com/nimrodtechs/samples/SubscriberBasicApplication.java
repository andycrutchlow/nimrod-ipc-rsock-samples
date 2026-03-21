package com.nimrodtechs.samples;

import com.nimrodtechs.ipcrsock.common.MessageReceiverInterface;
import com.nimrodtechs.ipcrsock.subscriber.SubscriberService;
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

import java.util.Scanner;

@SpringBootApplication
@ConfigurationPropertiesScan("com.nimrodtechs.ipcrsock")
@ComponentScan(basePackages = {"com.nimrodtechs.ipcrsock.subscriber","com.nimrodtechs.ipcrsock.serialization","com.nimrodtechs.ipcrsock.common"})

public class SubscriberBasicApplication {
    private static final Logger log = LoggerFactory.getLogger(SubscriberBasicApplication.class);
    @Autowired
    SubscriberService subscriberService;

    public static void main(String[] args) {
        SpringApplication.run(SubscriberBasicApplication.class, args);
    }

    @Bean
    CommandLineRunner runSubscriber() {
        return args -> {

            Scanner scanner = new Scanner(System.in);

            MessageReceiverInterface<MarketData> receiver = (publisher, subject, marketData) -> {
                log.info("RECEIVED -> " + subject + " : latency="+(System.nanoTime() - marketData.createdAt())+ " : " + marketData);

            };

            while (true) {

                System.out.println();
                System.out.println("Choose subscription:");
                System.out.println("1 - EURUSD.*");
                System.out.println("2 - EURUSD.SPOT");
                System.out.println("3 - EURUSD.1W");
                System.out.println("q - quit");
                System.out.print("> ");

                String input = scanner.nextLine();

                switch (input) {

                    case "1":
                        subscriberService.subscribe(
                                "publisher1",
                                "EURUSD.*",
                                receiver,
                                MarketData.class,
                                false
                        );
                        break;

                    case "2":
                        subscriberService.subscribe(
                                "publisher1",
                                "EURUSD.SPOT",
                                receiver,
                                MarketData.class,
                                false
                        );
                        break;

                    case "3":
                        subscriberService.subscribe(
                                "publisher1",
                                "EURUSD.1W",
                                receiver,
                                MarketData.class,
                                false
                        );
                        break;

                    case "q":
                        System.exit(0);
                        break;

                    default:
                        System.out.println("Unknown option");
                }
            }
        };
    }
}
