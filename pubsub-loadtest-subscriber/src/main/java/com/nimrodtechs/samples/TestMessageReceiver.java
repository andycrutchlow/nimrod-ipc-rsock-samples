package com.nimrodtechs.samples;

import com.nimrodtechs.ipcrsock.common.MessageReceiverInterface;
import com.nimrodtechs.samples.dto.LoadTestMessage;

public class TestMessageReceiver implements MessageReceiverInterface<LoadTestMessage> {

    private final SubscriberMetrics metrics;

    public TestMessageReceiver(SubscriberMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void messageReceived(String publisherName, String subject, LoadTestMessage message) {
        metrics.record(
                message.sequence(),
                message.sendTimeNanos()
        );
    }
}
