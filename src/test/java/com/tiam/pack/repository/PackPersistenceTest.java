package com.tiam.pack.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.tiam.pack.domain.PackDownloadEvent;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackTokens;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
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
    @Autowired PackDownloadEventRepository downloads;
    @Autowired TestEntityManager em;

    @Test
    void order_roundTripsThroughTheRealSchema() {
        PackOrder saved = orders.saveAndFlush(order("ana@example.com"));

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
        PackOrder saved = orders.saveAndFlush(order("lock@example.com"));

        assertThat(orders.findWithLockById(saved.getId())).isPresent();
    }

    @Test
    void findReusable_returnsTheNewestMatchingPendingOrderWithACheckoutLink() {
        PackOrder older = checkout("reuse@example.com");
        PackOrder newer = checkout("reuse@example.com");
        age(older, 5);
        age(newer, 1);
        // none of these may match
        age(checkout("someone-else@example.com"), 1);
        PackOrder noLink = order("reuse@example.com");
        orders.saveAndFlush(noLink);
        PackOrder tooOld = checkout("reuse@example.com");
        age(tooOld, 40);
        PackOrder otherEdition = checkout("reuse@example.com");
        otherEdition.setEdition(PackEdition.PROFESIONALES);
        orders.saveAndFlush(otherEdition);
        PackOrder withGames = checkout("reuse@example.com");
        withGames.setWithGames(true);
        orders.saveAndFlush(withGames);
        PackOrder paid = checkout("reuse@example.com");
        paid.setStatus(PackOrderStatus.PAID);
        orders.saveAndFlush(paid);
        em.clear();

        List<PackOrder> reusable = orders.findReusable("reuse@example.com", PackEdition.FAMILIAS, false, false,
                PackOrderStatus.PENDING, Instant.now().minus(15, ChronoUnit.MINUTES));

        assertThat(reusable).extracting(PackOrder::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void countByEmailAndStatusAndCreatedAtAfter_countsOnlyRecentOrdersInThatStatus() {
        orders.saveAndFlush(order("count@example.com"));
        orders.saveAndFlush(order("count@example.com"));
        PackOrder old = orders.saveAndFlush(order("count@example.com"));
        age(old, 120);
        PackOrder failed = order("count@example.com");
        failed.setStatus(PackOrderStatus.FAILED);
        orders.saveAndFlush(failed);
        em.clear();

        long pending = orders.countByEmailAndStatusAndCreatedAtAfter("count@example.com", PackOrderStatus.PENDING,
                Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(pending).isEqualTo(2);
    }

    @Test
    void findStalePending_returnsPendingOrdersWithACheckoutInsideTheWindow() {
        PackOrder stale = checkout("stale@example.com");
        age(stale, 60);
        PackOrder tooRecent = checkout("stale@example.com");
        age(tooRecent, 2);
        PackOrder tooOld = checkout("stale@example.com");
        age(tooOld, 60 * 24 * 30);
        PackOrder neverReachedMp = orders.saveAndFlush(order("stale@example.com"));
        age(neverReachedMp, 60);
        PackOrder paid = checkout("stale@example.com");
        paid.setStatus(PackOrderStatus.PAID);
        orders.saveAndFlush(paid);
        age(paid, 60);
        em.clear();
        Instant now = Instant.now();

        List<PackOrder> found = orders.findStalePending(PackOrderStatus.PENDING,
                now.minus(14, ChronoUnit.DAYS), now.minus(10, ChronoUnit.MINUTES));

        assertThat(found).extracting(PackOrder::getId).contains(stale.getId())
                .doesNotContain(tooRecent.getId(), tooOld.getId(), neverReachedMp.getId(), paid.getId());
    }

    @Test
    void markEmailSent_recordsTheDeliveryOnlyOnce() {
        PackOrder paid = paid("mail@example.com", 60);
        em.clear();
        Instant firstAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        int first = orders.markEmailSent(paid.getId(), firstAt);
        int second = orders.markEmailSent(paid.getId(), firstAt.plusSeconds(60));
        em.clear();

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
        assertThat(orders.findById(paid.getId()).orElseThrow().getEmailSentAt()).isEqualTo(firstAt);
    }

    @Test
    void findIdsAwaitingAccessEmail_returnsPaidOrdersWithoutAnEmailInsideTheWindow() {
        PackOrder waiting = paid("wait@example.com", 60);
        PackOrder justPaid = paid("wait@example.com", 1);
        PackOrder tooOld = paid("wait@example.com", 60 * 24 * 5);
        PackOrder alreadySent = paid("wait@example.com", 60);
        alreadySent.setEmailSentAt(Instant.now());
        orders.saveAndFlush(alreadySent);
        PackOrder pending = orders.saveAndFlush(order("wait@example.com"));
        em.clear();
        Instant now = Instant.now();

        List<Long> ids = orders.findIdsAwaitingAccessEmail(PackOrderStatus.PAID,
                now.minus(72, ChronoUnit.HOURS), now.minus(5, ChronoUnit.MINUTES));

        assertThat(ids).contains(waiting.getId())
                .doesNotContain(justPaid.getId(), tooOld.getId(), alreadySent.getId(), pending.getId());
    }

    @Test
    void downloadEvents_areCountedPerOrderAndPerWindow() {
        PackOrder mine = paid("dl@example.com", 60);
        PackOrder other = paid("dl-other@example.com", 60);
        event(mine, "pack-completo-a4");
        event(mine, "cuadernillos-a4");
        PackDownloadEvent old = event(mine, "pack-completo-carta");
        event(other, "pack-completo-a4");
        em.getEntityManager()
                .createNativeQuery("update pack_download_events set created_at = now() - interval '2 days' where id = ?1")
                .setParameter(1, old.getId())
                .executeUpdate();
        em.clear();

        assertThat(downloads.countByOrderId(mine.getId())).isEqualTo(3);
        assertThat(downloads.countByOrderIdAndCreatedAtAfter(mine.getId(), Instant.now().minus(24, ChronoUnit.HOURS)))
                .isEqualTo(2);
        assertThat(downloads.countByOrderId(other.getId())).isEqualTo(1);
    }

    private PackDownloadEvent event(PackOrder order, String fileKey) {
        PackDownloadEvent event = new PackDownloadEvent();
        event.setOrderId(order.getId());
        event.setFileKey(fileKey);
        return downloads.saveAndFlush(event);
    }

    private PackOrder paid(String email, long paidMinutesAgo) {
        PackOrder order = order(email);
        order.setStatus(PackOrderStatus.PAID);
        order.setPaidAt(Instant.now().minus(paidMinutesAgo, ChronoUnit.MINUTES));
        order.setAccessExpiresAt(Instant.now().plus(90, ChronoUnit.DAYS));
        order.setMpPaymentId("PAY-" + PackTokens.newToken());
        return orders.saveAndFlush(order);
    }

    private PackOrder checkout(String email) {
        PackOrder order = order(email);
        order.setInitPoint("https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=X");
        order.setMpPreferenceId("X");
        return orders.saveAndFlush(order);
    }

    /** Moves created_at back in time (the column is filled by Hibernate on insert). */
    private void age(PackOrder order, long minutes) {
        em.getEntityManager()
                .createNativeQuery("update pack_orders set created_at = now() - make_interval(mins => ?1) where id = ?2")
                .setParameter(1, (int) minutes)
                .setParameter(2, order.getId())
                .executeUpdate();
        em.clear();
    }

    static PackOrder order(String email) {
        PackOrder order = new PackOrder();
        order.setAccessToken(PackTokens.newToken());
        order.setEdition(PackEdition.FAMILIAS);
        order.setBuyerName("Ana");
        order.setEmail(email);
        order.setPackAmount(new BigDecimal("14990.00"));
        order.setTotalAmount(new BigDecimal("14990.00"));
        return order;
    }
}
