package com.ecommerce.data;

import com.ecommerce.data.entity.RecProductEntity;
import com.ecommerce.data.repository.RecInventoryRepository;
import com.ecommerce.data.repository.RecOrderRepository;
import com.ecommerce.data.repository.RecProductRepository;
import com.ecommerce.data.repository.RecUserEventRepository;
import com.ecommerce.data.repository.RecUserRepository;
import com.ecommerce.model.RecommendationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationDataServiceTest {

    @Test
    void rerecallOverfetchesThenExcludesVetoedProductsWithoutChangingMarketFilters() {
        RecProductRepository products = mock(RecProductRepository.class);
        RecInventoryRepository inventory = mock(RecInventoryRepository.class);
        RecUserRepository users = mock(RecUserRepository.class);
        RecUserEventRepository events = mock(RecUserEventRepository.class);
        RecOrderRepository orders = mock(RecOrderRepository.class);
        SemanticProductSearchService semantic = mock(SemanticProductSearchService.class);
        List<RecProductEntity> marketRows = List.of(
                product("p-veto"), product("p-2"), product("p-3"), product("p-4"));
        when(products.findByMarket("shopify", "SG", "SEA", "SGD")).thenReturn(marketRows);
        when(semantic.retrieve(anyList(), anySet(), anyString(), anyInt())).thenReturn(
                new SemanticProductSearchService.RetrievalResult(
                        marketRows, "postgresql+qwen+pgvector", true, null));

        RecommendationDataService service = new RecommendationDataService(
                products, inventory, users, events, orders, semantic);
        RecommendationDataService.ProductSearchResult result = service.searchProductsWithSource(
                RecommendationRequest.builder().userId("u-1").platform("shopify")
                        .country("SG").region("SEA").currency("SGD").build(),
                Set.of("electronics"), 3, Set.of("p-veto"));

        assertThat(result.products()).extracting("productId").containsExactly("p-2", "p-3", "p-4");
        verify(products).findByMarket("shopify", "SG", "SEA", "SGD");
        verify(semantic).retrieve(eq(marketRows), eq(Set.of("electronics")), anyString(), eq(4));
    }

    private static RecProductEntity product(String productId) {
        return RecProductEntity.builder()
                .productId(productId)
                .name(productId)
                .category("electronics")
                .price(20)
                .currency("SGD")
                .stock(10)
                .description("test product")
                .platform("shopify")
                .warehouseRegion("SG")
                .deliveryDays(2)
                .crossBorderEligible(true)
                .supportedCountries("SG,SEA")
                .tags("test")
                .build();
    }
}
