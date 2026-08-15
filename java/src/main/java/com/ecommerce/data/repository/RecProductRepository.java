package com.ecommerce.data.repository;

import com.ecommerce.data.entity.RecProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RecProductRepository extends JpaRepository<RecProductEntity, String> {

    @Query("""
            select p from RecProductEntity p
            where lower(p.platform) = lower(:platform)
              and p.crossBorderEligible = true
              and p.stock > 0
              and upper(p.status) = 'ACTIVE'
              and (upper(p.supportedCountries) like upper(concat('%', :country, '%'))
                   or upper(p.supportedCountries) like upper(concat('%', :region, '%')))
              and upper(p.currency) = upper(:currency)
            order by p.deliveryDays asc, p.stock desc
            """)
    List<RecProductEntity> findByMarket(@Param("platform") String platform,
                                        @Param("country") String country,
                                        @Param("region") String region,
                                        @Param("currency") String currency);

    /** Compatibility overload for callers that only have a country. */
    default List<RecProductEntity> findByMarket(String platform, String country, String currency) {
        return findByMarket(platform, country, "SEA", currency);
    }

    Optional<RecProductEntity> findByProductId(String productId);

    List<RecProductEntity> findByProductIdIn(List<String> productIds);

    long count();
}
