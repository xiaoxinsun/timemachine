package com.trading.model;

import lombok.Builder;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

@Builder
public record TeamConfig(
    String teamName,
    Set<OrderStatus> statuses,
    LocalTime startTime,
    LocalTime cutoffTime,
    ZoneId zoneId
) {}
