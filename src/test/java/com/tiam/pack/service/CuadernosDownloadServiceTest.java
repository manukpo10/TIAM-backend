package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackDownloadEvent;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackTokens;
import com.tiam.pack.dto.DownloadLinkResponse;
import com.tiam.pack.dto.PackOrderResponse;
import com.tiam.pack.repository.PackDownloadEventRepository;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.pack.storage.PrivateFileStore;
import com.tiam.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** What a «Cuadernos TIAM» order can see and download, and what it can never reach. */
@ExtendWith(MockitoExtension.class)
class CuadernosDownloadServiceTest {

    static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");
    static final String SIGNED = "https://acct.r2.cloudflarestorage.com/bucket/packs/v1/cuadernos/a4/coleccion-completa.pdf?sig=1";

    final String token = PackTokens.newToken();

    @Mock PackOrderRepository orders;
    @Mock PackDownloadEventRepository downloads;
    @Mock PrivateFileStore store;
    @Mock ChallengePurchaseService challengePurchases;
    @Mock PackOrderService packOrderService;

    PackProperties properties;
    PackDownloadService service;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        service = new PackDownloadService(orders, downloads, store, challengePurchases, properties, new MutableClock(NOW),
                packOrderService);
    }

    // --- the order view -----------------------------------------------------------------------------------------------

    @Test
    void view_paidOrder_listsTheCollectionAndTheFourWorkbooks_forBothPapers_withCatalogKeys() {
        givenOrder(PackEdition.CUADERNOS, PackOrderStatus.PAID);

        PackOrderResponse view = service.view(token);

        assertThat(view.status()).isEqualTo("PAID");
        assertThat(view.edition()).isEqualTo("cuadernos");
        assertThat(view.firstName()).isEqualTo("Ana");
        assertThat(view.total()).isEqualByComparingTo("20000");
        assertThat(view.items()).hasSize(1);
        PackOrderResponse.Item item = view.items().get(0);
        assertThat(item.code()).isEqualTo("PACK");
        assertThat(item.title()).isEqualTo("Cuadernos TIAM");
        assertThat(item.files()).extracting(PackOrderResponse.FileInfo::key).containsExactly(
                "coleccion-completa-a4", "cuaderno-1-a4", "cuaderno-2-a4", "cuaderno-3-a4", "cuaderno-4-a4",
                "coleccion-completa-carta", "cuaderno-1-carta", "cuaderno-2-carta", "cuaderno-3-carta", "cuaderno-4-carta");
        assertThat(item.files()).noneMatch(f -> f.key().contains("packs/"));
        assertThat(item.files().get(0).label()).isEqualTo("Colección completa: los 4 cuadernos en 1 PDF");
        assertThat(item.files().get(0).paper()).isEqualTo("A4");
        assertThat(item.files().get(0).sizeMb()).isEqualTo(27);
        assertThat(item.files().get(3).label()).isEqualTo("Cuaderno 3 · Nivel intermedio alto");
        assertThat(item.files().get(3).sizeMb()).isEqualTo(7);
        assertThat(item.files().get(5).paper()).isEqualTo("Carta");
        assertThat(view.games()).isNull();
    }

    @Test
    void view_neverShowsTheExtraBooklet_evenIfTheFlagWasSet() {
        givenOrder(PackEdition.CUADERNOS, PackOrderStatus.PAID).setWithExtra(true);

        PackOrderResponse view = service.view(token);

        assertThat(view.items()).extracting(PackOrderResponse.Item::code).containsExactly("PACK");
        assertThat(view.items().get(0).files()).extracting(PackOrderResponse.FileInfo::key)
                .noneMatch(key -> key.startsWith("extra-"));
    }

    @Test
    void view_withTheGamesMonth_carriesThePersonalDesafioLink() {
        PackOrder order = givenOrder(PackEdition.CUADERNOS, PackOrderStatus.PAID);
        order.setWithGames(true);
        order.setChallengePurchaseId(77L);
        when(challengePurchases.findAccessLinkIfActive(77L)).thenReturn(Optional.of("https://tiamdigital.com/desafio/tok"));

        PackOrderResponse view = service.view(token);

        assertThat(view.games().link()).isEqualTo("https://tiamdigital.com/desafio/tok");
    }

    @Test
    void view_withTheGamesMonth_saysItIsOneMonthOfThirtyDaysFromThePurchase_andTheNextOnesAreOptionalAndPaidApart() {
        PackOrder order = givenOrder(PackEdition.CUADERNOS, PackOrderStatus.PAID);
        order.setWithGames(true);
        order.setChallengePurchaseId(77L);
        when(challengePurchases.findAccessLinkIfActive(77L)).thenReturn(Optional.of("https://tiamdigital.com/desafio/tok"));
        when(challengePurchases.findMonthIfActive(77L)).thenReturn(Optional.of(2));

        PackOrderResponse.Games games = service.view(token).games();

        assertThat(games.monthsIncluded()).isEqualTo(1);
        assertThat(games.month()).as("the month actually granted, not always the first").isEqualTo(2);
        assertThat(games.durationDays()).isEqualTo(30);
        assertThat(games.startsAt()).as("the clock runs from the payment, not from a WhatsApp message").isEqualTo("purchase");
        assertThat(games.nextMonthsPaidSeparately()).isTrue();
        assertThat(games.nextMonthPriceArs()).isEqualTo(20000); // the Desafío's own price, read from its service
    }

    @Test
    void view_withTheGamesMonthButNoLink_keepsTheBlockWithANullLink_andTheSameFacts() {
        PackOrder order = givenOrder(PackEdition.CUADERNOS, PackOrderStatus.PAID);
        order.setWithGames(true);
        order.setChallengePurchaseId(77L); // granted, but no personal link could be produced
        when(challengePurchases.findAccessLinkIfActive(any())).thenReturn(Optional.empty());

        PackOrderResponse.Games games = service.view(token).games();

        assertThat(games.link()).isNull();
        assertThat(games.monthsIncluded()).isEqualTo(1);
        assertThat(games.month()).isNull(); // unknown: the page says "1 mes del Desafío"
    }

    @Test
    void view_whenTheMonthWasNeverGranted_theBuyerIsNotToldItExists_forEveryEdition() {
        for (PackEdition edition : PackEdition.values()) {
            PackOrder order = givenOrder(edition, PackOrderStatus.PAID);
            order.setWithGames(true); // bought it, but no month could be granted: no purchase id

            PackOrderResponse view = service.view(token);

            assertThat(view.status()).as("%s", edition).isEqualTo("PAID");
            assertThat(view.games()).as("%s", edition).isNull();
        }
        verifyNoInteractions(challengePurchases);
    }

    @Test
    void view_aPackOrderWithGames_hasNoneOfTheCuadernosFacts_soItsResponseIsUnchanged() {
        PackOrder order = givenOrder(PackEdition.FAMILIAS, PackOrderStatus.PAID);
        order.setWithGames(true);
        order.setChallengePurchaseId(77L);
        when(challengePurchases.findAccessLinkIfActive(77L)).thenReturn(Optional.of("https://tiamdigital.com/desafio/tok"));

        PackOrderResponse view = service.view(token);

        assertThat(view.games()).isEqualTo(new PackOrderResponse.Games("https://tiamdigital.com/desafio/tok"));
        assertThat(view.games().monthsIncluded()).isNull();
        assertThat(view.games().month()).isNull();
        assertThat(view.games().durationDays()).isNull();
        assertThat(view.games().startsAt()).isNull();
        assertThat(view.games().nextMonthsPaidSeparately()).isNull();
        assertThat(view.games().nextMonthPriceArs()).isNull();
    }

    @Test
    void view_notPaidYet_saysOnlyTheStatusAndTheEdition() {
        for (PackOrderStatus status : new PackOrderStatus[] {PackOrderStatus.PENDING, PackOrderStatus.FAILED, PackOrderStatus.REVIEW}) {
            givenOrder(PackEdition.CUADERNOS, status);

            PackOrderResponse view = service.view(token);

            assertThat(view.status()).isEqualTo(status.name());
            assertThat(view.edition()).isEqualTo("cuadernos");
            assertThat(view.items()).isEmpty();
            assertThat(view.total()).isNull();
        }
    }

    @Test
    void view_aRevokedCuadernosOrder_isNotFound() {
        givenOrder(PackEdition.CUADERNOS, PackOrderStatus.REVOKED);

        assertThatThrownBy(() -> service.view(token)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void view_aPackOrder_isUnchanged() {
        givenOrder(PackEdition.FAMILIAS, PackOrderStatus.PAID);

        PackOrderResponse view = service.view(token);

        assertThat(view.edition()).isEqualTo("familias");
        assertThat(view.items().get(0).title()).isEqualTo("Pack imprimible - Edición Familias");
        assertThat(view.items().get(0).files()).extracting(PackOrderResponse.FileInfo::key)
                .containsExactly("pack-completo-a4", "cuadernillos-a4", "pack-completo-carta", "cuadernillos-carta");
    }

    // --- issuing a link -----------------------------------------------------------------------------------------------

    @Test
    void issueLink_signsTheCuadernosStorageKey_withAFixedAsciiDownloadName() {
        givenOrder(PackEdition.CUADERNOS, PackOrderStatus.PAID);
        when(store.signedDownloadUrl(any(), any(), any())).thenReturn(SIGNED);

        DownloadLinkResponse link = service.issueLink(token, "coleccion-completa-a4");

        assertThat(link.url()).isEqualTo(SIGNED);
        assertThat(link.expiresInSeconds()).isEqualTo(300L);
        verify(store).signedDownloadUrl("packs/v1/cuadernos/a4/coleccion-completa.pdf", Duration.ofSeconds(300),
                "TIAM-Cuadernos-A4-coleccion-completa.pdf");
    }

    @Test
    void issueLink_everyOneOfTheTenFiles_resolvesToItsOwnObject() {
        givenOrder(PackEdition.CUADERNOS, PackOrderStatus.PAID);
        when(store.signedDownloadUrl(any(), any(), any())).thenReturn(SIGNED);

        service.issueLink(token, "cuaderno-3-carta");
        service.issueLink(token, "cuaderno-1-a4");

        verify(store).signedDownloadUrl("packs/v1/cuadernos/carta/cuaderno-3.pdf", Duration.ofSeconds(300),
                "TIAM-Cuadernos-Carta-cuaderno-3-intermedio-alto.pdf");
        verify(store).signedDownloadUrl("packs/v1/cuadernos/a4/cuaderno-1.pdf", Duration.ofSeconds(300),
                "TIAM-Cuadernos-A4-cuaderno-1-inicial.pdf");
    }

    @Test
    void issueLink_usesTheCatalogVersionTheOrderWasBoughtUnder() {
        givenOrder(PackEdition.CUADERNOS, PackOrderStatus.PAID);
        properties.setCatalogVersion("v2"); // a later catalog; this order keeps its own files
        when(store.signedDownloadUrl(any(), any(), any())).thenReturn(SIGNED);

        service.issueLink(token, "cuaderno-4-a4");

        verify(store).signedDownloadUrl(eq("packs/v1/cuadernos/a4/cuaderno-4.pdf"), any(), any());
    }

    @Test
    void issueLink_recordsTheCatalogCodeInTheAuditTrail() {
        PackOrder order = givenOrder(PackEdition.CUADERNOS, PackOrderStatus.PAID);
        when(store.signedDownloadUrl(any(), any(), any())).thenReturn(SIGNED);

        service.issueLink(token, "cuaderno-2-a4");

        ArgumentCaptor<PackDownloadEvent> captor = ArgumentCaptor.forClass(PackDownloadEvent.class);
        verify(downloads).save(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo(order.getId());
        assertThat(captor.getValue().getFileKey()).isEqualTo("cuaderno-2-a4");
    }

    @Test
    void issueLink_aCuadernosOrder_isRefusedEveryPackFile_andTheExtra() {
        givenOrder(PackEdition.CUADERNOS, PackOrderStatus.PAID).setWithExtra(true);

        for (String packFile : new String[] {"pack-completo-a4", "cuadernillos-a4", "pack-completo-carta", "cuadernillos-carta",
                "extra-a4", "extra-carta"}) {
            assertThatThrownBy(() -> service.issueLink(token, packFile))
                    .as(packFile).isInstanceOf(ResourceNotFoundException.class);
        }

        verifyNoInteractions(store);
        verify(downloads, never()).save(any());
    }

    @Test
    void issueLink_aPackOrder_isRefusedEveryCuadernosFile() {
        for (PackEdition edition : PackEdition.packEditions()) {
            givenOrder(edition, PackOrderStatus.PAID).setWithExtra(true);

            for (String file : new String[] {"coleccion-completa-a4", "coleccion-completa-carta", "cuaderno-1-a4", "cuaderno-2-a4",
                    "cuaderno-3-carta", "cuaderno-4-carta"}) {
                assertThatThrownBy(() -> service.issueLink(token, file))
                        .as("%s %s", edition, file).isInstanceOf(ResourceNotFoundException.class);
            }
        }

        verifyNoInteractions(store);
    }

    @Test
    void issueLink_aPackOrder_keepsItsFiles() {
        givenOrder(PackEdition.PROFESIONALES, PackOrderStatus.PAID);
        when(store.signedDownloadUrl(any(), any(), any())).thenReturn(SIGNED);

        service.issueLink(token, "cuadernillos-carta");

        verify(store).signedDownloadUrl("packs/v1/profesionales/carta/cuadernillos.zip", Duration.ofSeconds(300),
                "TIAM-Pack-Profesionales-Carta-cuadernillos.zip");
    }

    @Test
    void issueLink_notPaid_isNotFound() {
        givenOrder(PackEdition.CUADERNOS, PackOrderStatus.PENDING);

        assertThatThrownBy(() -> service.issueLink(token, "coleccion-completa-a4")).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(store);
    }

    @Test
    void issueLink_afterTheAccessWindow_isNotFound() {
        PackOrder order = givenOrder(PackEdition.CUADERNOS, PackOrderStatus.PAID);
        order.setAccessExpiresAt(NOW.minus(Duration.ofDays(1)));

        assertThatThrownBy(() -> service.issueLink(token, "coleccion-completa-a4")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void issueLink_theDailyCapAppliesToCuadernosToo() {
        PackOrder order = givenOrder(PackEdition.CUADERNOS, PackOrderStatus.PAID);
        properties.getDownloads().setDailyCap(2);
        when(downloads.countByOrderIdAndCreatedAtAfter(eq(order.getId()), any())).thenReturn(2L);

        assertThatThrownBy(() -> service.issueLink(token, "cuaderno-1-a4")).isInstanceOf(PackDownloadLimitException.class);

        verifyNoInteractions(store);
    }

    // --- helpers -------------------------------------------------------------------------------------------------------

    private PackOrder givenOrder(PackEdition edition, PackOrderStatus status) {
        PackOrder order = new PackOrder();
        order.setId(42L);
        order.setStatus(status);
        order.setAccessToken(token);
        order.setEdition(edition);
        order.setBuyerName("ana gómez");
        order.setEmail("ana@example.com");
        order.setCatalogVersion("v1");
        order.setTotalAmount(new java.math.BigDecimal("20000.00"));
        order.setPaidAt(NOW.minus(Duration.ofDays(1)));
        order.setAccessExpiresAt(NOW.plus(Duration.ofDays(90)));
        org.mockito.Mockito.lenient().when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.of(order));
        org.mockito.Mockito.lenient().when(orders.findWithLockById(42L)).thenReturn(Optional.of(order));
        return order;
    }
}
