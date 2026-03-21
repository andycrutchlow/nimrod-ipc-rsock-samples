package com.nimrodtechs.samples.dto;

public record LoadTestMessage(
        long sequence,
        long sendTimeNanos,
        String publisherName,
        String subject,
        byte[] payload
) {}
