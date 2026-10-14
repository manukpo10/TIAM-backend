package com.tiam.pack.config;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the printable-pack sales (everything under {@code tiam.pack}). The defaults here
 * mirror application.yml so a unit test or a missing property behaves like a fresh deploy.
 *
 * <p>Prices live ONLY here (server side): the landing page may display them, but the checkout
 * never trusts an amount that arrives from the browser.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tiam.pack")
public class PackProperties {

    /** Master switch for starting new checkouts. Paid orders keep working when it is off. */
    private boolean salesEnabled = false;

    /**
     * DEVELOPMENT ONLY. Lets a checkout start with the local file folder, the logging-only email sender and
     * localhost URLs, which are otherwise refused (see PackSalesReadiness). Ignored when the {@code prod}
     * profile is active. Never put it in a real deployment's environment.
     */
    private boolean devMode = false;

    private BigDecimal priceArs = new BigDecimal("14990");
    private BigDecimal gamesPriceArs = new BigDecimal("10000");
    private BigDecimal extraPriceArs = new BigDecimal("4990");

    /** Public origin of the landing pages ("/pack/familias", "/pack/profesionales" live under it). */
    private String landingBaseUrl = "http://localhost:5173";

    /** Where Mercado Pago sends the buyer back to; the order token is appended as {@code ?o=}. */
    private String thankyouUrl = "http://localhost:5173/pack/gracias";

    /** Base of the downloads page in the access email; the order token is appended as a path segment. */
    private String downloadBaseUrl = "http://localhost:5173/pack/descargas";

    /**
     * Public https folder that serves the images of the pack emails (logo, product picture): the frontend's
     * {@code public/email/}. A sent email keeps pointing at these URLs for good, so the names there are stable.
     * Only the dev mode may point it at a local frontend; anything else that is not public https is ignored.
     */
    private String emailAssetsBaseUrl = "https://www.tiamdigital.com/email";

    /** How long a paid order can download after paying (renewed when access is re-sent). */
    private int accessDays = 90;

    /** Storage key version for NEW orders (packs/v1/..., packs/v2/...); each order keeps its own. */
    private String catalogVersion = "v1";

    /** How long the Mercado Pago preference stays payable (cash coupons expire in a few days). */
    private int preferenceValidityDays = 7;

    /** Receives the regret-form notice. Empty = only logged. */
    private String regretNotifyTo = "";

    /** Receives operational alerts (amount mismatch, double payment). Falls back to {@link #regretNotifyTo}. */
    private String ownerNotifyTo = "";

    private final Reconciliation reconciliation = new Reconciliation();
    private final EmailRetry emailRetry = new EmailRetry();
    private final Downloads downloads = new Downloads();
    private final RateLimits rateLimits = new RateLimits();
    private final Storage storage = new Storage();

    /** The address that gets operational alerts, or empty when none is configured. */
    public String effectiveOwnerNotifyTo() {
        String owner = ownerNotifyTo == null ? "" : ownerNotifyTo.strip();
        if (!owner.isEmpty()) {
            return owner;
        }
        return regretNotifyTo == null ? "" : regretNotifyTo.strip();
    }

    /** The address that gets regret-form notices, or empty when none is configured. */
    public String effectiveRegretNotifyTo() {
        String regret = regretNotifyTo == null ? "" : regretNotifyTo.strip();
        if (!regret.isEmpty()) {
            return regret;
        }
        return ownerNotifyTo == null ? "" : ownerNotifyTo.strip();
    }

    @Getter
    @Setter
    public static class Reconciliation {
        private boolean enabled = true;
        private long intervalMs = 900_000L;
        private long gracePeriodMinutes = 10L;
        /** The preference expires after 7 days, so a longer window only wastes Mercado Pago searches. */
        private long lookbackDays = 14L;
        /**
         * Rejected orders are re-checked for this long: a buyer can retry and pay on the same checkout after a
         * rejection, and if that approval's notification is lost nothing else would deliver the order.
         */
        private long failedLookbackDays = 3L;
        /** Most orders asked about per run (newest first), so a pile of abandoned carts cannot flood Mercado Pago. */
        private int maxPerRun = 100;
        /** A run stops asking Mercado Pago after this long, so a slow or failing API cannot hog a scheduler thread. */
        private int maxRunSeconds = 120;
        /** PAID orders younger than this are checked for refunds / chargebacks whose notification was lost. */
        private long refundLookbackDays = 30L;
        /** How often one PAID order is asked about. */
        private long refundCheckIntervalHours = 6L;
    }

    @Getter
    @Setter
    public static class EmailRetry {
        private boolean enabled = true;
        private long intervalMs = 900_000L;
        private long minAgeMinutes = 5L;
        private long lookbackHours = 72L;
    }

    @Getter
    @Setter
    public static class Downloads {
        private long urlTtlSeconds = 300L;
        private int dailyCap = 30;
        private int totalCap = 300;
    }

    @Getter
    @Setter
    public static class RateLimits {
        /**
         * New checkouts per client IP per 10 minutes. Deliberately generous: mobile carriers (CGNAT) put many
         * buyers of one ad campaign behind one public address, and an unknown IP is never limited.
         */
        private int checkoutPerIp = 20;
        /** New checkouts per (email, client IP) per hour: stops one visitor from piling up orders for an address. */
        private int pendingPerEmailAndIp = 3;
        /**
         * PENDING orders per email per hour, whoever started them. A loose ceiling only: it must stay high enough
         * that a stranger who knows an email cannot lock its owner out of buying (the strict limit is per IP).
         */
        private int pendingPerEmail = 20;
        /** Calls to the token-bearing endpoints per (client IP, token) per minute: one thank-you page polls ~15/min. */
        private int tokenCallsPerIp = 60;
        /** Calls to the token-bearing endpoints per client IP per minute, across every token. */
        private int tokenCallsPerIpTotal = 600;
        /** Access re-send requests per email per day (counted only when the email really has a paid order). */
        private int resendPerEmail = 3;
        /** Access re-send requests per client IP per hour. */
        private int resendPerIp = 30;
        /** Regret submissions per client IP per hour. */
        private int regretPerIp = 10;
        /** Regret submissions per email per day. */
        private int regretPerEmail = 2;
        /** Receipts the regret form may email to buyers in total per day, so it can never become a mail cannon. */
        private int regretReceiptsPerDay = 100;
    }

    @Getter
    @Setter
    public static class Storage {
        /** "r2", "local", or empty = r2 when every R2 credential is set, otherwise local. */
        private String type = "";
        private String localDir = "./pack-files";
        /** Public origin of THIS backend, used to build the signed URLs of the local (dev) store. */
        private String localBaseUrl = "http://localhost:8080";
        private String r2AccountId = "";
        private String r2AccessKeyId = "";
        private String r2SecretAccessKey = "";
        private String r2Bucket = "";
    }
}
