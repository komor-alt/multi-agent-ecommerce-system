package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** Fulfillment summary of the recommended products (warehouse, SLA, restrictions). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FulfillmentContext {
    private String warehouseRegion;
    private int deliveryDays;
    private String status;
    @Builder.Default
    private List<Map<String, Object>> items = List.of();
    @Builder.Default
    private List<Map<String, Object>> restrictions = List.of();
}
