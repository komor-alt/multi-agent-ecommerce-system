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
            "search_cross_border_products",
            "rerank_products",
            "check_fulfillment_inventory",
            "filter_products",
            "generate_localized_copy",
            "final_answer"
    );
}
