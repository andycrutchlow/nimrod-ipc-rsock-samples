package com.nimrodtechs.samples;

import com.nimrodtechs.ipcrsock.publisher.PublisherSocketImpl;
import com.nimrodtechs.samples.dto.LoadTestMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

public class PublisherLoadEngine implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(PublisherLoadEngine.class);
    public enum Mode {
        FIXED,
        ROUND_ROBIN
    }

    private final PublisherSocketImpl publisherSocket;
    private final String publisherName;
    private final List<String> subjects;
    private final long ratePerSecond;
    private final int payloadSize;
    private final long durationSeconds;
    private final Mode mode;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final LongAdder sentCount = new LongAdder();

    public PublisherLoadEngine(
            PublisherSocketImpl publisherSocket,
            String publisherName,
            List<String> subjects,
            long ratePerSecond,
            int payloadSize,
            long durationSeconds,
            Mode mode
    ) {
        if (subjects == null || subjects.isEmpty()) {
            throw new IllegalArgumentException("subjects must not be empty");
        }
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be > 0");
        }
        if (payloadSize < 0) {
            throw new IllegalArgumentException("payloadSize must be >= 0");
        }

        this.publisherSocket = publisherSocket;
        this.publisherName = publisherName;
        this.subjects = subjects;
        this.ratePerSecond = ratePerSecond;
        this.payloadSize = payloadSize;
        this.durationSeconds = durationSeconds;
        this.mode = mode;
    }

    public long getSentCount() {
        return sentCount.sum();
    }

    public void stop() {
        running.set(false);
        //Dump out metrics
       log.info("Sent: " + getSentCount()+" messages");
    }

    @Override
    public void run() {
        final long intervalNanos = 1_000_000_000L / ratePerSecond;
        final long startTime = System.nanoTime();
        final long endTime = durationSeconds > 0
                ? startTime + durationSeconds * 1_000_000_000L
                : Long.MAX_VALUE;

        long sequence = 0;
        int subjectIndex = 0;
        long nextSendTime = startTime;

        while (running.get()) {
            long now = System.nanoTime();

            if (now >= endTime) {
                break;
            }

            long remaining = nextSendTime - now;

            if (remaining > 1_000_000L) {
                // > 1 ms away
                LockSupport.parkNanos(remaining - 500_000L);
                continue;
            }

            if (remaining > 50_000L) {
                // 50 µs to 1 ms away
                LockSupport.parkNanos(remaining / 2);
                continue;
            }

            while ((now = System.nanoTime()) < nextSendTime) {
                Thread.onSpinWait();
            }

            String subject;
            if (mode == Mode.FIXED) {
                subject = subjects.get(0);
            } else {
                subject = subjects.get(subjectIndex);
                subjectIndex++;
                if (subjectIndex >= subjects.size()) {
                    subjectIndex = 0;
                }
            }

            LoadTestMessage testMessage = new LoadTestMessage(
                    sequence,
                    System.nanoTime(),
                    publisherName,
                    subject,
                    buildPayload(payloadSize, sequence)
            );

            publisherSocket.publish(subject, testMessage);
            sentCount.increment();

            sequence++;
            nextSendTime += intervalNanos;

            // Catch-up protection: if badly behind, don't try to replay ancient schedule forever.
            long lag = System.nanoTime() - nextSendTime;
            if (lag > 1_000_000_000L) {
                nextSendTime = System.nanoTime();
            }
        }
    }

    private byte[] buildPayload(int size, long sequence) {
        byte[] data = new byte[size];
        if (size > 0) {
            byte fill = (byte) (sequence & 0x7F);
            for (int i = 0; i < data.length; i++) {
                data[i] = fill;
            }
        }
        return data;
    }
}
