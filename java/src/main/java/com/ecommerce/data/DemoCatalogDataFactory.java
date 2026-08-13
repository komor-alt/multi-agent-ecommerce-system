package com.ecommerce.data;

import com.ecommerce.model.Product;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class DemoCatalogDataFactory {

    private DemoCatalogDataFactory() {
    }

    public static List<Product> createCatalog() {
        List<Product> products = new ArrayList<>();
        products.addAll(fixedProducts());
        products.addAll(generatedProducts());
        return List.copyOf(products);
    }

    public static Map<String, Object> summarize(List<Product> products) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dataset_type", "deterministic_demo_seed");
        summary.put("production_data", false);
        summary.put("total_products", products.size());
        summary.put("platforms", countBy(products, Product::getPlatform));
        summary.put("countries_or_regions", countSupportedRegions(products));
        summary.put("currencies", countBy(products, Product::getCurrency));
        summary.put("warehouse_regions", countBy(products, Product::getWarehouseRegion));
        summary.put("categories", countBy(products, Product::getCategory));
        summary.put("cross_border", Map.of(
                "eligible", products.stream().filter(Product::isCrossBorderEligible).count(),
                "not_eligible", products.stream().filter(product -> !product.isCrossBorderEligible()).count()
        ));
        summary.put("stock_status", Map.of(
                "out_of_stock", products.stream().filter(product -> product.getStock() <= 0).count(),
                "low_stock_1_to_50", products.stream().filter(product -> product.getStock() > 0 && product.getStock() <= 50).count(),
                "normal_stock", products.stream().filter(product -> product.getStock() > 50).count()
        ));
        summary.put("deterministic_ids", products.stream()
                .map(Product::getProductId)
                .sorted()
                .limit(12)
                .collect(Collectors.toList()));
        summary.put("note", "Mock Shopify Development Store catalog for Agent orchestration, filtering, metrics, and evaluation demos. Replace CommerceConnector for real APIs or DB.");
        return summary;
    }

    private static List<Product> fixedProducts() {
        return List.of(
                product("P001", "Anker 140W GaN Charger", "accessory", 89.0, "Anker", "S-SG-01", 320, List.of("fast-charge", "travel"), List.of("SEA", "SG", "MY"), "SGD", "SG", 2, "shopify", true),
                product("P002", "Sony WH-1000XM6", "headphone", 499.0, "Sony", "S-SG-02", 85, List.of("noise-cancelling", "premium"), List.of("SEA", "SG", "TH"), "SGD", "SG", 3, "shopify", true),
                product("P003", "iPad Air M3", "tablet", 899.0, "Apple", "S-SG-03", 120, List.of("study", "work"), List.of("SEA", "SG"), "SGD", "SG", 2, "shopify", true),
                product("P004", "Logitech MX Master 3S", "accessory", 149.0, "Logitech", "S-MY-01", 42, List.of("office", "wireless"), List.of("SEA", "SG", "MY"), "SGD", "MY", 5, "shopify", true),
                product("P005", "Xiaomi Pad 7 Pro", "tablet", 1699.0, "Xiaomi", "S-MY-02", 260, List.of("value", "entertainment"), List.of("SEA", "MY"), "MYR", "MY", 3, "shopify", true),
                product("P006", "Beauty Travel Kit", "beauty", 39.0, "GlowLab", "S-TH-01", 0, List.of("travel", "gift"), List.of("SEA", "TH", "SG"), "SGD", "TH", 7, "shopify", true),
                product("P007", "Gaming Laptop Aurora X", "laptop", 1899.0, "Mechrevo", "S-CN-01", 35, List.of("gaming", "performance"), List.of("SEA", "VN", "ID"), "SGD", "CN", 9, "shopify", true),
                product("P008", "Restricted Battery Pack", "accessory", 59.0, "VoltGo", "S-CN-02", 500, List.of("power-bank"), List.of("SEA", "SG", "MY"), "SGD", "CN", 10, "shopify", false),
                product("P009", "Shopee Exclusive Earbuds", "headphone", 99.0, "SoundBee", "S-ID-01", 410, List.of("wireless", "value"), List.of("SEA", "ID", "VN"), "SGD", "ID", 8, "shopee", true),
                product("P010", "Vietnam Local Coffee Box", "grocery", 28.0, "Saigon Roast", "S-VN-01", 160, List.of("gift", "local"), List.of("SEA", "VN"), "VND", "VN", 2, "shopify", true)
        );
    }

    private static List<Product> generatedProducts() {
        String[] countries = {"SG", "MY", "TH", "ID", "VN"};
        String[] currencies = {"SGD", "MYR", "THB", "IDR", "VND"};
        String[] categories = {"accessory", "headphone", "tablet", "beauty", "home", "grocery", "fashion", "laptop", "camera", "toy"};
        String[] brands = {"Nova", "Orchid", "Merlion", "Bamboo", "Lotus", "Astra", "Harbor", "PixelWay", "GlowLab", "UrbanKit"};
        String[] warehouses = {"SG", "MY", "TH", "ID", "VN", "CN"};
        List<Product> products = new ArrayList<>();
        for (int i = 11; i <= 60; i++) {
            int index = i - 11;
            String country = countries[index % countries.length];
            String currency = currencies[index % currencies.length];
            String category = categories[index % categories.length];
            String brand = brands[index % brands.length];
            String warehouse = warehouses[index % warehouses.length];
            boolean shopify = index % 7 != 0;
            boolean eligible = index % 9 != 0;
            int stock = switch (index % 8) {
                case 0 -> 0;
                case 1 -> 18;
                case 2 -> 48;
                case 3 -> 76;
                default -> 140 + index * 7;
            };
            List<String> supported = index % 6 == 0
                    ? List.of(country)
                    : List.of("SEA", country, countries[(index + 1) % countries.length]);
            products.add(product(
                    String.format("P%03d", i),
                    brand + " " + displayName(category) + " " + country,
                    category,
                    priceFor(category, index),
                    brand,
                    "S-" + warehouse + "-" + String.format("%02d", index + 1),
                    stock,
                    tagsFor(category, index),
                    supported,
                    currency,
                    warehouse,
                    2 + (index % 9),
                    shopify ? "shopify" : "shopee",
                    eligible
            ));
        }
        return products;
    }

    private static String displayName(String category) {
        return switch (category) {
            case "accessory" -> "Travel Adapter";
            case "headphone" -> "Wireless Audio";
            case "tablet" -> "Study Tablet";
            case "beauty" -> "Beauty Set";
            case "home" -> "Smart Home Kit";
            case "grocery" -> "Local Gift Box";
            case "fashion" -> "Lightweight Jacket";
            case "laptop" -> "Creator Laptop";
            case "camera" -> "Vlog Camera";
            default -> "Kids Learning Kit";
        };
    }

    private static double priceFor(String category, int index) {
        double base = switch (category) {
            case "laptop" -> 1299;
            case "tablet" -> 499;
            case "headphone" -> 129;
            case "camera" -> 699;
            case "beauty", "grocery", "toy" -> 39;
            default -> 79;
        };
        return base + (index % 5) * 17;
    }

    private static List<String> tagsFor(String category, int index) {
        List<String> tags = new ArrayList<>();
        tags.add(category);
        tags.add(index % 2 == 0 ? "travel" : "local");
        if (index % 5 == 0) {
            tags.add("premium");
        }
        if (index % 4 == 0) {
            tags.add("new");
        }
        return List.copyOf(tags);
    }

    private static Map<String, Long> countSupportedRegions(List<Product> products) {
        return products.stream()
                .flatMap(product -> product.getSupportedRegions() == null ? List.<String>of().stream() : product.getSupportedRegions().stream())
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
    }

    private static Map<String, Long> countBy(List<Product> products, java.util.function.Function<Product, String> classifier) {
        return products.stream()
                .sorted(Comparator.comparing(Product::getProductId))
                .collect(Collectors.groupingBy(product -> String.valueOf(classifier.apply(product)), LinkedHashMap::new, Collectors.counting()));
    }

    private static Product product(String id, String name, String category, double price, String brand, String sellerId,
                                   int stock, List<String> tags, List<String> supportedRegions, String currency,
                                   String warehouseRegion, int deliveryDays, String platform, boolean crossBorderEligible) {
        return Product.builder()
                .productId(id)
                .name(name)
                .category(category)
                .price(price)
                .description("Deterministic demo product for cross-border recommendation validation.")
                .brand(brand)
                .sellerId(sellerId)
                .stock(stock)
                .tags(tags)
                .supportedRegions(supportedRegions)
                .currency(currency)
                .warehouseRegion(warehouseRegion)
                .deliveryDays(deliveryDays)
                .platform(platform)
                .crossBorderEligible(crossBorderEligible)
                .build();
    }
}
