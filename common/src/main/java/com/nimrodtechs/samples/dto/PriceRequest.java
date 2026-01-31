package com.nimrodtechs.samples.dto;

public record PriceRequest(String ccyPair, String tenor, long timeSent) {
}
