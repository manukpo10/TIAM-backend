package com.tiam.pack.config;

import com.tiam.common.email.EmailSender;
import com.tiam.pack.storage.PrivateFileStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The fail-closed gate in front of the checkout. Sales may be switched on ({@code PACK_SALES_ENABLED=true}), but a
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
 * <p>{@code PACK_DEV_MODE=true} lifts these requirements for local development only; the {@code prod} profile
 * ignores it.
 */
@Component
public class PackSalesReadiness {

    private final PackProperties properties;
    private final PrivateFileStore store;
    private final EmailSender emailSender;
    private final Environment environment;

    public PackSalesReadiness(PackProperties properties, PrivateFileStore store, EmailSender emailSender,
            Environment environment) {
        this.properties = properties;
        this.store = store;
        this.emailSender = emailSender;
        this.environment = environment;
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

    private static void requirePublicHttps(List<String> problems, String variable, String value) {
        if (!PackUrls.isPublicHttps(value)) {
            problems.add(variable + " is \"" + value + "\" (empty, localhost or not https): Mercado Pago or the "
                    + "buyer would be sent to an address that does not work");
        }
    }
}
