package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationCheck {
    private String name;
    private boolean passed;
    private String detail;
    @Builder.Default
    private double weight = 1.0;
}
