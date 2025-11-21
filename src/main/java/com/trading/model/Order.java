package com.trading.model;

import lombok.Builder;

import java.util.List;

@Builder
public record Order(
    String orderId,
    OrderStatus status,
    List<StatusTransition> statusTransitions
) {}
