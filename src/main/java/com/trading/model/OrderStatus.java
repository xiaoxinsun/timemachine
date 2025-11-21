package com.trading.model;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public enum OrderStatus {
    DRAFT,
    SUBMITTED,
    STARTED,
    AUDIT_REVIEW_LEVEL1_OPEN,
    AUDIT_REVIEW_LEVEL1_IN_PROGRESS,
    AUDIT_REVIEW_LEVEL1_PARKED,
    AUDIT_REVIEW_LEVEL1_SUBMITTED,
    AUDIT_REVIEW_LEVEL2_OPEN,
    AUDIT_REVIEW_LEVEL2_IN_PROGRESS,
    AUDIT_REVIEW_LEVEL2_PARKED,
    AUDIT_REVIEW_LEVEL2_APPROVED,
    TRADING_OPEN,
    TRADING_IN_PROGRESS,
    TRADING_PARKED,
    TRADING_SUBMITTED,
    COMPLETED;

    private static final Set<OrderStatus> AUDIT_REVIEW_STATUSES = Arrays.stream(values())
            .filter(s -> s.name().startsWith("AUDIT_REVIEW"))
            .collect(Collectors.toSet());

    private static final Set<OrderStatus> TRADING_STATUSES = Arrays.stream(values())
            .filter(s -> s.name().startsWith("TRADING"))
            .collect(Collectors.toSet());

    public boolean isAuditReviewStatus() {
        return AUDIT_REVIEW_STATUSES.contains(this);
    }

    public boolean isTradingStatus() {
        return TRADING_STATUSES.contains(this);
    }
}
