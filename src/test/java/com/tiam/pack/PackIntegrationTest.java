package com.tiam.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import com.tiam.pack.config.PackSalesReadiness;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackTokens;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.pack.service.PackOrderService;
import com.tiam.pack.service.PaymentSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The tests that need the WHOLE application: the real Spring context (a wiring error, a duplicated bean or a cycle
 * would otherwise only show up when deploying, and would take the Desafío down with it), the real security filter
 * chain and JSON settings, and the real Postgres row locks. Everything else in the pack is tested against mocks.
 * Skipped when Docker is not available.
 *
 * <p>The email provider is the only thing replaced (a recording mock); the background jobs are switched off so the
 * counts below cannot be disturbed by a scheduler tick.
 */
@SpringBootTest(properties = {
        "tiam.jwt.secret=integration-test-secret-integration-test-secret-integration",
        "tiam.pack.download-base-url=https://tiamdigital.com/pack/descargas",
        "tiam.pack.landing-base-url=https://tiamdigital.com",
        "tiam.pack.reconciliation.enabled=false",
        "tiam.pack.email-retry.enabled=false",
        "tiam.challenge.reconciliation.enabled=false",
        "tiam.challenge.email-retry.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PackIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final AtomicLong PHONES = new AtomicLong(5_492_210_000_000L);

    @MockBean EmailSender emailSender;

    @Autowired MockMvc mvc;
    @Autowired PackOrderService packOrderService;
    @Autowired PackOrderRepository orders;
    @Autowired PackSalesReadiness readiness;
    @Autowired JdbcTemplate jdbc;

    // --- the application boots, as it will on Render --------------------------------------------------------------

    @Test
    void theRealApplicationContext_boots_withAllMigrationsApplied() {
        Integer latest = jdbc.queryForObject("select max(version::int) from flyway_schema_history where success", Integer.class);

        assertThat(latest).isGreaterThanOrEqualTo(14);
        assertThat(readiness).isNotNull();
    }

    @Test
    void withTheDefaultConfiguration_theShopIsNotReadyToSell_soCheckoutWouldRefuseEvenWithSalesSwitchedOn() {
        // no R2 credentials, an email sender that delivers nothing (the mock), no owner address
        assertThat(readiness.problems()).anyMatch(p -> p.contains("R2")).anyMatch(p -> p.contains("PACK_OWNER_NOTIFY_TO"));
        assertThat(readiness.isReady()).isFalse();
    }

    // --- the real filter chain and JSON settings ---------------------------------------------------------------------

    @Test
    void checkout_withSalesOff_redirectsBackToTheLandingWithTheClosedCode() throws Exception {
        mvc.perform(post("/packs/checkout").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "ana@example.com").param("nombre", "Ana Gómez").param("edicion", "profesionales"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://tiamdigital.com/pack/profesionales?error=cerrado#comprar"));
    }

    @Test
    void checkout_fromAnUnlistedOrigin_getsTheFriendlyRedirectThroughTheRealFilterChain() throws Exception {
        mvc.perform(post("/packs/checkout").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("Origin", "https://tiam-preview-1234.vercel.app")
                        .param("email", "ana@example.com").param("nombre", "Ana Gómez").param("edicion", "familias"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://tiamdigital.com/pack/familias?error=origen#comprar"));
    }

    @Test
    void unknownToken_isAUniform404_whoseBodyNeverRepeatsTheToken() throws Exception {
        String token = PackTokens.newToken();

        String body = mvc.perform(get("/packs/orders/" + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.path").value("/packs/orders/{token}"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(token);
    }

    @Test
    void aPendingOrder_isShownAsPendingWithItsEdition() throws Exception {
        PackOrder order = orders.saveAndFlush(order("pending-view@example.com", "5492210000001", false, PackOrderStatus.PENDING));

        mvc.perform(get("/packs/orders/" + order.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.edition").value("familias"))
                .andExpect(jsonPath("$.data.total").doesNotExist())
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void aPaidOrder_isServedWithIsoDatesAndTheTotal_asTheFrontendExpects() throws Exception {
        PackOrder order = order("paid-view@example.com", "5492210000002", false, PackOrderStatus.PAID);
        order.setAccessExpiresAt(Instant.parse("2027-01-12T15:00:00Z"));
        order.setPaidAt(Instant.parse("2026-10-14T15:00:00Z"));
        orders.saveAndFlush(order);

        mvc.perform(get("/packs/orders/" + order.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.expiresAt").value("2027-01-12T15:00:00Z"))
                .andExpect(jsonPath("$.data.total").value(24990))
                .andExpect(jsonPath("$.data.items[0].code").value("PACK"))
                .andExpect(jsonPath("$.data.games").doesNotExist());
    }

    @Test
    void anOrderHeldForReview_isShownAsReview() throws Exception {
        PackOrder order = orders.saveAndFlush(order("review-view@example.com", "5492210000003", false, PackOrderStatus.REVIEW));

        mvc.perform(get("/packs/orders/" + order.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVIEW"));
    }

    @Test
    void theRegretForm_returnsTheReceiptAsJson_andIsPublic() throws Exception {
        mvc.perform(post("/packs/regret").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Ana Gómez\",\"email\":\"integration-regret@example.com\",\"reason\":\"No lo necesito\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reference").value(org.hamcrest.Matchers.matchesPattern("ARR-\\d{6}")))
                .andExpect(jsonPath("$.data.receivedAt").value(org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*Z")));
    }

    @Test
    void theProtectedParts_ofTheBackend_stillRequireALogin() throws Exception {
        mvc.perform(get("/exercises")).andExpect(result ->
                assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    // --- concurrency against the real database ---------------------------------------------------------------------------

    @Test
    void theSamePaymentNotifiedByEightThreadsAtOnce_deliversExactlyOnce() throws Exception {
        when(emailSender.isEnabled()).thenReturn(true);
        when(emailSender.send(any())).thenReturn(true);
        String phone = nextPhone();
        PackOrder order = orders.saveAndFlush(order("concurrent-once@example.com", phone, true, PackOrderStatus.PENDING));
        PaymentSnapshot payment = approved(order, "PAY-ONCE-" + order.getId());

        runConcurrently(8, () -> packOrderService.onPayment(order.getId(), payment));

        PackOrder paid = orders.findById(order.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(PackOrderStatus.PAID);
        assertThat(paid.getChallengePurchaseId()).isNotNull();
        assertThat(challengeMonths(phone)).as("one Desafío month, not eight").hasSize(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(packEmailsSentTo("concurrent-once@example.com")).hasSize(1));
        Thread.sleep(500); // a duplicate would arrive by now
        assertThat(packEmailsSentTo("concurrent-once@example.com")).as("one access email, not eight").hasSize(1);
    }

    @Test
    void twoPacksWithGamesForTheSamePhone_paidAtTheSameTime_neverReceiveTheSameMonth() throws Exception {
        for (int round = 0; round < 12; round++) {
            String phone = nextPhone();
            PackOrder first = orders.saveAndFlush(order("race-a-" + round + "@example.com", phone, true, PackOrderStatus.PENDING));
            PackOrder second = orders.saveAndFlush(order("race-b-" + round + "@example.com", phone, true, PackOrderStatus.PENDING));
            List<Runnable> tasks = List.of(
                    () -> packOrderService.onPayment(first.getId(), approved(first, "PAY-A-" + first.getId())),
                    () -> packOrderService.onPayment(second.getId(), approved(second, "PAY-B-" + second.getId())));

            runConcurrently(tasks);

            assertThat(challengeMonths(phone)).as("round %d: months granted to the same phone", round).containsExactly(1, 2);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------

    private List<EmailMessage> packEmailsSentTo(String email) {
        return mockingDetails(emailSender).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("send"))
                .map(invocation -> (EmailMessage) invocation.getArgument(0))
                .filter(message -> message.to().equals(email) && message.subject().startsWith("Tu Pack TIAM"))
                .toList();
    }

    private List<Integer> challengeMonths(String phone) {
        return jdbc.queryForList("select challenge_month from challenge_purchases where phone = ? order by challenge_month",
                Integer.class, phone);
    }

    private static String nextPhone() {
        return String.valueOf(PHONES.incrementAndGet());
    }

    private static PaymentSnapshot approved(PackOrder order, String paymentId) {
        return new PaymentSnapshot(paymentId, "approved", order.getTotalAmount(), "ARS", "pack:" + order.getId());
    }

    private static PackOrder order(String email, String phone, boolean withGames, PackOrderStatus status) {
        PackOrder order = new PackOrder();
        order.setAccessToken(PackTokens.newToken());
        order.setStatus(status);
        order.setEdition(PackEdition.FAMILIAS);
        order.setWithGames(withGames);
        order.setCurrency("ARS");
        order.setPackAmount(new BigDecimal("14990"));
        order.setGamesAmount(withGames ? new BigDecimal("10000") : BigDecimal.ZERO);
        order.setTotalAmount(withGames ? new BigDecimal("24990") : new BigDecimal("24990"));
        order.setBuyerName("Ana Gómez");
        order.setEmail(email);
        order.setPhone(phone);
        return order;
    }

    private static void runConcurrently(int threads, Runnable task) throws Exception {
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(task);
        }
        runConcurrently(tasks);
    }

    /** Starts every task at the same instant and waits for all of them; any exception fails the test. */
    private static void runConcurrently(List<Runnable> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                task.run();
                return null;
            }));
        }
        ready.await();
        go.countDown();
        try {
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
