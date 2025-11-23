package com.trading.model;

import lombok.Builder;
import java.util.Set;

@Builder
public record ActivityBlock(
        Set<OrderStatus> statuses,
        OrderStatus entryStatus,
        OrderStatus firstInProgressStatus) {
}
