package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolLoopConfig {
    @Builder.Default
    private int maxSteps = 8;
    @Builder.Default
    private List<String> toolWhitelist = List.of(
            "get_user_profile",
            "load_campaign_constraints",
            "get_recent_orders",
            "search_products",
            "check_fulfillment",
            "check_inventory",
            "rerank",
            "filter_products",
            "generate_localized_copy",
            "generate_retention_copy",
            "final_answer"
    );
}
