package com.tiam.pack.config;

import com.tiam.common.email.EmailSender;
import com.tiam.pack.domain.PackCatalog;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.storage.PrivateFileStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The fail-closed gate in front of the checkout. Sales may be switched on ({@code PACK_SALES_ENABLED=true} for the Pack,
 * {@code CUADERNOS_SALES_ENABLED=true} for the Cuadernos; the gate is the same for both), but a
 * checkout only starts when a buyer who pays will really get what they paid for:
 * <ul>
 *   <li>the private file store is the real remote one (R2), not the development folder whose links point at
 *       the buyer's own computer;</li>
 *   <li>email really leaves (Resend configured), because the access link is delivered by email and the
 *       "lost my link" page depends on it;</li>
 *   <li>the landing, thank-you and downloads URLs are public https addresses;</li>
 *   <li>someone is told when a payment needs a human (amount mismatch, late approval, regret request).</li>
 * </ul>
 * Anything missing makes the checkout answer {@code ?error=cerrado} and log loudly. Nothing here can stop the
 * app from booting (that would take the Desafío down too).
 *
 * <p>{@link #productProblems} adds what is checked per PRODUCT, so one product's gap never closes another: its prices
 * are sane (at least ARS 1000: {@code 20.000} typed with a thousands dot is read as 20) and every object of its catalog
 * exists in R2. The answer about R2 is cached for a few minutes, so a checkout does not call R2.
 *
 * <p>{@code PACK_DEV_MODE=true} lifts the shop requirements and the file check for local development only; the
 * {@code prod} profile ignores it. The price rule has no dev exception.
 */
@Slf4j
@Component
public class PackSalesReadiness {

    /** The lowest price a product or add-on may have. A smaller number is a typo ("20.000" read as 20, or 0). */
    static final BigDecimal MIN_PRICE_ARS = new BigDecimal("1000");
    /** A complete catalog is trusted this long before asking R2 again. */
    static final Duration COMPLETE_CATALOG_TTL = Duration.ofMinutes(10);
    /** A gap is rechecked sooner, so uploading the missing file reopens sales without a restart. */
    static final Duration GAP_TTL = Duration.ofSeconds(30);
    private static final int KEYS_SHOWN = 3;

    private final PackProperties properties;
    private final PrivateFileStore store;
    private final EmailSender emailSender;
    private final Environment environment;
    private final Clock clock;

    /** What R2 said about one product's files, until {@code validUntil}. */
    private record CatalogCheck(List<String> missing, int total, Instant validUntil) {
    }

    private final Map<String, CatalogCheck> catalogChecks = new ConcurrentHashMap<>();

    public PackSalesReadiness(PackProperties properties, PrivateFileStore store, EmailSender emailSender,
            Environment environment) {
        this(properties, store, emailSender, environment, Clock.systemUTC());
    }

    @Autowired
    public PackSalesReadiness(PackProperties properties, PrivateFileStore store, EmailSender emailSender,
            Environment environment, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.emailSender = emailSender;
        this.environment = environment;
        this.clock = clock;
    }

    /** What is wrong, one human-readable line each (naming the environment variables to fix). Empty = ready. */
    public List<String> problems() {
        if (devModeActive()) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        if (!store.isRemote()) {
            problems.add("the private file store is not Cloudflare R2 (set PACK_STORAGE=r2 and R2_ACCOUNT_ID, "
                    + "R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET): download links would not work for buyers");
        }
        if (!emailSender.isEnabled()) {
            problems.add("email is not really configured (set RESEND_API_KEY and EMAIL_FROM): the access link "
                    + "would never be delivered and 'lost my link' could not work");
        }
        requirePublicHttps(problems, "PACK_LANDING_BASE_URL", properties.getLandingBaseUrl());
        requirePublicHttps(problems, "PACK_THANKYOU_URL", properties.getThankyouUrl());
        requirePublicHttps(problems, "PACK_DOWNLOAD_BASE_URL", properties.getDownloadBaseUrl());
        if (!StringUtils.hasText(properties.effectiveOwnerNotifyTo())) {
            problems.add("nobody is set to be notified (set PACK_OWNER_NOTIFY_TO): a payment held for review, a "
                    + "late approval or a regret request would go unnoticed");
        }
        return problems;
    }

    /**
     * What keeps THIS product closed even when the shop is ready: a price that is not sane, or catalog objects that are
     * not in R2. Empty = this product may be sold. The prices are always checked (also in dev mode); the files only
     * when a real R2 is in use and dev mode is off (a development folder has none, and {@link #problems} already
     * refuses it).
     */
    public List<String> productProblems(PackEdition edition) {
        List<String> problems = new ArrayList<>(priceProblems(edition));
        if (!devModeActive() && store.isRemote()) {
            problems.addAll(fileProblems(edition));
        }
        return problems;
    }

    public boolean isReady() {
        return problems().isEmpty();
    }

    /** True when PACK_DEV_MODE is set and this is not a production deployment. */
    public boolean devModeActive() {
        return properties.isDevMode() && !isProd();
    }

    /** True when PACK_DEV_MODE is set in a production deployment, where it has no effect. */
    public boolean devModeIgnored() {
        return properties.isDevMode() && isProd();
    }

    /** The prod profile is active. */
    public boolean isProduction() {
        return isProd();
    }

    private boolean isProd() {
        return environment.acceptsProfiles(Profiles.of("prod"));
    }

    // ---- prices --------------------------------------------------------------------------------------------------

    private List<String> priceProblems(PackEdition edition) {
        List<String> problems = new ArrayList<>();
        if (edition.isCuadernos()) {
            checkPrice(problems, "CUADERNOS_PRICE_ARS", properties.getCuadernos().getPriceArs(), edition);
            checkPrice(problems, "CUADERNOS_GAMES_PRICE_ARS", properties.getCuadernos().getGamesPriceArs(), edition);
        } else {
            checkPrice(problems, "PACK_PRICE_ARS", properties.getPriceArs(), edition);
            checkPrice(problems, "PACK_GAMES_PRICE_ARS", properties.getGamesPriceArs(), edition);
            checkPrice(problems, "PACK_EXTRA_PRICE_ARS", properties.getExtraPriceArs(), edition);
        }
        return problems;
    }

    private static void checkPrice(List<String> problems, String variable, BigDecimal value, PackEdition edition) {
        if (value == null) {
            problems.add(variable + " is not set: " + edition.productName() + " stays closed");
        } else if (value.compareTo(MIN_PRICE_ARS) < 0) {
            problems.add(variable + " is \"" + value.stripTrailingZeros().toPlainString() + "\", below ARS "
                    + MIN_PRICE_ARS.toPlainString() + ": use plain digits (20000, not 20.000, which is read as 20) - "
                    + edition.productName() + " stays closed");
        }
    }

    // ---- files in R2 ---------------------------------------------------------------------------------------------

    private List<String> fileProblems(PackEdition edition) {
        String version = edition.isCuadernos() ? properties.getCuadernos().getCatalogVersion() : properties.getCatalogVersion();
        String cacheKey = edition.slug() + "/" + version;
        Instant now = clock.instant();
        CatalogCheck cached = catalogChecks.get(cacheKey);
        if (cached == null || !now.isBefore(cached.validUntil())) {
            cached = checkCatalog(edition, version, now);
            catalogChecks.put(cacheKey, cached);
        }
        if (cached.missing().isEmpty()) {
            return List.of();
        }
        String shown = String.join(", ", cached.missing().stream().limit(KEYS_SHOWN).toList())
                + (cached.missing().size() > KEYS_SHOWN ? ", ..." : "");
        return List.of(edition.productName() + ": " + cached.missing().size() + " of " + cached.total()
                + " files are not in R2 (" + shown + "): upload them to the bucket before selling it");
    }

    private CatalogCheck checkCatalog(PackEdition edition, String version, Instant now) {
        List<String> keys = PackCatalog.filesFor(edition, true).stream()
                .map(file -> file.storageKey(version, edition)).toList();
        List<String> missing = store.missingKeys(keys);
        if (missing.isEmpty()) {
            return new CatalogCheck(List.of(), keys.size(), now.plus(COMPLETE_CATALOG_TTL));
        }
        log.error("{} sales stay closed: {} of {} catalog files are not in R2 (or R2 could not be checked): {}",
                edition.productName(), missing.size(), keys.size(), missing);
        return new CatalogCheck(List.copyOf(missing), keys.size(), now.plus(GAP_TTL));
    }

    private static void requirePublicHttps(List<String> problems, String variable, String value) {
        if (!PackUrls.isPublicHttps(value)) {
            problems.add(variable + " is \"" + value + "\" (empty, localhost or not https): Mercado Pago or the "
                    + "buyer would be sent to an address that does not work");
        }
    }
}
