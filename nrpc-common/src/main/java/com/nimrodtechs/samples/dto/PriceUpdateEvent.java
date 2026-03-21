package com.nimrodtechs.samples.dto;

public record PriceUpdateEvent(
        String subject,     // e.g. "EUR/USD"
        long seq,
        long timeSentNs,
        byte[] payload
) {}