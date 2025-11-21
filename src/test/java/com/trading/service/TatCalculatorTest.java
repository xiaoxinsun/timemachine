package com.trading.service;

import com.trading.model.Order;
import com.trading.model.OrderStatus;
import com.trading.model.StatusTransition;
import com.trading.model.TeamConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TatCalculatorTest {

    private TatCalculator tatCalculator;

    @BeforeEach
    void setUp() {
        BusinessDurationCalculator durationCalculator = new BusinessDurationCalculator();
        
        Set<OrderStatus> auditStatuses = Arrays.stream(OrderStatus.values())
                .filter(s -> s.name().startsWith("AUDIT_REVIEW"))
                .collect(Collectors.toSet());
        
        Set<OrderStatus> tradingStatuses = Arrays.stream(OrderStatus.values())
                .filter(s -> s.name().startsWith("TRADING"))
                .collect(Collectors.toSet());

        TeamConfig auditConfig = TeamConfig.builder()
                .teamName("AUDIT_REVIEW")
                .statuses(auditStatuses)
                .startTime(LocalTime.of(9, 0))
                .cutoffTime(LocalTime.of(17, 0))
                .zoneId(ZoneId.systemDefault())
                .build();

        TeamConfig tradingConfig = TeamConfig.builder()
                .teamName("TRADING")
                .statuses(tradingStatuses)
                .startTime(LocalTime.of(9, 0))
                .cutoffTime(LocalTime.of(17, 0))
                .zoneId(ZoneId.systemDefault())
                .build();

        tatCalculator = new TatCalculator(durationCalculator, Map.of(
                "AUDIT_REVIEW", auditConfig,
                "TRADING", tradingConfig
        ));
    }

    @Test
    void testOverallTat() {
        LocalDateTime start = LocalDateTime.of(2023, 1, 2, 10, 0); // Monday
        List<StatusTransition> transitions = new ArrayList<>();
        transitions.add(new StatusTransition(OrderStatus.DRAFT, start));
        transitions.add(new StatusTransition(OrderStatus.SUBMITTED, start.plusMinutes(10)));
        transitions.add(new StatusTransition(OrderStatus.COMPLETED, start.plusMinutes(60)));

        Order order = Order.builder()
                .statusTransitions(transitions)
                .build();

        Duration result = tatCalculator.calculateOverallTat(order);
        assertEquals(Duration.ofMinutes(60), result);
    }

    @Test
    void testAuditReviewTeamTat_Standard() {
        LocalDateTime start = LocalDateTime.of(2023, 1, 2, 10, 0); // Monday 10 AM
        List<StatusTransition> transitions = new ArrayList<>();
        // DRAFT: 10:00
        transitions.add(new StatusTransition(OrderStatus.DRAFT, start));
        // AUDIT_REVIEW_LEVEL1_OPEN: 10:10
        transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_OPEN, start.plusMinutes(10)));
        // AUDIT_REVIEW_LEVEL1_IN_PROGRESS: 10:20
        transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_IN_PROGRESS, start.plusMinutes(20)));
        // STARTED: 10:50
        transitions.add(new StatusTransition(OrderStatus.STARTED, start.plusMinutes(50)));
        
        // Total AUDIT time = 10m (OPEN) + 30m (IN_PROGRESS) = 40m

        Order order = Order.builder()
                .statusTransitions(transitions)
                .build();

        Duration result = tatCalculator.calculateAuditReviewTeamTat(order);
        assertEquals(Duration.ofMinutes(40), result);
    }

    @Test
    void testAuditReviewTeamTat_ParkedExcluded() {
        LocalDateTime start = LocalDateTime.of(2023, 1, 2, 10, 0); // Monday
        List<StatusTransition> transitions = new ArrayList<>();
        
        // OPEN: 10:00
        transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_OPEN, start));
        
        // PARKED: 10:10 (OPEN duration: 10m)
        transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_PARKED, start.plusMinutes(10)));
        
        // IN_PROGRESS: 11:10 (PARKED duration: 60m - SHOULD BE EXCLUDED)
        transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_IN_PROGRESS, start.plusMinutes(70)));
        
        // COMPLETED: 11:20 (IN_PROGRESS duration: 10m)
        transitions.add(new StatusTransition(OrderStatus.COMPLETED, start.plusMinutes(80)));

        // Total AUDIT = 10m (OPEN) + 0m (PARKED) + 10m (IN_PROGRESS) = 20m

        Order order = Order.builder()
                .statusTransitions(transitions)
                .build();

        Duration result = tatCalculator.calculateAuditReviewTeamTat(order);
        assertEquals(Duration.ofMinutes(20), result);
    }

    @Test
    void testAuditReviewTeamTat_AfterCutoff() {
        // Friday 17:30 (After 17:00 cutoff)
        LocalDateTime start = LocalDateTime.of(2023, 1, 6, 17, 30); 
        
        List<StatusTransition> transitions = new ArrayList<>();
        
        // OPEN: Fri 17:30 -> Mon 09:30
        // Effective Start: Mon 09:00
        // End: Mon 09:30
        // Duration: 30m
        transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_OPEN, start));
        
        // COMPLETED: Mon 09:30
        LocalDateTime end = LocalDateTime.of(2023, 1, 9, 9, 30);
        transitions.add(new StatusTransition(OrderStatus.COMPLETED, end));

        Order order = Order.builder()
                .statusTransitions(transitions)
                .build();

        Duration result = tatCalculator.calculateAuditReviewTeamTat(order);
        assertEquals(Duration.ofMinutes(30), result);
    }

    @Test
    void testAuditReviewTeamTat_OverWeekend() {
        // Friday 16:00
        LocalDateTime start = LocalDateTime.of(2023, 1, 6, 16, 0);
        
        List<StatusTransition> transitions = new ArrayList<>();
        
        // OPEN: Fri 16:00 -> Mon 10:00
        // Fri: 16:00 -> 17:00 (1h)
        // Sat/Sun: 0
        // Mon: 09:00 -> 10:00 (1h)
        // Total: 2h = 120m
        transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_OPEN, start));
        
        // COMPLETED: Mon 10:00
        LocalDateTime end = LocalDateTime.of(2023, 1, 9, 10, 0);
        transitions.add(new StatusTransition(OrderStatus.COMPLETED, end));

        Order order = Order.builder()
                .statusTransitions(transitions)
                .build();

        Duration result = tatCalculator.calculateAuditReviewTeamTat(order);
        assertEquals(Duration.ofMinutes(120), result);
    }
}
