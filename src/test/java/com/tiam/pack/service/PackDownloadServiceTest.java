package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.tiam.pack.storage.StorageUnavailableException;
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

@ExtendWith(MockitoExtension.class)
class PackDownloadServiceTest {

    static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");
    static final String SIGNED = "https://acct.r2.cloudflarestorage.com/bucket/packs/v1/familias/a4/pack-completo.pdf?sig=1";

    final String token = PackTokens.newToken();

    @Mock PackOrderRepository orders;
    @Mock PackDownloadEventRepository downloads;
    @Mock PrivateFileStore store;
    @Mock ChallengePurchaseService challengePurchases;

    PackProperties properties;
    PackDownloadService service;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        service = new PackDownloadService(orders, downloads, store, challengePurchases, properties, new MutableClock(NOW));
    }

    // --- the order view ---------------------------------------------------------------------------------------------

    @Test
    void view_paidOrder_listsOnlyWhatTheBuyerBought_withCatalogKeysNotStorageKeys() {
        givenOrder(PackOrderStatus.PAID, 90);

        PackOrderResponse view = service.view(token);

        assertThat(view.status()).isEqualTo("PAID");
        assertThat(view.total()).isEqualByComparingTo("14990");
        assertThat(view.firstName()).isEqualTo("Ana");
        assertThat(view.edition()).isEqualTo("familias");
        assertThat(view.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(90)));
        assertThat(view.items()).hasSize(1);
        PackOrderResponse.Item pack = view.items().get(0);
        assertThat(pack.code()).isEqualTo("PACK");
        assertThat(pack.files()).extracting(PackOrderResponse.FileInfo::key)
                .containsExactly("pack-completo-a4", "cuadernillos-a4", "pack-completo-carta", "cuadernillos-carta");
        assertThat(pack.files().get(0).paper()).isEqualTo("A4");
        assertThat(pack.files().get(0).sizeMb()).isEqualTo(19);
        assertThat(pack.files()).noneMatch(f -> f.key().contains("packs/"));
        assertThat(view.games()).isNull();
    }

    @Test
    void view_withTheExtraBooklet_addsASecondItem() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, 90);
        order.setWithExtra(true);

        PackOrderResponse view = service.view(token);

        assertThat(view.items()).extracting(PackOrderResponse.Item::code).containsExactly("PACK", "EXTRA");
        assertThat(view.items().get(1).files()).extracting(PackOrderResponse.FileInfo::key)
                .containsExactly("extra-a4", "extra-carta");
    }

    @Test
    void view_withGames_carriesThePersonalDesafioLink() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, 90);
        order.setWithGames(true);
        order.setChallengePurchaseId(77L);
        when(challengePurchases.findAccessLinkIfActive(77L)).thenReturn(Optional.of("https://tiamdigital.com/desafio/tok"));

        PackOrderResponse view = service.view(token);

        assertThat(view.games()).isEqualTo(new PackOrderResponse.Games("https://tiamdigital.com/desafio/tok"));
    }

    @Test
    void view_withGamesButNoLinkAvailable_keepsTheBlockWithANullLink() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, 90);
        order.setWithGames(true);
        when(challengePurchases.findAccessLinkIfActive(any())).thenReturn(Optional.empty());

        PackOrderResponse view = service.view(token);

        assertThat(view.games()).isNotNull();
        assertThat(view.games().link()).isNull();
    }

    @Test
    void view_pendingOrder_saysOnlyThatItIsPending() {
        givenOrder(PackOrderStatus.PENDING, 90);

        PackOrderResponse view = service.view(token);

        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.edition()).isEqualTo("familias");
        assertThat(view.total()).isNull();
        assertThat(view.items()).isEmpty();
        assertThat(view.firstName()).isNull();
        assertThat(view.expiresAt()).isNull();
    }

    @Test
    void view_anOrderHeldForReview_isHonestlyReportedAsReview_withoutAnyFiles() {
        givenOrder(PackOrderStatus.REVIEW, 90);

        PackOrderResponse view = service.view(token);

        assertThat(view.status()).isEqualTo("REVIEW");
        assertThat(view.edition()).isEqualTo("familias");
        assertThat(view.items()).isEmpty();
        assertThat(view.expiresAt()).isNull();
    }

    @Test
    void view_aFailedOrder_saysFailed() {
        givenOrder(PackOrderStatus.FAILED, 90);

        PackOrderResponse view = service.view(token);

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.items()).isEmpty();
    }

    @Test
    void view_revokedExpiredUnknownOrMalformed_areAllTheSameNotFound() {
        givenOrder(PackOrderStatus.REVOKED, 90);
        assertThatThrownBy(() -> service.view(token)).isInstanceOf(ResourceNotFoundException.class);

        givenOrder(PackOrderStatus.PAID, -1); // access window already closed
        assertThatThrownBy(() -> service.view(token)).isInstanceOf(ResourceNotFoundException.class);

        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.view(token)).isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> service.view("not-a-token")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.view(null)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void notFoundMessages_neverRepeatTheToken() {
        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.view(token)).hasMessageNotContaining(token);
        assertThatThrownBy(() -> service.issueLink(token, "pack-completo-a4")).hasMessageNotContaining(token);
    }

    // --- issuing a link ------------------------------------------------------------------------------------------------

    @Test
    void issueLink_signsTheVersionedStorageKey_for300Seconds_withAFixedDownloadName() {
        givenOrder(PackOrderStatus.PAID, 90);
        when(store.signedDownloadUrl(any(), any(), any())).thenReturn(SIGNED);

        DownloadLinkResponse link = service.issueLink(token, "pack-completo-a4");

        assertThat(link.url()).isEqualTo(SIGNED);
        assertThat(link.expiresInSeconds()).isEqualTo(300L);
        verify(store).signedDownloadUrl("packs/v1/familias/a4/pack-completo.pdf", Duration.ofSeconds(300),
                "TIAM-Pack-Familias-A4-completo.pdf");
    }

    @Test
    void issueLink_usesTheCatalogVersionTheOrderWasBoughtUnder_soANewVersionNeverBreaksOldOrders() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, 90);
        order.setCatalogVersion("v1");
        properties.setCatalogVersion("v2"); // a later catalog; this order must keep its own files
        when(store.signedDownloadUrl(any(), any(), any())).thenReturn(SIGNED);

        service.issueLink(token, "cuadernillos-carta");

        verify(store).signedDownloadUrl(eq("packs/v1/familias/carta/cuadernillos.zip"), any(), any());
    }

    @Test
    void issueLink_ttlFollowsTheConfiguration() {
        givenOrder(PackOrderStatus.PAID, 90);
        properties.getDownloads().setUrlTtlSeconds(120);
        when(store.signedDownloadUrl(any(), any(), any())).thenReturn(SIGNED);

        DownloadLinkResponse link = service.issueLink(token, "pack-completo-a4");

        assertThat(link.expiresInSeconds()).isEqualTo(120L);
        verify(store).signedDownloadUrl(any(), eq(Duration.ofSeconds(120)), any());
    }

    @Test
    void issueLink_logsEachIssuedLink() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, 90);
        when(store.signedDownloadUrl(any(), any(), any())).thenReturn(SIGNED);

        service.issueLink(token, "pack-completo-a4");

        ArgumentCaptor<PackDownloadEvent> captor = ArgumentCaptor.forClass(PackDownloadEvent.class);
        verify(downloads).save(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo(order.getId());
        assertThat(captor.getValue().getFileKey()).isEqualTo("pack-completo-a4");
    }

    @Test
    void issueLink_aFileTheOrderDidNotBuy_isNotFound_andNothingIsSigned() {
        givenOrder(PackOrderStatus.PAID, 90); // no extra

        assertThatThrownBy(() -> service.issueLink(token, "extra-a4")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.issueLink(token, "../../etc/passwd")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.issueLink(token, "packs/v1/familias/a4/pack-completo.pdf"))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(store);
        verify(downloads, never()).save(any());
    }

    @Test
    void issueLink_notPaidRevokedOrExpired_isNotFound() {
        for (PackOrderStatus status : new PackOrderStatus[] {PackOrderStatus.PENDING, PackOrderStatus.FAILED,
                PackOrderStatus.REVIEW, PackOrderStatus.REVOKED}) {
            givenOrder(status, 90);
            assertThatThrownBy(() -> service.issueLink(token, "pack-completo-a4"))
                    .as("status %s", status).isInstanceOf(ResourceNotFoundException.class);
        }
        givenOrder(PackOrderStatus.PAID, -1);
        assertThatThrownBy(() -> service.issueLink(token, "pack-completo-a4")).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(store);
    }

    @Test
    void issueLink_anInactiveOrder_isNotFound() {
        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueLink(token, "pack-completo-a4")).isInstanceOf(ResourceNotFoundException.class);
    }

    // --- caps ---------------------------------------------------------------------------------------------------------------

    @Test
    void issueLink_locksTheOrderBeforeCountingTheCaps_soParallelRequestsCannotOvershootThem() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, 90);
        when(store.signedDownloadUrl(any(), any(), any())).thenReturn(SIGNED);

        service.issueLink(token, "pack-completo-a4");

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(orders, downloads);
        inOrder.verify(orders).findWithLockById(order.getId());
        inOrder.verify(downloads).countByOrderIdAndCreatedAtAfter(eq(order.getId()), any());
        inOrder.verify(downloads).save(any());
    }

    @Test
    void issueLink_anOrderRevokedWhileWaitingForTheLock_isNotFound() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, 90);
        PackOrder revokedMeanwhile = new PackOrder();
        revokedMeanwhile.setId(order.getId());
        revokedMeanwhile.setStatus(PackOrderStatus.REVOKED);
        revokedMeanwhile.setEdition(PackEdition.FAMILIAS);
        revokedMeanwhile.setAccessExpiresAt(order.getAccessExpiresAt());
        when(orders.findWithLockById(42L)).thenReturn(Optional.of(revokedMeanwhile));

        assertThatThrownBy(() -> service.issueLink(token, "pack-completo-a4")).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(store);
    }

    @Test
    void issueLink_dailyCapReached_isRefused() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, 90);
        when(downloads.countByOrderIdAndCreatedAtAfter(eq(order.getId()), any())).thenReturn(30L);

        assertThatThrownBy(() -> service.issueLink(token, "pack-completo-a4"))
                .isInstanceOfSatisfying(PackDownloadLimitException.class,
                        e -> assertThat(e.cap()).isEqualTo(PackDownloadLimitException.Cap.DAILY));

        verifyNoInteractions(store);
        verify(downloads, never()).save(any());
    }

    @Test
    void issueLink_dailyCapCountsTheLast24Hours() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, 90);
        when(downloads.countByOrderIdAndCreatedAtAfter(eq(order.getId()), any())).thenReturn(29L);
        when(downloads.countByOrderId(anyLong())).thenReturn(29L);
        when(store.signedDownloadUrl(any(), any(), any())).thenReturn(SIGNED);

        service.issueLink(token, "pack-completo-a4");

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(downloads).countByOrderIdAndCreatedAtAfter(eq(order.getId()), since.capture());
        assertThat(since.getValue()).isEqualTo(NOW.minus(Duration.ofHours(24)));
    }

    @Test
    void issueLink_totalCapReached_isRefused() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, 90);
        when(downloads.countByOrderId(order.getId())).thenReturn(300L);

        assertThatThrownBy(() -> service.issueLink(token, "pack-completo-a4"))
                .isInstanceOfSatisfying(PackDownloadLimitException.class,
                        e -> assertThat(e.cap()).isEqualTo(PackDownloadLimitException.Cap.TOTAL));

        verifyNoInteractions(store);
    }

    @Test
    void issueLink_capsAreConfigurable() {
        properties.getDownloads().setDailyCap(2);
        PackOrder order = givenOrder(PackOrderStatus.PAID, 90);
        when(downloads.countByOrderIdAndCreatedAtAfter(eq(order.getId()), any())).thenReturn(2L);

        assertThatThrownBy(() -> service.issueLink(token, "pack-completo-a4")).isInstanceOf(PackDownloadLimitException.class);
    }

    // --- storage trouble -------------------------------------------------------------------------------------------------------

    @Test
    void issueLink_whenTheStoreIsMisconfigured_isUnavailable_andNothingIsCounted() {
        givenOrder(PackOrderStatus.PAID, 90);
        when(store.signedDownloadUrl(any(), any(), any())).thenThrow(new StorageUnavailableException("PACK_STORAGE wrong"));

        assertThatThrownBy(() -> service.issueLink(token, "pack-completo-a4")).isInstanceOf(PackUnavailableException.class);

        verify(downloads, never()).save(any());
    }

    // --- helpers ----------------------------------------------------------------------------------------------------------------------

    private PackOrder givenOrder(PackOrderStatus status, long accessDaysLeft) {
        PackOrder order = new PackOrder();
        order.setId(42L);
        order.setStatus(status);
        order.setAccessToken(token);
        order.setEdition(PackEdition.FAMILIAS);
        order.setBuyerName("ana gómez");
        order.setEmail("ana@example.com");
        order.setCatalogVersion("v1");
        order.setTotalAmount(new java.math.BigDecimal("14990.00"));
        order.setPaidAt(NOW.minus(Duration.ofDays(1)));
        order.setAccessExpiresAt(NOW.plus(Duration.ofDays(accessDaysLeft)));
        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.of(order));
        org.mockito.Mockito.lenient().when(orders.findWithLockById(42L)).thenReturn(Optional.of(order));
        return order;
    }
}
