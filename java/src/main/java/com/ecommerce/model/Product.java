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
public class Product {
    private String productId;
    private String name;
    private String category;
    private double price;
    private String description;
    private String brand;
    private String sellerId;
    private int stock;
    private List<String> tags;
    private double score;
    private List<String> supportedRegions;
    private String currency;
    private String warehouseRegion;
    private int deliveryDays;
    private String platform;
    private boolean crossBorderEligible;
}
