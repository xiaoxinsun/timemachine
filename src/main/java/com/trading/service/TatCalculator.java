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

    // Default constructor for backward compatibility or simple tests if needed, 
    // but ideally we should use the one with dependencies.
    // For now, I'll remove the default constructor to force configuration.

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

    // Deprecated methods kept for now or removed? 
    // The user asked to "introduce additional order status which corresponding to additional teams".
    // So I should probably replace the hardcoded methods with the generic one,
    // or delegate them to the generic one if we want to keep the API.
    // I'll keep them for now but delegate.

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

        Duration totalDuration = Duration.ZERO;

        for (int i = 0; i < transitions.size() - 1; i++) {
            StatusTransition current = transitions.get(i);
            StatusTransition next = transitions.get(i + 1);

            // Skip if parked
            if (current.getStatus().name().endsWith("_PARKED")) {
                continue;
            }

            // Check if status belongs to team
            if (config.statuses().contains(current.getStatus())) {
                totalDuration = totalDuration.plus(
                    durationCalculator.calculateDuration(current.getChangeTime(), next.getChangeTime(), config)
                );
            }
        }

        return totalDuration;
    }
}
