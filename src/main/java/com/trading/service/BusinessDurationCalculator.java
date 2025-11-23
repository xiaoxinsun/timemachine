package com.trading.service;

import com.trading.model.TeamConfig;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

public class BusinessDurationCalculator {

    public Duration calculateDuration(LocalDateTime start, LocalDateTime end, TeamConfig config) {
        if (start == null || end == null || config == null) {
            return Duration.ZERO;
        }
        if (start.isAfter(end)) {
            return Duration.ZERO;
        }

        // 1. Adjust Start Time based on Cutoff and Business Hours
        LocalDateTime effectiveStart = adjustStartTime(start, config);

        // If adjustment pushed start past end, duration is 0
        if (effectiveStart.isAfter(end)) {
            return Duration.ZERO;
        }

        // 2. Calculate duration within business hours
        return calculateBusinessDuration(effectiveStart, end, config);
    }

    private LocalDateTime adjustStartTime(LocalDateTime start, TeamConfig config) {
        LocalDateTime adjusted = start;
        LocalTime time = start.toLocalTime();

        // If after cutoff, move to next business day start
        if (time.isAfter(config.cutoffTime())) {
            adjusted = getNextBusinessDayStart(start.toLocalDate().plusDays(1), config);
        }
        // If before start time, move to start time of same day (if business day)
        else if (time.isBefore(config.startTime())) {
            if (isWeekend(start)) {
                adjusted = getNextBusinessDayStart(start.toLocalDate(), config);
            } else {
                adjusted = LocalDateTime.of(start.toLocalDate(), config.startTime());
            }
        }
        // If on weekend, move to next business day start
        else if (isWeekend(start)) {
            adjusted = getNextBusinessDayStart(start.toLocalDate(), config);
        }

        return adjusted;
    }

    public LocalDateTime getNextBusinessDayStart(java.time.LocalDate date, TeamConfig config) {
        java.time.LocalDate nextDate = date;
        while (isWeekend(nextDate)) {
            nextDate = nextDate.plusDays(1);
        }
        return LocalDateTime.of(nextDate, config.startTime());
    }

    private boolean isWeekend(LocalDateTime dateTime) {
        return isWeekend(dateTime.toLocalDate());
    }

    private boolean isWeekend(java.time.LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private Duration calculateBusinessDuration(LocalDateTime start, LocalDateTime end, TeamConfig config) {
        Duration totalDuration = Duration.ZERO;
        LocalDateTime current = start;

        while (current.isBefore(end)) {
            if (isWeekend(current)) {
                // Skip to next day start (which might be Monday)
                current = LocalDateTime.of(current.toLocalDate().plusDays(1), config.startTime());
                continue;
            }

            LocalDateTime dayEnd = LocalDateTime.of(current.toLocalDate(), config.cutoffTime()); // Using cutoff as end
                                                                                                 // of business day?
            // Requirement says "cutoff time which marked their end of day".
            // So we count time until cutoff.

            // If current time is already past cutoff (shouldn't happen with adjusted start,
            // but for safety), skip to next day
            if (current.toLocalTime().isAfter(config.cutoffTime())) {
                current = LocalDateTime.of(current.toLocalDate().plusDays(1), config.startTime());
                continue;
            }

            // If current time is before start (shouldn't happen with adjusted start), move
            // to start
            if (current.toLocalTime().isBefore(config.startTime())) {
                current = LocalDateTime.of(current.toLocalDate(), config.startTime());
            }

            // Determine the end of the counting interval for this day
            // It's either the actual end time (if on same day) or the business day end
            // (cutoff)
            LocalDateTime intervalEnd;
            if (end.toLocalDate().equals(current.toLocalDate())) {
                if (end.toLocalTime().isBefore(config.cutoffTime())) {
                    intervalEnd = end;
                } else {
                    intervalEnd = dayEnd;
                }
            } else {
                intervalEnd = dayEnd;
            }

            if (current.isBefore(intervalEnd)) {
                totalDuration = totalDuration.plus(Duration.between(current, intervalEnd));
            }

            // Move to next day start
            current = LocalDateTime.of(current.toLocalDate().plusDays(1), config.startTime());
        }

        return totalDuration;
    }
}
