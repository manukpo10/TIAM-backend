package com.tiam.pack.service;

import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackEdition;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The only place a price is decided. Amounts come from configuration; whatever the browser says
 * it costs is, at most, compared against this (see the {@code expected_total} check).
 */
@Component
@RequiredArgsConstructor
public class PackPricing {

    private static final String EXTRA_GAMES = "juegos";
    private static final String EXTRA_BOOKLET = "cuadernillo";

    private final PackProperties properties;

    public PackPrice calculate(PackEdition edition, PackExtras extras) {
        BigDecimal pack = money(properties.getPriceArs(), "PACK_PRICE_ARS", true);
        BigDecimal games = extras.games() ? money(properties.getGamesPriceArs(), "PACK_GAMES_PRICE_ARS", false) : zero();
        BigDecimal extra = extras.extra() ? money(properties.getExtraPriceArs(), "PACK_EXTRA_PRICE_ARS", false) : zero();
        return new PackPrice(pack, games, extra, pack.add(games).add(extra));
    }

    /**
     * Turns the repeated {@code extras} form field into the add-ons it names. An unknown value fails
     * the whole checkout instead of being ignored: silently dropping something the buyer asked for
     * would charge them for less than they believe they are buying.
     */
    public static PackExtras parseExtras(Collection<String> raw) {
        boolean games = false;
        boolean extra = false;
        if (raw != null) {
            for (String value : raw) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String normalized = value.strip().toLowerCase(Locale.ROOT);
                switch (normalized) {
                    case EXTRA_GAMES -> games = true;
                    case EXTRA_BOOKLET -> extra = true;
                    default -> throw new PackCheckoutException(PackCheckoutError.EXTRAS, "unknown extra");
                }
            }
        }
        return new PackExtras(games, extra);
    }

    private static BigDecimal money(BigDecimal configured, String envName, boolean mustBePositive) {
        if (configured == null || configured.signum() < 0 || (mustBePositive && configured.signum() == 0)) {
            throw new IllegalStateException(envName + " must be " + (mustBePositive ? "greater than zero" : "zero or more"));
        }
        return configured.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    }
}
