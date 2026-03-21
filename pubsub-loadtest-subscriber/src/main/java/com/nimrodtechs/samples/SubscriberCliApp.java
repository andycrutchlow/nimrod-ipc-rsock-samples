package com.nimrodtechs.samples;

import com.nimrodtechs.ipcrsock.subscriber.SubscriberService;
import com.nimrodtechs.samples.dto.LoadTestMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

@SpringBootApplication()
@ComponentScan(basePackages = {"com.nimrodtechs.ipcrsock.subscriber","com.nimrodtechs.ipcrsock.serialization","com.nimrodtechs.ipcrsock.common"})public class SubscriberCliApp implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SubscriberCliApp.class);

    @Autowired
    private SubscriberService subscriberService;

    public static void main(String[] args) {
        SpringApplication.run(SubscriberCliApp.class, args);
    }

    @Override
    public void run(String... args) throws Exception {

        Config config = Config.parse(args);

        log.info("Starting subscriber CLI: publisher={}, subjects={}",
                config.publisherName, config.subjects);

        SubscriberMetrics metrics = new SubscriberMetrics();
        TestMessageReceiver receiver = new TestMessageReceiver(metrics);

        // subscribe to all subjects
        for (String subject : config.subjects) {
            subscriberService.subscribe(
                    config.publisherName,
                    subject,
                    receiver,
                    LoadTestMessage.class,
                    false // no conflation for testing
            );
        }

        ScheduledExecutorService statsExecutor =
                Executors.newSingleThreadScheduledExecutor();

        statsExecutor.scheduleAtFixedRate(() -> {
            SubscriberMetrics.Snapshot s = metrics.snapshotAndResetInterval();

            log.info("rate={} msg/s total={} avgLatency={}µs maxLatency={}µs gaps={}",
                    s.msgsPerSec(),
                    s.total(),
                    String.format("%.2f", s.avgLatencyMicros()),
                    s.maxLatencyMicros(),
                    s.gapCount()
            );

        }, 1, 1, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down subscriber...");
            statsExecutor.shutdownNow();
        }));
    }

    static class Config {
        String publisherName;
        List<String> subjects;

        static Config parse(String[] args) {
            Config config = new Config();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];

                switch (arg) {
                    case "--publisher" -> config.publisherName = args[++i];
                    case "--subjects" -> config.subjects = Arrays.asList(args[++i].split(","));
                    default -> throw new IllegalArgumentException("Unknown arg: " + arg);
                }
            }

            if (config.publisherName == null || config.subjects == null) {
                throw new IllegalArgumentException("--publisher and --subjects required");
            }

            return config;
        }
    }
}