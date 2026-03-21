package com.nimrodtechs.samples.dto;

public record MarketData(String ccyPair, String tenor, String BidPrice, String AskPrice, long createdAt) {
}
