package com.tiam.pack.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackTokens;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the real Flyway migrations and Hibernate's schema validation against Postgres, so an entity
 * that drifts from V12 (or a broken JPQL query) fails here instead of at boot in production.
 * Skipped when Docker is not available.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class PackPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired PackOrderRepository orders;

    @Test
    void order_roundTripsThroughTheRealSchema() {
        PackOrder saved = orders.saveAndFlush(order("tok-" + PackTokens.newToken()));

        PackOrder found = orders.findByAccessTokenAndActivoTrue(saved.getAccessToken()).orElseThrow();

        assertThat(found.getId()).isNotNull();
        assertThat(found.getStatus()).isEqualTo(PackOrderStatus.PENDING);
        assertThat(found.getEdition()).isEqualTo(PackEdition.FAMILIAS);
        assertThat(found.getTotalAmount()).isEqualByComparingTo("14990");
        assertThat(found.getCatalogVersion()).isEqualTo("v1");
        assertThat(found.isActivo()).isTrue();
    }

    @Test
    void findWithLockById_returnsTheOrder() {
        PackOrder saved = orders.saveAndFlush(order("tok-" + PackTokens.newToken()));

        assertThat(orders.findWithLockById(saved.getId())).isPresent();
    }

    static PackOrder order(String token) {
        PackOrder order = new PackOrder();
        order.setAccessToken(token.substring(0, Math.min(token.length(), 64)));
        order.setEdition(PackEdition.FAMILIAS);
        order.setBuyerName("Ana");
        order.setEmail("ana@example.com");
        order.setPackAmount(new BigDecimal("14990.00"));
        order.setTotalAmount(new BigDecimal("14990.00"));
        return order;
    }
}
