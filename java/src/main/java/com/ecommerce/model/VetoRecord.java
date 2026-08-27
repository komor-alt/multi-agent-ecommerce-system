package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VetoRecord {
    private AgentId source;
    @Builder.Default
    private List<String> productIds = new ArrayList<>();
    private String reason;
    @Builder.Default
    private boolean handled = false;
}
