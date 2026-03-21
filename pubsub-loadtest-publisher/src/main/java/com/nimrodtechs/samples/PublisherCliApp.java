package com.nimrodtechs.samples;

import com.nimrodtechs.ipcrsock.publisher.PublisherSocketImpl;
import com.nimrodtechs.ipcrsock.serialization.KryoDecoder;
import com.nimrodtechs.ipcrsock.serialization.KryoEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

@SpringBootApplication()
@ComponentScan(basePackages = {"com.nimrodtechs.ipcrsock.publisher"})
public class PublisherCliApp implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PublisherCliApp.class);

    @Autowired
    private PublisherSocketImpl publisherSocket;

    public static void main(String[] args) {
        SpringApplication.run(PublisherCliApp.class, args);
    }

    @Override
    public void run(String... args) throws Exception {

        Config config = Config.parse(args);

        log.info("Starting publisher CLI with subjects={}, rate={}, payloadSize={}, durationSeconds={}, mode={}",
                config.subjects, config.ratePerSecond, config.payloadSize, config.durationSeconds, config.mode);

        publisherSocket.start(config.port);

        PublisherLoadEngine engine = new PublisherLoadEngine(
                publisherSocket,
                config.publisherName,
                config.subjects,
                config.ratePerSecond,
                config.payloadSize,
                config.durationSeconds,
                config.mode
        );

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(engine);

        Runtime.getRuntime().addShutdownHook(new Thread(engine::stop));
    }

    static class Config {
        String publisherName = "samplePublisher";
        int port = 40281;
        List<String> subjects;
        long ratePerSecond = 1000;
        int payloadSize = 128;
        long durationSeconds = 30;
        PublisherLoadEngine.Mode mode = PublisherLoadEngine.Mode.FIXED;

        static Config parse(String[] args) {
            Config config = new Config();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];

                switch (arg) {
                    case "--publisher" -> config.publisherName = requireValue(args, ++i, arg);
                    case "--port" -> config.port = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--subjects" -> config.subjects = Arrays.asList(requireValue(args, ++i, arg).split(","));
                    case "--rate" -> config.ratePerSecond = Long.parseLong(requireValue(args, ++i, arg));
                    case "--payload-size" -> config.payloadSize = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--duration-seconds" -> config.durationSeconds = Long.parseLong(requireValue(args, ++i, arg));
                    case "--mode" -> config.mode = PublisherLoadEngine.Mode.valueOf(
                            requireValue(args, ++i, arg).trim().toUpperCase(Locale.ROOT).replace('-', '_')
                    );
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (config.subjects == null || config.subjects.isEmpty()) {
                throw new IllegalArgumentException("--subjects is required");
            }

            return config;
        }

        private static String requireValue(String[] args, int index, String argName) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + argName);
            }
            return args[index];
        }
    }
}
