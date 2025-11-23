package com.trading.model;

import lombok.Builder;

import java.util.List;
import java.time.LocalTime;
import java.time.ZoneId;

@Builder
public record TeamConfig(
        String teamName,
        List<ActivityBlock> activityBlocks,
        LocalTime startTime,
        LocalTime cutoffTime,
        ZoneId zoneId) {
}
