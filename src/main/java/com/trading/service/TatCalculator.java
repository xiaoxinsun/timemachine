package com.trading.service;

import com.trading.model.Order;
import com.trading.model.OrderStatus;
import com.trading.model.StatusTransition;
import com.trading.model.TeamConfig;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class TatCalculator {

    private final BusinessDurationCalculator durationCalculator;
    private final Map<String, TeamConfig> teamConfigs;

    public TatCalculator(BusinessDurationCalculator durationCalculator, Map<String, TeamConfig> teamConfigs) {
        this.durationCalculator = durationCalculator;
        this.teamConfigs = teamConfigs;
    }

    public Duration calculateOverallTat(Order order) {
        return calculateDurationBetween(order, OrderStatus.DRAFT, OrderStatus.COMPLETED);
    }

    public Duration calculateReviewTat(Order order) {
        return calculateDurationBetween(order, OrderStatus.SUBMITTED, OrderStatus.STARTED);
    }

    public Duration calculateExecutionTat(Order order) {
        return calculateDurationBetween(order, OrderStatus.STARTED, OrderStatus.COMPLETED);
    }

    public Duration calculateTeamTat(Order order, String teamName) {
        TeamConfig config = teamConfigs.get(teamName);
        if (config == null) {
            throw new IllegalArgumentException("Unknown team: " + teamName);
        }
        return calculateTotalDurationInStatuses(order, config);
    }

    public Duration calculateAuditReviewTeamTat(Order order) {
        return calculateTeamTat(order, "AUDIT_REVIEW");
    }

    public Duration calculateTradingTeamTat(Order order) {
        return calculateTeamTat(order, "TRADING");
    }

    private Duration calculateDurationBetween(Order order, OrderStatus startStatus, OrderStatus endStatus) {
        List<StatusTransition> transitions = order.statusTransitions();
        if (transitions == null || transitions.isEmpty()) {
            return Duration.ZERO;
        }

        transitions.sort(Comparator.comparing(StatusTransition::getChangeTime));

        LocalDateTime startTime = null;
        LocalDateTime endTime = null;

        for (StatusTransition transition : transitions) {
            if (transition.getStatus() == startStatus && startTime == null) {
                startTime = transition.getChangeTime();
            }
            if (transition.getStatus() == endStatus) {
                endTime = transition.getChangeTime();
            }
        }

        if (startTime != null && endTime != null) {
            return Duration.between(startTime, endTime);
        }
        return Duration.ZERO;
    }

    private Duration calculateTotalDurationInStatuses(Order order, TeamConfig config) {
        List<StatusTransition> transitions = order.statusTransitions();
        if (transitions == null || transitions.isEmpty()) {
            return Duration.ZERO;
        }

        transitions.sort(Comparator.comparing(StatusTransition::getChangeTime));

        // 1. Identify Team's Transitions
        StatusTransition startTransition = null;
        StatusTransition inProgressTransition = null;
        LocalDateTime endTime = null;

        for (int i = 0; i < transitions.size(); i++) {
            StatusTransition t = transitions.get(i);
            if (config.statuses().contains(t.getStatus())) {
                if (t.getStatus() == config.entryStatus() && startTransition == null) {
                    startTransition = t;
                }

                // Check for IN_PROGRESS
                if (t.getStatus() == config.firstInProgressStatus() && inProgressTransition == null) {
                    inProgressTransition = t;
                }

                // Look ahead for end of team block
                if (i + 1 < transitions.size()) {
                    StatusTransition next = transitions.get(i + 1);
                    if (!config.statuses().contains(next.getStatus())) {
                        endTime = next.getChangeTime();
                        break; // Exited team block
                    }
                }
            }
        }

        if (startTransition == null || endTime == null) {
            return Duration.ZERO;
        }

        // 2. Calculate Effective Start Time
        LocalDateTime effectiveStart = calculateEffectiveStartTime(startTransition, inProgressTransition, config);

        // 3. Calculate Duration (Wall Clock - Parked)
        if (effectiveStart.isAfter(endTime)) {
            return Duration.ZERO;
        }

        Duration totalDuration = Duration.between(effectiveStart, endTime);
        Duration parkedDuration = calculateParkedDuration(transitions, effectiveStart, endTime);

        return totalDuration.minus(parkedDuration);
    }

    private LocalDateTime calculateEffectiveStartTime(StatusTransition startTransition,
            StatusTransition inProgressTransition, TeamConfig config) {
        LocalDateTime entryTime = startTransition.getChangeTime();

        // Check if entry is after cutoff
        if (entryTime.toLocalTime().isAfter(config.cutoffTime())) {
            LocalDateTime nextBusinessDayStart = durationCalculator
                    .getNextBusinessDayStart(entryTime.toLocalDate().plusDays(1), config);

            // Special Case: Team started working (IN_PROGRESS) before next business day
            if (inProgressTransition != null && inProgressTransition.getChangeTime().isBefore(nextBusinessDayStart)) {
                return inProgressTransition.getChangeTime();
            }

            return nextBusinessDayStart;
        }

        return entryTime;
    }

    private Duration calculateParkedDuration(List<StatusTransition> transitions, LocalDateTime start,
            LocalDateTime end) {
        Duration parkedDuration = Duration.ZERO;

        for (int i = 0; i < transitions.size() - 1; i++) {
            StatusTransition current = transitions.get(i);
            StatusTransition next = transitions.get(i + 1);

            if (current.getStatus().isParked()) {
                LocalDateTime parkStart = current.getChangeTime();
                LocalDateTime parkEnd = next.getChangeTime();

                // Intersect park interval with [start, end]
                LocalDateTime effectiveParkStart = parkStart.isAfter(start) ? parkStart : start;
                LocalDateTime effectiveParkEnd = parkEnd.isBefore(end) ? parkEnd : end;

                if (effectiveParkStart.isBefore(effectiveParkEnd)) {
                    parkedDuration = parkedDuration.plus(Duration.between(effectiveParkStart, effectiveParkEnd));
                }
            }
        }
        return parkedDuration;
    }
}
