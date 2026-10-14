package com.tiam.pack.service;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.email.EmailAddresses;
import com.tiam.common.util.PhoneNumberUtil;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.config.PackSalesReadiness;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackTokens;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.subscription.service.CheckoutLine;
import com.tiam.subscription.service.CheckoutPreferenceSpec;
import com.tiam.subscription.service.CreatedPreference;
import com.tiam.subscription.service.MercadoPagoService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Starts a printable-pack purchase: validates what the form sent, prices it on the server, stores a
 * PENDING order and asks Mercado Pago for a checkout link.
 *
 * <p>Deliberately not {@code @Transactional}: the call to Mercado Pago is a network round trip and must
 * not hold a database connection (the pool is tiny). Each repository call commits on its own.
 *
 * <p>Nothing here can send email, by construction: this service has no sender and publishes no event.
 * Mail only goes out after a payment is confirmed (see PackOrderService), so the public form cannot be
 * used to mail strangers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackCheckoutService {

    private static final Duration REUSE_WINDOW = Duration.ofMinutes(15);
    private static final Duration IP_WINDOW = Duration.ofMinutes(10);
    private static final Duration PENDING_WINDOW = Duration.ofHours(1);
    private static final ZoneId ARGENTINA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final int LOCK_STRIPES = 64;
    private static final int MAX_REMEMBERED_CHECKOUTS = 5_000;

    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._%+'-]{1,64}@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}");
    private static final Pattern EXPECTED_TOTAL = Pattern.compile("\\d{1,9}([.,]\\d{1,2})?");

    private final PackOrderRepository orders;
    private final PackPricing pricing;
    private final PackProperties properties;
    private final MercadoPagoService mercadoPago;
    private final ChallengePurchaseService challengePurchases;
    private final PackRateLimiter rateLimiter;
    private final PackSalesReadiness readiness;

    /**
     * Serialises "look for a reusable checkout, else create one" per buyer, so a double click that
     * arrives as two simultaneous requests still ends up with one order. Striped (not one lock per email)
     * so the set of locks never grows. Single-instance assumption, like the rest of the backend.
     */
    private final Object[] emailLocks = newLocks();

    /**
     * Which client IP started each recent checkout. A double click is only forgiven for the SAME visitor: without
     * this, anyone who knows a buyer's email could pre-create a checkout for it (with their own name, phone and
     * order token) and have the real buyer redirected into it. In memory on purpose: after a restart nothing is
     * reused, which is the safe direction, and it costs no personal data in the database.
     */
    private final ConcurrentHashMap<Long, String> checkoutOwners = new ConcurrentHashMap<>();

    /** @return the Mercado Pago URL to send the buyer to */
    public String start(CheckoutCommand command) {
        if (!properties.isSalesEnabled()) {
            throw new PackCheckoutException(PackCheckoutError.CERRADO);
        }
        // Fail closed: a sale that cannot be fulfilled (local file store, no real email, localhost URLs, nobody to
        // tell about a problem) is refused BEFORE any order exists or any money is asked for.
        List<String> notReady = readiness.problems();
        if (!notReady.isEmpty()) {
            log.error("Pack checkout REFUSED (sales are enabled but the shop is not ready): {}", notReady);
            throw new PackCheckoutException(PackCheckoutError.CERRADO);
        }

        String email = normalizeEmail(command.email());
        String name = normalizeName(command.name());
        PackEdition edition = PackEdition.fromSlug(command.edition())
                .orElseThrow(() -> new PackCheckoutException(PackCheckoutError.EDICION));
        PackExtras extras = PackPricing.parseExtras(command.extras());
        String phone = normalizePhone(command.phone(), extras.games());
        PackPrice price = pricing.calculate(edition, extras);
        checkExpectedTotal(command.expectedTotal(), price);

        synchronized (emailLocks[Math.floorMod(email.hashCode(), LOCK_STRIPES)]) {
            Optional<PackOrder> reusable = orders.findReusable(email, edition, extras.games(), extras.extra(),
                    PackOrderStatus.PENDING, Instant.now().minus(REUSE_WINDOW)).stream()
                    .filter(candidate -> startedBy(candidate, command.clientIp()))
                    .findFirst();
            if (reusable.isPresent()) {
                log.info("Pack checkout: reusing the open checkout of order id={}", reusable.get().getId());
                return reusable.get().getInitPoint();
            }

            enforceLimits(email, command.clientIp());

            // After the limits on purpose: asked before them, "?error=meses" would let anyone probe which phone
            // numbers hold every Desafío month at no cost.
            if (extras.games() && !challengePurchases.hasMonthAvailable(phone)) {
                throw new PackCheckoutException(PackCheckoutError.MESES);
            }

            if (!mercadoPago.isConfigured()) {
                log.warn("Pack checkout refused: Mercado Pago is not configured (MP_ACCESS_TOKEN)");
                throw new PackCheckoutException(PackCheckoutError.CERRADO);
            }
            return createOrderAndCheckout(command, email, name, edition, extras, phone, price);
        }
    }

    private String createOrderAndCheckout(CheckoutCommand command, String email, String name, PackEdition edition,
            PackExtras extras, String phone, PackPrice price) {
        PackOrder order = new PackOrder();
        order.setAccessToken(PackTokens.newToken());
        order.setStatus(PackOrderStatus.PENDING);
        order.setEdition(edition);
        order.setWithGames(extras.games());
        order.setWithExtra(extras.extra());
        order.setCurrency("ARS");
        order.setPackAmount(price.pack());
        order.setGamesAmount(price.games());
        order.setExtraAmount(price.extra());
        order.setTotalAmount(price.total());
        order.setBuyerName(name);
        order.setEmail(email);
        order.setPhone(phone);
        order.setCatalogVersion(properties.getCatalogVersion());
        order.setUtmSource(cleanUtm(command.utmSource()));
        order.setUtmMedium(cleanUtm(command.utmMedium()));
        order.setUtmCampaign(cleanUtm(command.utmCampaign()));
        order.setUtmContent(cleanUtm(command.utmContent()));
        order = orders.save(order);

        try {
            CreatedPreference preference = mercadoPago.createCheckoutPreference(
                    preferenceSpec(order, edition, extras, price));
            if (!StringUtils.hasText(preference.initPoint())) {
                throw new IllegalStateException("Mercado Pago returned no init_point");
            }
            order.setMpPreferenceId(preference.id());
            order.setInitPoint(preference.initPoint());
            orders.save(order);
            rememberOwner(order, command.clientIp());
            spendEmailQuota(order.getEmail(), command.clientIp());
            log.info("Pack checkout started: order id={} edition={} total={} buyer={}",
                    order.getId(), edition, price.total(), EmailAddresses.mask(email));
            return preference.initPoint();
        } catch (MPException | MPApiException | RuntimeException e) {
            // FAILED (not PENDING) so an outage does not eat the buyer's "pending orders per hour" quota,
            // and so nothing about this order could ever be delivered.
            log.error("Could not create the Mercado Pago preference for pack order id={}: {}",
                    order.getId(), e.getMessage(), e);
            order.setStatus(PackOrderStatus.FAILED);
            orders.save(order);
            throw new PackCheckoutException(PackCheckoutError.PAGO);
        }
    }

    private CheckoutPreferenceSpec preferenceSpec(PackOrder order, PackEdition edition, PackExtras extras, PackPrice price) {
        List<CheckoutLine> lines = new ArrayList<>();
        lines.add(new CheckoutLine("pack-" + edition.slug(), "Pack TIAM - Edición " + edition.displayName(),
                "Pack imprimible de estimulación cognitiva, Edición " + edition.displayName(), price.pack()));
        if (extras.games()) {
            lines.add(new CheckoutLine("juegos", "Mes de juegos - Desafío 30 días TIAM",
                    "Un mes del Desafío 30 días de TIAM Digital", price.games()));
        }
        if (extras.extra()) {
            lines.add(new CheckoutLine("cuadernillo-extra", "Cuadernillo extra TIAM",
                    "Cuadernillo extra imprimible, Edición " + edition.displayName(), price.extra()));
        }

        OffsetDateTime from = OffsetDateTime.now(ARGENTINA).truncatedTo(ChronoUnit.MILLIS);
        OffsetDateTime until = from.plusDays(properties.getPreferenceValidityDays());
        return new CheckoutPreferenceSpec(lines, order.getEmail(), order.getBuyerName(), order.externalReference(),
                returnUrl(order), from, until, "TIAM");
    }

    private String returnUrl(PackOrder order) {
        String base = properties.getThankyouUrl();
        return base + (base.contains("?") ? "&" : "?") + "o=" + order.getAccessToken();
    }

    private boolean startedBy(PackOrder candidate, String clientIp) {
        return StringUtils.hasText(clientIp) && clientIp.equals(checkoutOwners.get(candidate.getId()));
    }

    private void rememberOwner(PackOrder order, String clientIp) {
        if (!StringUtils.hasText(clientIp)) {
            return;
        }
        if (checkoutOwners.size() >= MAX_REMEMBERED_CHECKOUTS) {
            checkoutOwners.clear(); // nothing is reused until new checkouts repopulate it: safe, just less convenient
        }
        checkoutOwners.put(order.getId(), clientIp);
    }

    /**
     * Visitor first (coarse: it protects Mercado Pago and the database, and an unknown IP is not limited), then
     * the (email, visitor) pair, which is the strict one. Keyed on the pair, a stranger who knows a buyer's email
     * can only exhaust THEIR OWN quota for it, never lock the real buyer out (they come from another address).
     * The pair's quota is spent only when a checkout was actually created ({@link #spendEmailQuota}), so a
     * Mercado Pago outage does not eat it.
     */
    private void enforceLimits(String email, String clientIp) {
        PackProperties.RateLimits limits = properties.getRateLimits();
        if (!rateLimiter.tryAcquireByIp("checkout-ip", clientIp, limits.getCheckoutPerIp(), IP_WINDOW)) {
            log.warn("Pack checkout refused: too many checkouts from ip={}", clientIp);
            throw new PackCheckoutException(PackCheckoutError.LIMITE);
        }
        if (!rateLimiter.isWithinLimit(emailQuotaKey(email, clientIp), limits.getPendingPerEmailAndIp(), PENDING_WINDOW)) {
            log.warn("Pack checkout refused: too many checkouts for {} from this visitor", EmailAddresses.mask(email));
            throw new PackCheckoutException(PackCheckoutError.LIMITE);
        }
        long pending = orders.countByEmailAndStatusAndCreatedAtAfter(
                email, PackOrderStatus.PENDING, Instant.now().minus(PENDING_WINDOW));
        if (pending >= limits.getPendingPerEmail()) {
            log.warn("Pack checkout refused: too many pending orders for {}", EmailAddresses.mask(email));
            throw new PackCheckoutException(PackCheckoutError.LIMITE);
        }
    }

    private void spendEmailQuota(String email, String clientIp) {
        rateLimiter.tryAcquire(emailQuotaKey(email, clientIp),
                properties.getRateLimits().getPendingPerEmailAndIp(), PENDING_WINDOW);
    }

    private static String emailQuotaKey(String email, String clientIp) {
        return "checkout-email-ip:" + email + "|" + (StringUtils.hasText(clientIp) ? clientIp : "-");
    }

    // --- input normalisation -------------------------------------------------------------------

    private static String normalizeEmail(String raw) {
        String email = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new PackCheckoutException(PackCheckoutError.EMAIL);
        }
        return email;
    }

    private static String normalizeName(String raw) {
        String name = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        boolean hasLetter = name.chars().anyMatch(Character::isLetter);
        boolean hasForbidden = name.chars().anyMatch(c -> Character.isISOControl(c) || c == '<' || c == '>');
        if (name.length() < 2 || name.length() > 100 || !hasLetter || hasForbidden) {
            throw new PackCheckoutException(PackCheckoutError.NOMBRE);
        }
        return name;
    }

    /** Required (and validated) only with the games add-on; otherwise kept when plausible and dropped when not. */
    private static String normalizePhone(String raw, boolean required) {
        String trimmed = raw == null ? "" : raw.strip();
        String normalized = trimmed.length() > 40 ? "" : PhoneNumberUtil.normalize(trimmed);
        boolean plausible = normalized.length() >= 12 && normalized.length() <= 15;
        if (required && !plausible) {
            throw new PackCheckoutException(PackCheckoutError.TELEFONO);
        }
        return plausible ? normalized : null;
    }

    private static void checkExpectedTotal(String raw, PackPrice price) {
        if (!StringUtils.hasText(raw)) {
            return;
        }
        String candidate = raw.strip();
        if (!EXPECTED_TOTAL.matcher(candidate).matches()
                || new BigDecimal(candidate.replace(',', '.')).compareTo(price.total()) != 0) {
            throw new PackCheckoutException(PackCheckoutError.PRECIO);
        }
    }

    private static String cleanUtm(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.chars().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }

    private static Object[] newLocks() {
        Object[] locks = new Object[LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }
}
