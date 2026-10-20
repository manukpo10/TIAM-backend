package com.tiam.pack.service;

import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackCatalog;
import com.tiam.pack.domain.PackDownloadEvent;
import com.tiam.pack.domain.PackFile;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackProduct;
import com.tiam.pack.domain.PackTokens;
import com.tiam.pack.dto.DownloadLinkResponse;
import com.tiam.pack.dto.PackOrderResponse;
import com.tiam.pack.repository.PackDownloadEventRepository;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.pack.storage.PrivateFileStore;
import com.tiam.pack.storage.StorageUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The buyer's downloads page: what an order contains, and short-lived signed links for its files.
 *
 * <p>The token is the credential, so every "no" is the SAME {@link ResourceNotFoundException} (malformed,
 * unknown, inactive, revoked, expired, a file the order did not buy) and its message never repeats the token.
 * The one exception is deliberate: a known order that is still PENDING, FAILED or in REVIEW says so (with its
 * edition only), because the thank-you page polls it while the payment confirms; that reveals nothing to someone
 * without the token.
 *
 * <p>Links are issued by POST, never by a GET that email scanners and link previewers could trigger, and each
 * one is logged and counted against two caps (per 24 h and in total) that make sharing the token uneconomical.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackDownloadService {

    private static final Duration DAILY_WINDOW = Duration.ofHours(24);

    private final PackOrderRepository orders;
    private final PackDownloadEventRepository downloads;
    private final PrivateFileStore store;
    private final ChallengePurchaseService challengePurchases;
    private final PackProperties properties;
    private final Clock clock;
    private final PackOrderService packOrderService;

    /** Not read-only: the first visit with a re-sent link promotes it to the order's token (see findVisibleOrder). */
    @Transactional
    public PackOrderResponse view(String token) {
        PackOrder order = findVisibleOrder(token);
        switch (order.getStatus()) {
            case PENDING -> {
                return PackOrderResponse.withoutFiles("PENDING", order.getEdition().slug());
            }
            case REVIEW -> {
                // Honest to the buyer: a payment arrived but a person has to check it (the owner was told).
                return PackOrderResponse.withoutFiles("REVIEW", order.getEdition().slug());
            }
            case FAILED -> {
                return PackOrderResponse.withoutFiles("FAILED", order.getEdition().slug());
            }
            default -> {
                // PAID: fall through to the full body, after the access window check below
            }
        }
        requireOpenAccess(order);

        List<PackOrderResponse.Item> items = new ArrayList<>();
        // The Cuadernos are one item with their own title (no Pack wording, no editions) and never have the extra booklet.
        items.add(item(order, PackProduct.PACK, "PACK", order.getEdition().isCuadernos()
                ? "Cuadernos TIAM"
                : "Pack imprimible - Edición " + order.getEdition().displayName()));
        if (order.isWithExtra() && !order.getEdition().isCuadernos()) {
            items.add(item(order, PackProduct.EXTRA, "EXTRA", "Cuadernillo extra"));
        }
        PackOrderResponse.Games games = null;
        // Only when the month was actually granted: a buyer whose month could not be given (the owner is told to refund
        // it) must not be shown a block that promises it.
        if (order.hasDesafioMonth()) {
            String gamesLink = challengePurchases.findAccessLinkIfActive(order.getChallengePurchaseId()).orElse(null);
            games = order.getEdition().isCuadernos()
                    ? PackOrderResponse.Games.includedMonth(gamesLink,
                            challengePurchases.findMonthIfActive(order.getChallengePurchaseId()).orElse(null),
                            ChallengePurchaseService.monthPriceArs())
                    : new PackOrderResponse.Games(gamesLink);
        }
        return PackOrderResponse.paid(firstName(order.getBuyerName()), order.getEdition().slug(), items, games,
                order.getAccessExpiresAt(), order.getTotalAmount());
    }

    @Transactional
    public DownloadLinkResponse issueLink(String token, String fileCode) {
        PackOrder visible = findVisibleOrder(token);
        if (visible.getStatus() != PackOrderStatus.PAID) {
            throw notFound();
        }
        // Lock the row before counting, so parallel requests queue up and each sees the previous one's event
        // (otherwise a burst could all pass the cap check at once). Re-validate on the locked copy.
        PackOrder order = orders.findWithLockById(visible.getId()).orElseThrow(PackDownloadService::notFound);
        if (order.getStatus() != PackOrderStatus.PAID) {
            throw notFound();
        }
        requireOpenAccess(order);

        PackFile file = PackCatalog.find(fileCode, order.getEdition(), order.isWithExtra()).orElseThrow(PackDownloadService::notFound);

        PackProperties.Downloads caps = properties.getDownloads();
        Instant since = clock.instant().minus(DAILY_WINDOW);
        if (downloads.countByOrderIdAndCreatedAtAfter(order.getId(), since) >= caps.getDailyCap()) {
            log.warn("Pack order id={} reached its daily download cap", order.getId());
            throw new PackDownloadLimitException(PackDownloadLimitException.Cap.DAILY);
        }
        if (downloads.countByOrderId(order.getId()) >= caps.getTotalCap()) {
            log.warn("Pack order id={} reached its total download cap", order.getId());
            throw new PackDownloadLimitException(PackDownloadLimitException.Cap.TOTAL);
        }

        Duration ttl = Duration.ofSeconds(caps.getUrlTtlSeconds());
        String url;
        try {
            url = store.signedDownloadUrl(file.storageKey(order.getCatalogVersion(), order.getEdition()), ttl,
                    file.downloadFilename(order.getEdition()));
        } catch (StorageUnavailableException e) {
            log.error("Pack download link for order id={} could not be issued: {}", order.getId(), e.getMessage());
            throw new PackUnavailableException("File storage is not available", e);
        }

        PackDownloadEvent event = new PackDownloadEvent();
        event.setOrderId(order.getId());
        event.setFileKey(file.code());
        downloads.save(event);
        log.info("Pack download link issued: order id={} file={}", order.getId(), file.code());

        return new DownloadLinkResponse(url, ttl.getSeconds());
    }

    // ---------------------------------------------------------------------------------------------------

    /** The order behind the token unless it must look like it does not exist. */
    private PackOrder findVisibleOrder(String token) {
        if (!PackTokens.looksValid(token)) {
            throw notFound();
        }
        PackOrder order = orders.findByAccessTokenAndActivoTrue(token)
                .or(() -> promoteReplacementToken(token))
                .orElseThrow(PackDownloadService::notFound);
        if (order.getStatus() == PackOrderStatus.REVOKED) {
            throw notFound();
        }
        return order;
    }

    /**
     * A token that is nobody's current one may be the replacement a buyer asked for ("I lost my link"). Using it
     * for the first time makes it the order's token and retires the old one: the buyer chose to switch, which is
     * what stops a stranger from invalidating a working link just by requesting a re-send.
     */
    private Optional<PackOrder> promoteReplacementToken(String token) {
        return orders.findByNextAccessTokenAndActivoTrue(token)
                .flatMap(candidate -> packOrderService.promoteReplacementToken(candidate.getId(), token));
    }

    private void requireOpenAccess(PackOrder order) {
        if (!order.isDownloadable(clock.instant())) {
            throw notFound();
        }
    }

    private PackOrderResponse.Item item(PackOrder order, PackProduct product, String code, String title) {
        List<PackOrderResponse.FileInfo> files = PackCatalog.filesFor(order.getEdition(), order.isWithExtra()).stream()
                .filter(file -> file.product() == product)
                .map(file -> new PackOrderResponse.FileInfo(file.code(), file.label(), file.paper().displayName(), file.sizeMb()))
                .toList();
        return new PackOrderResponse.Item(code, title, files);
    }

    private static String firstName(String buyerName) {
        if (buyerName == null || buyerName.isBlank()) {
            return "";
        }
        String first = buyerName.trim().split("\\s+")[0];
        return first.substring(0, 1).toUpperCase() + first.substring(1).toLowerCase();
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Not found");
    }
}
