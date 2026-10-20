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

import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
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
 * The «Cuadernos TIAM» product through the WHOLE application: real Spring context, real filter chain, JSON settings and
 * Postgres. Skipped when Docker is not available (see {@link PackIntegrationTest}).
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
class CuadernosIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final AtomicLong PHONES = new AtomicLong(5_492_230_000_000L);

    @MockBean EmailSender emailSender;

    @Autowired MockMvc mvc;
    @Autowired PackOrderService packOrderService;
    @Autowired PackOrderRepository orders;
    @Autowired ChallengePurchaseRepository challengePurchases;
    @Autowired JdbcTemplate jdbc;

    // --- the checkout through the real filter chain ----------------------------------------------------------------

    @Test
    void checkout_withCuadernosSalesOff_redirectsToTheCuadernosLandingWithTheClosedCode() throws Exception {
        mvc.perform(post("/packs/checkout").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "ana@example.com").param("nombre", "Ana Gómez").param("edicion", "cuadernos"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://tiamdigital.com/cuadernos?error=cerrado#comprar"));
    }

    @Test
    void checkout_fromAnUnlistedOrigin_isSentBackToTheCuadernosLanding() throws Exception {
        mvc.perform(post("/packs/checkout").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("Origin", "https://tiam-preview-1234.vercel.app")
                        .param("email", "ana@example.com").param("nombre", "Ana Gómez").param("edicion", "cuadernos"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "https://tiamdigital.com/cuadernos?error=origen#comprar"));
    }

    // --- persistence --------------------------------------------------------------------------------------------------

    @Test
    void aCuadernosOrder_isStoredByNameInTheExistingColumn_andReadBackAsTheEnum() {
        PackOrder saved = orders.saveAndFlush(order("stored@example.com", nextPhone(), false, PackOrderStatus.PENDING));

        String stored = jdbc.queryForObject("select edition from pack_orders where id = ?", String.class, saved.getId());

        assertThat(stored).isEqualTo("CUADERNOS");
        assertThat(orders.findById(saved.getId()).orElseThrow().getEdition()).isEqualTo(PackEdition.CUADERNOS);
    }

    @Test
    void findReusable_doesNotMixTheTwoProducts() {
        PackOrder pending = order("reuse@example.com", nextPhone(), false, PackOrderStatus.PENDING);
        pending.setInitPoint("https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=X");
        orders.saveAndFlush(pending);

        assertThat(orders.findReusable("reuse@example.com", PackEdition.CUADERNOS, false, false, PackOrderStatus.PENDING,
                Instant.now().minus(Duration.ofMinutes(15)))).hasSize(1);
        assertThat(orders.findReusable("reuse@example.com", PackEdition.FAMILIAS, false, false, PackOrderStatus.PENDING,
                Instant.now().minus(Duration.ofMinutes(15)))).isEmpty();
    }

    // --- the downloads page ----------------------------------------------------------------------------------------------

    @Test
    void aPaidCuadernosOrder_isServedWithItsTenFiles_asTheFrontendExpects() throws Exception {
        PackOrder order = order("paid-view@example.com", nextPhone(), false, PackOrderStatus.PAID);
        order.setAccessExpiresAt(Instant.parse("2027-01-12T15:00:00Z"));
        order.setPaidAt(Instant.parse("2026-10-14T15:00:00Z"));
        orders.saveAndFlush(order);

        mvc.perform(get("/packs/orders/" + order.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.edition").value("cuadernos"))
                .andExpect(jsonPath("$.data.total").value(20000))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("PACK"))
                .andExpect(jsonPath("$.data.items[0].title").value("Cuadernos TIAM"))
                .andExpect(jsonPath("$.data.items[0].files.length()").value(10))
                .andExpect(jsonPath("$.data.items[0].files[0].key").value("coleccion-completa-a4"))
                .andExpect(jsonPath("$.data.items[0].files[0].paper").value("A4"))
                .andExpect(jsonPath("$.data.items[0].files[0].sizeMb").value(27))
                .andExpect(jsonPath("$.data.items[0].files[5].paper").value("Carta"))
                .andExpect(jsonPath("$.data.games").doesNotExist());
    }

    @Test
    void aPendingCuadernosOrder_isShownAsPendingWithItsEdition() throws Exception {
        PackOrder order = orders.saveAndFlush(order("pending-view@example.com", nextPhone(), false, PackOrderStatus.PENDING));

        mvc.perform(get("/packs/orders/" + order.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.edition").value("cuadernos"));
    }

    @Test
    void aSignedLinkIsIssuedForACuadernosFile_andRefusedForAPackFile() throws Exception {
        PackOrder order = order("link@example.com", nextPhone(), false, PackOrderStatus.PAID);
        order.setAccessExpiresAt(Instant.now().plus(Duration.ofDays(30)));
        order.setPaidAt(Instant.now());
        orders.saveAndFlush(order);

        mvc.perform(post("/packs/orders/" + order.getAccessToken() + "/files/cuaderno-2-a4/link"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.containsString("cuadernos")))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300));
        mvc.perform(post("/packs/orders/" + order.getAccessToken() + "/files/pack-completo-a4/link"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/packs/orders/" + order.getAccessToken() + "/files/extra-a4/link"))
                .andExpect(status().isNotFound());
    }

    // --- paying: the same machinery, the Cuadernos wording ----------------------------------------------------------------

    @Test
    void aCuadernosOrderWithTheDesafioOffer_paidByManyNotificationsAtOnce_deliversOneMonthAndOneEmail() throws Exception {
        when(emailSender.isEnabled()).thenReturn(true);
        when(emailSender.send(any())).thenReturn(true);
        String phone = nextPhone();
        PackOrder order = orders.saveAndFlush(order("offer@example.com", phone, true, PackOrderStatus.PENDING));
        PaymentSnapshot payment = approved(order, "PAY-C-" + order.getId());

        runConcurrently(8, () -> packOrderService.onPayment(order.getId(), payment));

        PackOrder paid = orders.findById(order.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(PackOrderStatus.PAID);
        assertThat(paid.getTotalAmount()).isEqualByComparingTo("35000");
        assertThat(paid.getChallengePurchaseId()).isNotNull();
        assertThat(challengeMonths(phone)).as("exactly one Desafío month").containsExactly(1);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(cuadernosEmailsSentTo("offer@example.com")).hasSize(1));
        Thread.sleep(500);
        assertThat(cuadernosEmailsSentTo("offer@example.com")).as("one access email, not eight").hasSize(1);
        EmailMessage email = cuadernosEmailsSentTo("offer@example.com").get(0);
        assertThat(email.subject()).isEqualTo("Tus Cuadernos TIAM están listos: descargá tus PDF");
        assertThat(email.html()).contains("Tu primer mes de juegos").contains("https://www.tiamdigital.com/email/cuadernos.jpg");

        mvc.perform(get("/packs/orders/" + paid.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(35000))
                .andExpect(jsonPath("$.data.games.link").isNotEmpty())
                .andExpect(jsonPath("$.data.games.monthsIncluded").value(1))
                .andExpect(jsonPath("$.data.games.startsVia").value("whatsapp"))
                .andExpect(jsonPath("$.data.games.nextMonthsPaidSeparately").value(true))
                .andExpect(jsonPath("$.data.games.nextMonthPriceArs").value(20000));
    }

    @Test
    void aBuyerWhoAlreadyHasMonthOne_getsMonthTwoWithTheCuadernos() {
        when(emailSender.isEnabled()).thenReturn(true);
        when(emailSender.send(any())).thenReturn(true);
        String phone = nextPhone();
        PackOrder firstOrder = orders.saveAndFlush(order("m1@example.com", phone, true, PackOrderStatus.PENDING));
        packOrderService.onPayment(firstOrder.getId(), approved(firstOrder, "PAY-M1-" + firstOrder.getId()));
        PackOrder secondOrder = orders.saveAndFlush(order("m2@example.com", phone, true, PackOrderStatus.PENDING));

        packOrderService.onPayment(secondOrder.getId(), approved(secondOrder, "PAY-M2-" + secondOrder.getId()));

        assertThat(challengeMonths(phone)).containsExactly(1, 2);
    }

    @Test
    void aCuadernosAndAPackOrderWithGames_forTheSamePhone_paidTogether_neverReceiveTheSameMonth() throws Exception {
        for (int round = 0; round < 8; round++) {
            String phone = nextPhone();
            PackOrder cuadernos = orders.saveAndFlush(order("mix-c-" + round + "@example.com", phone, true, PackOrderStatus.PENDING));
            PackOrder pack = order("mix-p-" + round + "@example.com", phone, true, PackOrderStatus.PENDING);
            pack.setEdition(PackEdition.FAMILIAS);
            pack.setPackAmount(new BigDecimal("14990"));
            pack.setGamesAmount(new BigDecimal("10000"));
            pack.setTotalAmount(new BigDecimal("24990"));
            PackOrder packSaved = orders.saveAndFlush(pack);

            runConcurrently(List.of(
                    () -> packOrderService.onPayment(cuadernos.getId(), approved(cuadernos, "PAY-X-" + cuadernos.getId())),
                    () -> packOrderService.onPayment(packSaved.getId(), approved(packSaved, "PAY-Y-" + packSaved.getId()))));

            assertThat(challengeMonths(phone)).as("round %d", round).containsExactly(1, 2);
        }
    }

    @Test
    void whenNoMonthIsLeft_theCuadernosAreStillDelivered() {
        when(emailSender.isEnabled()).thenReturn(true);
        when(emailSender.send(any())).thenReturn(true);
        String phone = nextPhone();
        for (int month = 1; month <= 4; month++) {
            ChallengePurchase owned = new ChallengePurchase();
            owned.setBuyerName("Ana");
            owned.setPhone(phone);
            owned.setEmail("x@example.com");
            owned.setStatus(ChallengePurchaseStatus.PAID);
            owned.setAccessToken("tok-" + phone + "-" + month);
            owned.setChallengeMonth(month);
            owned.setPurchaseDate(Instant.now());
            challengePurchases.saveAndFlush(owned);
        }
        PackOrder order = orders.saveAndFlush(order("nomonth@example.com", phone, true, PackOrderStatus.PENDING));

        packOrderService.onPayment(order.getId(), approved(order, "PAY-NM-" + order.getId()));

        PackOrder paid = orders.findById(order.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(PackOrderStatus.PAID);
        assertThat(paid.getChallengePurchaseId()).isNull();
        assertThat(challengeMonths(phone)).containsExactly(1, 2, 3, 4);
    }

    // ------------------------------------------------------------------------------------------------------------------------

    private List<EmailMessage> cuadernosEmailsSentTo(String email) {
        return mockingDetails(emailSender).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("send"))
                .map(invocation -> (EmailMessage) invocation.getArgument(0))
                .filter(message -> message.to().equals(email) && message.subject().startsWith("Tus Cuadernos TIAM"))
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

    /** A Cuadernos order at the launch prices: 20000, plus the 15000 Desafío offer when {@code withGames}. */
    private static PackOrder order(String email, String phone, boolean withGames, PackOrderStatus status) {
        PackOrder order = new PackOrder();
        order.setAccessToken(PackTokens.newToken());
        order.setStatus(status);
        order.setEdition(PackEdition.CUADERNOS);
        order.setWithGames(withGames);
        order.setCurrency("ARS");
        order.setPackAmount(new BigDecimal("20000"));
        order.setGamesAmount(withGames ? new BigDecimal("15000") : BigDecimal.ZERO);
        order.setTotalAmount(withGames ? new BigDecimal("35000") : new BigDecimal("20000"));
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
