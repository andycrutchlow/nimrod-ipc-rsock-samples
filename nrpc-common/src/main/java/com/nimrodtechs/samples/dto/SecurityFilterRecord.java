package com.nimrodtechs.samples.dto;

public record SecurityFilterRecord(
        String dvId,
        String cusip,
        String isin,
        String figi,
        String universe_code,
        String industryCode,
        String securityTypeCode,
        String marketSectorCode
) { }
