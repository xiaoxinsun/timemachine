package com.trading.service;

import com.trading.model.ActivityBlock;
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
                                .filter(s -> s.name().startsWith("AUDIT_REVIEW")
                                                && !s.name().contains("CREDIT_APPROVAL")
                                                && !s.name().endsWith("_APPROVED"))
                                .collect(Collectors.toSet());

                Set<OrderStatus> creditApprovalStatuses = Arrays.stream(OrderStatus.values())
                                .filter(s -> s.name().contains("CREDIT_APPROVAL") && !s.name().endsWith("_APPROVED"))
                                .collect(Collectors.toSet());

                Set<OrderStatus> tradingStatuses = Arrays.stream(OrderStatus.values())
                                .filter(s -> s.name().startsWith("TRADING"))
                                .collect(Collectors.toSet());

                ActivityBlock auditBlock1 = ActivityBlock.builder()
                                .statuses(auditStatuses)
                                .entryStatus(OrderStatus.AUDIT_REVIEW_LEVEL1_OPEN)
                                .firstInProgressStatus(OrderStatus.AUDIT_REVIEW_LEVEL1_IN_PROGRESS)
                                .build();

                ActivityBlock auditBlock2 = ActivityBlock.builder()
                                .statuses(creditApprovalStatuses)
                                .entryStatus(OrderStatus.AUDIT_REVIEW_CREDIT_APPROVAL_LEVEL1_OPEN)
                                .firstInProgressStatus(OrderStatus.AUDIT_REVIEW_CREDIT_APPROVAL_LEVEL1_IN_PROGRESS)
                                .build();

                TeamConfig auditConfig = TeamConfig.builder()
                                .teamName("AUDIT_REVIEW")
                                .activityBlocks(List.of(auditBlock1, auditBlock2))
                                .startTime(LocalTime.of(9, 0))
                                .cutoffTime(LocalTime.of(17, 0))
                                .zoneId(ZoneId.systemDefault())
                                .build();

                ActivityBlock tradingBlock = ActivityBlock.builder()
                                .statuses(tradingStatuses)
                                .entryStatus(OrderStatus.TRADING_OPEN)
                                .firstInProgressStatus(OrderStatus.TRADING_IN_PROGRESS)
                                .build();

                TeamConfig tradingConfig = TeamConfig.builder()
                                .teamName("TRADING")
                                .activityBlocks(List.of(tradingBlock))
                                .startTime(LocalTime.of(9, 0))
                                .cutoffTime(LocalTime.of(17, 0))
                                .zoneId(ZoneId.systemDefault())
                                .build();

                tatCalculator = new TatCalculator(durationCalculator, Map.of(
                                "AUDIT_REVIEW", auditConfig,
                                "TRADING", tradingConfig));
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
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_IN_PROGRESS,
                                start.plusMinutes(20)));
                // STARTED: 10:50
                transitions.add(new StatusTransition(OrderStatus.STARTED, start.plusMinutes(50)));

                // Entry: 10:10 (Before Cutoff) -> Effective Start: 10:10
                // End: 10:50
                // Duration: 40m

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

                // PARKED: 10:10
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_PARKED, start.plusMinutes(10)));

                // IN_PROGRESS: 11:10 (Parked for 60m)
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_IN_PROGRESS,
                                start.plusMinutes(70)));

                // COMPLETED: 11:20
                transitions.add(new StatusTransition(OrderStatus.COMPLETED, start.plusMinutes(80)));

                // Entry: 10:00 -> Effective Start: 10:00
                // End: 11:20
                // Gross Duration: 80m
                // Parked: 10:10 -> 11:10 (60m)
                // Net: 20m

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

                // OPEN: Fri 17:30
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_OPEN, start));

                // COMPLETED: Mon 09:30
                LocalDateTime end = LocalDateTime.of(2023, 1, 9, 9, 30);
                transitions.add(new StatusTransition(OrderStatus.COMPLETED, end));

                // Entry: Fri 17:30 (> Cutoff)
                // Next Business Day Start: Mon 09:00
                // Effective Start: Mon 09:00
                // End: Mon 09:30
                // Duration: 30m

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

                // OPEN: Fri 16:00
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_OPEN, start));

                // COMPLETED: Mon 10:00
                LocalDateTime end = LocalDateTime.of(2023, 1, 9, 10, 0);
                transitions.add(new StatusTransition(OrderStatus.COMPLETED, end));

                // Entry: Fri 16:00 (< Cutoff)
                // Effective Start: Fri 16:00
                // End: Mon 10:00
                // Duration (Wall Clock): Fri 16:00 -> Mon 10:00
                // Fri 16:00 -> Sat 16:00 (24h)
                // Sat 16:00 -> Sun 16:00 (24h)
                // Sun 16:00 -> Mon 10:00 (18h)
                // Total: 66h = 3960m

                Order order = Order.builder()
                                .statusTransitions(transitions)
                                .build();

                Duration result = tatCalculator.calculateAuditReviewTeamTat(order);
                assertEquals(Duration.ofHours(66), result);
        }

        @Test
        void testUserExample() {
                LocalDateTime t0 = LocalDateTime.of(2023, 1, 2, 9, 0); // DRAFT
                LocalDateTime t1 = t0.plusMinutes(10); // SUBMITTED
                LocalDateTime t2 = t0.plusMinutes(20); // STARTED
                LocalDateTime t3 = t0.plusMinutes(30); // AUDIT_REVIEW_LEVEL1_OPEN
                LocalDateTime t4 = t0.plusMinutes(40); // AUDIT_REVIEW_LEVEL1_IN_PROGRESS
                LocalDateTime t5 = t0.plusMinutes(50); // AUDIT_REVIEW_LEVEL1_PARKED
                LocalDateTime t6 = t0.plusMinutes(110); // AUDIT_REVIEW_LEVEL1_IN_PROGRESS (Parked for 60m)
                LocalDateTime t7 = t0.plusMinutes(120); // AUDIT_REVIEW_LEVEL1_SUBMITTED
                LocalDateTime t8 = t0.plusMinutes(130); // AUDIT_REVIEW_LEVEL2_OPEN
                LocalDateTime t9 = t0.plusMinutes(140); // AUDIT_REVIEW_LEVEL2_IN_PROGRESS
                LocalDateTime t10 = t0.plusMinutes(150); // AUDIT_REVIEW_LEVEL2_APPROVED

                List<StatusTransition> transitions = new ArrayList<>();
                transitions.add(new StatusTransition(OrderStatus.DRAFT, t0));
                transitions.add(new StatusTransition(OrderStatus.SUBMITTED, t1));
                transitions.add(new StatusTransition(OrderStatus.STARTED, t2));
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_OPEN, t3));
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_IN_PROGRESS, t4));
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_PARKED, t5));
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_IN_PROGRESS, t6));
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_SUBMITTED, t7));
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL2_OPEN, t8));
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL2_IN_PROGRESS, t9));
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL2_APPROVED, t10));

                Order order = Order.builder().statusTransitions(transitions).build();

                // Expected TAT for AUDIT_REVIEW:
                // T3->T4 (OPEN): 10m
                // T4->T5 (IN_PROGRESS): 10m
                // T5->T6 (PARKED): Skipped
                // T6->T7 (IN_PROGRESS): 10m
                // T7->T8 (SUBMITTED): 10m
                // T8->T9 (OPEN): 10m
                // T9->T10 (IN_PROGRESS): 10m
                // Total: 60m.

                Duration result = tatCalculator.calculateAuditReviewTeamTat(order);
                assertEquals(Duration.ofMinutes(60), result);
        }

        @Test
        void testAuditReviewTeamTat_MultiBlock() {
                LocalDateTime t0 = LocalDateTime.of(2023, 1, 2, 9, 0); // DRAFT

                List<StatusTransition> transitions = new ArrayList<>();
                transitions.add(new StatusTransition(OrderStatus.DRAFT, t0));

                // Block 1: Audit Review Level 1/2
                // Start: 09:10
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL1_OPEN, t0.plusMinutes(10)));
                // End: 09:40
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_LEVEL2_APPROVED, t0.plusMinutes(40)));
                // Block 1 Duration: 30m

                // Intermediary Trading Block (Not counted for Audit)
                transitions.add(new StatusTransition(OrderStatus.TRADING_OPEN, t0.plusMinutes(40)));
                transitions.add(new StatusTransition(OrderStatus.TRADING_IN_PROGRESS, t0.plusMinutes(50)));

                // Block 2: Credit Approval
                // Start: 10:00
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_CREDIT_APPROVAL_LEVEL1_OPEN,
                                t0.plusMinutes(60)));
                // Parked: 10:10
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_CREDIT_APPROVAL_LEVEL1_PARKED,
                                t0.plusMinutes(70)));
                // Resume: 10:40 (Parked 30m)
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_CREDIT_APPROVAL_LEVEL1_IN_PROGRESS,
                                t0.plusMinutes(100)));
                // End: 11:00
                transitions.add(new StatusTransition(OrderStatus.AUDIT_REVIEW_CREDIT_APPROVAL_LEVEL2_APPROVED,
                                t0.plusMinutes(120)));

                // Block 2 Gross: 10:00 -> 11:00 = 60m
                // Block 2 Parked: 30m
                // Block 2 Net: 30m

                // Total TAT = 30m (Block 1) + 30m (Block 2) = 60m

                Order order = Order.builder().statusTransitions(transitions).build();

                Duration result = tatCalculator.calculateAuditReviewTeamTat(order);
                assertEquals(Duration.ofMinutes(60), result);
        }
}
