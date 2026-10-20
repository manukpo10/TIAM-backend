package com.tiam.challenge.service;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.domain.ChallengeDayCatalog;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchasePaidEvent;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.dto.AdminGrantPurchaseRequest;
import com.tiam.challenge.dto.AdminGrantPurchaseResponse;
import com.tiam.challenge.dto.ChallengeAccessResponse;
import com.tiam.challenge.dto.ChallengeMonthAccess;
import com.tiam.challenge.dto.CreatePurchaseRequest;
import com.tiam.challenge.dto.CreatePurchaseResponse;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.tiam.common.exception.BadRequestException;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.common.util.PhoneNumberUtil;
import com.tiam.subscription.service.MercadoPagoService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengePurchaseService {

    private static final BigDecimal PRICE_ARS = BigDecimal.valueOf(20000);
    private static final String ITEM_TITLE = "Desafío 30 días - TIAM Digital";
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final int TOTAL_DAYS = 30;
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";
    private static final String STATUS_CANCELLED = "cancelled";
    /**
     * How many months are on sale — the ceiling for both the explicit-month
     * allowlist and {@link #nextUnpaidMonth}'s auto-assignment.
     *
     * <p>Deliberately NOT derived from how many catalogs
     * {@code ChallengeDayCatalog} declares: a month's catalog can exist before
     * the month is for sale, and selling it early would hand a buyer content
     * that isn't ready. Month 5 is exactly that today — its catalog is
     * declared, so {@link #grantManualPurchase} can mint a test link for it
     * (that only needs a catalog), while checkout still stops at month 4.
     * Raising this constant is the launch switch for the next month.
     */
    private static final int MONTHS_ON_SALE = 4;

    /** What one month of the Desafío costs on its own page (the "normal price" other products refer to). */
    public static BigDecimal monthPriceArs() {
        return PRICE_ARS;
    }

    private final ChallengePurchaseRepository challengePurchaseRepository;
    private final MercadoPagoService mercadoPagoService;
    private final WhatsAppProperties whatsAppProperties;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * One JVM-local lock per distinct phone that has ever attempted an
     * auto-assigned purchase — see the locking note in {@link #createPurchase}.
     * Never evicted: fine at this product's purchase volume, but it's an
     * unbounded map over the process lifetime, and it only serializes within
     * a single app instance. If this backend is ever horizontally scaled,
     * this needs to become a DB-level lock (e.g. a Postgres advisory lock)
     * instead.
     */
    private final ConcurrentHashMap<String, Object> autoAssignLocksByPhone = new ConcurrentHashMap<>();

    /**
     * Creates a pending purchase and a Mercado Pago checkout preference for it.
     */
    @Transactional
    public CreatePurchaseResponse createPurchase(CreatePurchaseRequest request) {
        Integer requestedMonth = request.challengeMonth();
        if (requestedMonth != null && (requestedMonth < 1 || requestedMonth > MONTHS_ON_SALE)) {
            // Checked before isConfigured()/persistence so a bad value fails fast
            // with a clean 400 instead of a 500 later when the day-catalog lookup
            // rejects it at play time.
            throw new BadRequestException("Unsupported challenge month: " + requestedMonth);
        }

        if (requestedMonth != null) {
            return createPurchaseForMonth(request, requestedMonth, null);
        }

        // Auto-assign path only (explicit-month callers have nothing to race
        // on). Locking per normalized phone closes the literal-interleaving
        // case: two truly simultaneous requests (double-tap, two open tabs)
        // both reading nextUnpaidMonth's DB query at the same instant. Held
        // for the whole purchase (through the DB save), not just the month
        // computation, so a genuine same-phone retry waits for the first
        // attempt's row to actually exist before computing its own "next"
        // month.
        //
        // What this does NOT close: nextUnpaidMonth only counts PAID rows
        // (on purpose — an abandoned PENDING checkout must not permanently
        // "claim" a month, see its own javadoc), and MP payment confirmation
        // is asynchronous (the webhook can take anywhere from under a
        // second to a couple minutes). So two purchase ATTEMPTS for the same
        // phone spaced seconds-to-minutes apart — not just millisecond-level
        // concurrent — can still both land on the same auto-assigned month
        // if the first one hasn't been confirmed PAID yet when the second
        // one runs; this lock only guarantees they don't corrupt each
        // other's read, not that the read reflects an in-flight payment.
        // Closing that fully needs a time-windowed rule (a recent PENDING
        // purchase also "claims" its month for some grace period before a
        // retry is allowed to reuse it) — a real product decision on how
        // long that grace period should be, not implemented here.
        String normalizedPhone = PhoneNumberUtil.normalize(request.phone());
        Object lock = autoAssignLocksByPhone.computeIfAbsent(normalizedPhone, key -> new Object());
        synchronized (lock) {
            int challengeMonth = nextUnpaidMonth(request.phone());
            return createPurchaseForMonth(request, challengeMonth, null);
        }
    }

    /** @param parentPurchaseId the buyer's first purchase when this month is bought from the player; null otherwise */
    private CreatePurchaseResponse createPurchaseForMonth(
            CreatePurchaseRequest request, int challengeMonth, Long parentPurchaseId) {
        if (!mercadoPagoService.isConfigured()) {
            throw new BadRequestException(
                    "Payment processing is not available yet. Please try again later.");
        }

        ChallengePurchase purchase = new ChallengePurchase();
        purchase.setBuyerName(request.buyerName());
        purchase.setPhone(PhoneNumberUtil.normalize(request.phone()));
        purchase.setEmail(request.email());
        purchase.setStatus(ChallengePurchaseStatus.PENDING);
        purchase.setAccessToken(UUID.randomUUID().toString());
        purchase.setChallengeMonth(challengeMonth);
        purchase.setParentPurchaseId(parentPurchaseId);
        purchase = challengePurchaseRepository.save(purchase);

        return startCheckout(purchase, null);
    }

    /**
     * Creates the Mercado Pago preference of a pending purchase and returns where to send the buyer.
     *
     * @param returnUrl where Mercado Pago sends the buyer back, or null for the default return page
     */
    private CreatePurchaseResponse startCheckout(ChallengePurchase purchase, String returnUrl) {
        String externalReference = String.valueOf(purchase.getId());
        String itemTitle = itemTitleFor(purchase.getChallengeMonth());

        try {
            String initPoint = returnUrl == null
                    ? mercadoPagoService.createPreference(itemTitle, PRICE_ARS, purchase.getEmail(), externalReference)
                    : mercadoPagoService.createPreference(
                            itemTitle, PRICE_ARS, purchase.getEmail(), externalReference, returnUrl);
            return new CreatePurchaseResponse(initPoint);
        } catch (MPException | MPApiException e) {
            log.error("Failed to create MP preference for purchase id={}: {}",
                    purchase.getId(), e.getMessage(), e);
            throw new BadRequestException("Could not start checkout: " + e.getMessage());
        }
    }

    /**
     * Grants a challenge month directly as PAID, with no Mercado Pago
     * transaction behind it — for a buyer who paid through another channel
     * (transferencia, efectivo) and needs their access link handed over by
     * hand instead of through the checkout flow. Mirrors what {@link #markPaid}
     * does to a pending purchase once MP confirms it, minus the MP side:
     * same PAID status, same {@code purchaseDate}-starts-now day-unlock
     * math, same access-token shape — so a manually granted buyer's
     * experience is indistinguishable from a normal one from here on.
     * {@code mpPaymentId} stays null on purpose: inventing a fake value
     * would misrepresent that no real MP transaction exists.
     *
     * <p>Accepts any month that has a catalog ({@link ChallengeDayCatalog#hasMonth}),
     * which can be AHEAD of {@link #MONTHS_ON_SALE}: that is what lets the
     * owner mint a link for a month that hasn't launched yet and try it end
     * to end. It is bounded by "can this month be played at all", not by
     * "is it for sale" — a month without a catalog is rejected here with a
     * clean 400 instead of failing later at play time.
     *
     * <p>Idempotent per (phone, month): re-running this for a buyer who
     * already has that month PAID returns their existing link instead of
     * creating a second row, so retrying after an uncertain result (e.g. a
     * dropped connection) can't double-grant.
     *
     * <p>Caller (the controller) is responsible for authenticating the
     * request — this method trusts that whoever invoked it already checked
     * that.
     */
    @Transactional
    public AdminGrantPurchaseResponse grantManualPurchase(AdminGrantPurchaseRequest request) {
        if (!ChallengeDayCatalog.hasMonth(request.challengeMonth())) {
            throw new BadRequestException("Unsupported challenge month: " + request.challengeMonth());
        }

        String normalizedPhone = PhoneNumberUtil.normalize(request.phone());
        List<ChallengePurchase> ofThisPhone = challengePurchaseRepository.findByPhoneAndActivoTrue(normalizedPhone);
        Optional<ChallengePurchase> existing = ofThisPhone.stream()
                .filter(p -> p.getStatus() == ChallengePurchaseStatus.PAID
                        && p.getChallengeMonth().equals(request.challengeMonth()))
                .findFirst();
        if (existing.isPresent()) {
            log.info("Manual grant for phone={} month={} already existed (purchase id={}) — returning it as-is",
                    normalizedPhone, request.challengeMonth(), existing.get().getId());
            return toGrantResponse(existing.get());
        }

        ChallengePurchase purchase = new ChallengePurchase();
        purchase.setBuyerName(request.buyerName());
        purchase.setPhone(normalizedPhone);
        purchase.setEmail(request.email());
        purchase.setStatus(ChallengePurchaseStatus.PAID);
        purchase.setAccessToken(UUID.randomUUID().toString());
        purchase.setChallengeMonth(request.challengeMonth());
        purchase.setPurchaseDate(Instant.now());
        // One link for the whole Desafío: a month granted to a buyer who already has a first purchase joins its chain,
        // so the owner hands over the link the buyer already uses (most buyers pay by transfer, not from the player).
        purchase.setParentPurchaseId(ofThisPhone.stream()
                .filter(p -> p.getStatus() == ChallengePurchaseStatus.PAID && p.getParentPurchaseId() == null)
                .min(Comparator.comparing(ChallengePurchase::getChallengeMonth))
                .map(ChallengePurchase::getId)
                .orElse(null));
        purchase = challengePurchaseRepository.save(purchase);

        log.info("Manually granted challenge month={} to phone={} (buyer={}, purchase id={})",
                request.challengeMonth(), normalizedPhone, request.buyerName(), purchase.getId());

        return toGrantResponse(purchase);
    }

    /**
     * Grants the next unpaid month to a phone as part of a printable-pack order that included the games
     * add-on. Modelled on {@link #grantManualPurchase}: a PAID purchase with {@code mpPaymentId} left null
     * (the Mercado Pago payment belongs to the pack order, there is no payment "of the Desafío"), and the
     * same purchase-date-starts-now day-unlock math.
     *
     * <p>Two consequences of that null payment id are deliberate: no {@link ChallengePurchasePaidEvent}
     * is published and the access-email retry job (which only picks purchases with a payment id) ignores
     * it, so the buyer gets ONE email, the pack one, which already carries this month's link.
     *
     * <p>Which month: the lowest one on sale this phone has not paid, under the same per-phone lock as
     * {@link #createPurchase}. When there is none the result is EMPTY rather than an exception: this runs
     * inside the pack order's payment transaction, and an exception crossing this transactional boundary
     * would mark that transaction rollback-only and undo a payment that really happened. The caller decides
     * what to do with an order that was paid but cannot be fully served.
     *
     * <p>Not idempotent by itself: the pack order remembers the purchase it created
     * ({@code pack_orders.challenge_purchase_id}) and never calls this twice for one order.
     *
     * <p>The phone must ALREADY be normalised ({@code pack_orders.phone} is): {@link PhoneNumberUtil#normalize}
     * is not idempotent, so running it a second time can turn a valid stored number into an unusable one. An
     * unusable phone also returns empty rather than throwing, for the same rollback-only reason.
     */
    @Transactional
    public Optional<ChallengePurchase> grantBundledMonth(String buyerName, String normalizedPhone, String email) {
        if (normalizedPhone == null || normalizedPhone.length() < 12) {
            log.error("Cannot grant a bundled challenge month: the order has no usable phone");
            return Optional.empty();
        }
        Object lock = autoAssignLocksByPhone.computeIfAbsent(normalizedPhone, key -> new Object());
        synchronized (lock) {
            Optional<Integer> challengeMonth = firstUnpaidMonth(paidMonthsForNormalized(normalizedPhone));
            if (challengeMonth.isEmpty()) {
                log.warn("Cannot grant a bundled challenge month: phone ending {} already has every month on sale",
                        lastDigits(normalizedPhone));
                return Optional.empty();
            }

            ChallengePurchase purchase = new ChallengePurchase();
            purchase.setBuyerName(buyerName);
            purchase.setPhone(normalizedPhone);
            purchase.setEmail(email);
            purchase.setStatus(ChallengePurchaseStatus.PAID);
            purchase.setAccessToken(UUID.randomUUID().toString());
            purchase.setChallengeMonth(challengeMonth.get());
            purchase.setPurchaseDate(Instant.now());
            purchase = challengePurchaseRepository.save(purchase);

            log.info("Granted challenge month={} to phone ending {} as part of a pack order (purchase id={})",
                    challengeMonth.get(), lastDigits(normalizedPhone), purchase.getId());
            return Optional.of(purchase);
        }
    }

    private static String lastDigits(String phone) {
        return phone.length() <= 4 ? "****" : "****" + phone.substring(phone.length() - 4);
    }

    /**
     * Closes a purchase (soft delete: {@code activo = false}); every access lookup filters on it. Used when
     * the pack order that bundled it is refunded or charged back. Unknown ids are ignored.
     */
    @Transactional
    public void deactivatePurchase(Long purchaseId) {
        challengePurchaseRepository.findById(purchaseId).ifPresent(purchase -> {
            purchase.setActivo(false);
            challengePurchaseRepository.save(purchase);
            log.info("Challenge purchase id={} deactivated", purchaseId);
            promoteNewHeadOfChain(purchase);
        });
    }

    /**
     * The months a buyer paid for from the player hang from their first purchase. When that first purchase is closed
     * (a refunded pack order), the lowest PAID month takes its place as the head of the chain and the others hang from
     * it, so the months that are still the buyer's keep sharing one link instead of each standing alone. Months that
     * were never paid are left as they are: nothing opens them.
     */
    private void promoteNewHeadOfChain(ChallengePurchase closed) {
        List<ChallengePurchase> children = challengePurchaseRepository.findByParentPurchaseIdAndActivoTrue(closed.getId());
        children.stream()
                .filter(p -> p.getStatus() == ChallengePurchaseStatus.PAID && p.getPurchaseDate() != null)
                .min(Comparator.comparingInt(ChallengePurchase::getChallengeMonth).thenComparing(ChallengePurchase::getId))
                .ifPresent(head -> {
                    head.setParentPurchaseId(null);
                    challengePurchaseRepository.save(head);
                    children.stream().filter(p -> p != head).forEach(p -> {
                        p.setParentPurchaseId(head.getId());
                        challengePurchaseRepository.save(p);
                    });
                    log.info("Challenge purchase id={} is now the head of the chain left by closed purchase id={}",
                            head.getId(), closed.getId());
                });
    }

    /**
     * The buyer's personal Desafío link for a PAID, active purchase, or empty. Read by the pack access
     * email and the downloads page so they can show "open my month of games".
     */
    @Transactional(readOnly = true)
    public Optional<String> findAccessLinkIfActive(Long purchaseId) {
        if (purchaseId == null) {
            return Optional.empty();
        }
        return challengePurchaseRepository.findById(purchaseId)
                .filter(p -> p.isActivo() && p.getStatus() == ChallengePurchaseStatus.PAID)
                .map(p -> {
                    String base = whatsAppProperties.getDesafioPlayBaseUrl();
                    base = base == null ? "" : base.strip();
                    while (base.endsWith("/")) {
                        base = base.substring(0, base.length() - 1);
                    }
                    return base + "/" + p.getAccessToken();
                });
    }

    private AdminGrantPurchaseResponse toGrantResponse(ChallengePurchase purchase) {
        String linkToken = linkTokenOf(purchase);
        String link = whatsAppProperties.getDesafioPlayBaseUrl() + "/" + linkToken;
        return new AdminGrantPurchaseResponse(
                linkToken, link, purchase.getBuyerName(), purchase.getChallengeMonth());
    }

    /**
     * The lowest month on sale (1 through {@link #MONTHS_ON_SALE}) this phone
     * doesn't already have a PAID purchase for — first-time buyers get 1, a
     * phone that already has month 1 PAID gets 2, and so on. Only PAID rows
     * count: an abandoned PENDING checkout or a FAILED payment for a month
     * doesn't block buying that same month again. Throws once every month on
     * sale is already PAID — there's no further month to fall back to, and
     * silently reassigning an already-owned month would charge the buyer
     * again for nothing new. A month beyond the ones on sale that the phone
     * already holds (a manual grant ahead of launch) is neither offered nor
     * counted: the loop never looks past {@link #MONTHS_ON_SALE}.
     *
     * <p>Only ever called from inside {@link #createPurchase}'s per-phone
     * {@code synchronized} block — calling it unguarded would reopen the
     * exact race that locking exists to close.
     */
    private int nextUnpaidMonth(String rawPhone) {
        Set<Integer> paidMonths = paidMonthsFor(rawPhone);
        for (int month = 1; month <= MONTHS_ON_SALE; month++) {
            if (!paidMonths.contains(month)) {
                return month;
            }
        }
        throw new BadRequestException(
                "Ya tenés los " + MONTHS_ON_SALE + " meses del Desafío activados — no queda ningún mes nuevo para comprar.");
    }

    /**
     * Whether this phone can still be granted another month on sale. The printable-pack checkout asks
     * this before charging for the games add-on, so nobody pays for a month that cannot be delivered.
     * Read-only and advisory: the real assignment happens at payment time ({@link #grantBundledMonth}).
     */
    @Transactional(readOnly = true)
    public boolean hasMonthAvailable(String rawPhone) {
        return firstUnpaidMonth(paidMonthsFor(rawPhone)).isPresent();
    }

    /**
     * Whether an active PAID purchase carries this email. Used by the regret form to decide whether the sender
     * is a real customer (and so gets a receipt by email) or an unknown address (which gets none).
     */
    @Transactional(readOnly = true)
    public boolean hasPaidPurchaseWithEmail(String email) {
        return email != null && !email.isBlank()
                && challengePurchaseRepository.existsByEmailIgnoreCaseAndStatusAndActivoTrue(email.strip(), ChallengePurchaseStatus.PAID);
    }

    /** The lowest month on sale not in {@code paidMonths}, or empty when every month on sale is taken. */
    private static Optional<Integer> firstUnpaidMonth(Set<Integer> paidMonths) {
        for (int month = 1; month <= MONTHS_ON_SALE; month++) {
            if (!paidMonths.contains(month)) {
                return Optional.of(month);
            }
        }
        return Optional.empty();
    }

    private Set<Integer> paidMonthsFor(String rawPhone) {
        String normalized = PhoneNumberUtil.normalize(rawPhone);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        return paidMonthsForNormalized(normalized);
    }

    private Set<Integer> paidMonthsForNormalized(String normalizedPhone) {
        return challengePurchaseRepository.findByPhoneAndActivoTrue(normalizedPhone).stream()
                .filter(p -> p.getStatus() == ChallengePurchaseStatus.PAID)
                .map(ChallengePurchase::getChallengeMonth)
                .collect(Collectors.toSet());
    }

    /**
     * Month 1 keeps the original title untouched (no buyer-facing behavior change).
     * Month 2+ appends " - Mes {N}" so the buyer's Mercado Pago receipt/checkout
     * screen distinguishes it from a month-1 purchase — this is presentation only,
     * it has no bearing on which catalog {@link #createPurchase} actually grants.
     */
    private String itemTitleFor(int challengeMonth) {
        return challengeMonth > 1 ? ITEM_TITLE + " - Mes " + challengeMonth : ITEM_TITLE;
    }

    /**
     * Resolves the buyer's current progress in the 30-day challenge from their
     * access token. Only PAID purchases with a recorded purchase date grant
     * access — anything else (unknown token, still PENDING, FAILED) is treated
     * as not found so we don't leak purchase state to an unauthenticated caller.
     */
    @Transactional(readOnly = true)
    public ChallengeAccessResponse getAccess(String accessToken) {
        ChallengePurchase purchase = resolvePaidPurchase(accessToken);
        int currentDay = computeCurrentDay(purchase.getPurchaseDate());
        List<ChallengePurchase> chain = paidChainOf(purchase);
        Integer nextMonth = nextMonthFor(chain);

        return new ChallengeAccessResponse(
                firstName(purchase.getBuyerName()), currentDay, TOTAL_DAYS, purchase.getChallengeMonth(),
                chain.stream()
                        .map(p -> new ChallengeMonthAccess(
                                p.getChallengeMonth(), p.getAccessToken(), computeCurrentDay(p.getPurchaseDate()), TOTAL_DAYS))
                        .toList(),
                nextMonth,
                nextMonth == null ? null : PRICE_ARS.intValueExact());
    }

    /** The newest month's value of a contact field that is not blank (the buyer's latest data), or null. */
    private static String contactOnFile(List<ChallengePurchase> chain, java.util.function.Function<ChallengePurchase, String> field) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            String value = field.apply(chain.get(i));
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Continues the Desafío with the next month, bought from the player near the end of the current one, with a single
     * tap: the buyer types nothing. Name, WhatsApp number and email are the ones on the purchase (the newest month's),
     * so the number that asks for the link is guaranteed to match. The new month is its own pending purchase (own start
     * date, own progress) chained to the first purchase, and Mercado Pago sends the buyer back to their OWN link, where
     * the new month appears once the payment is confirmed. A pending purchase of that month is reused, so tapping twice
     * never piles up rows — and before it is charged again Mercado Pago is asked whether that purchase already has a
     * payment: an approved one unlocks the month right here (the buyer is sent to their link instead of paying twice),
     * one still in process refuses the tap. Concurrent taps queue on a row lock of the buyer's first purchase.
     *
     * @param expectedMonth the month the player offered; refused if it is no longer the next one (a stale page)
     * @throws ResourceNotFoundException unknown or unpaid token
     * @throws BadRequestException       no month left to buy, a stale offer, no WhatsApp on file, or checkout unavailable
     */
    @Transactional
    public CreatePurchaseResponse createNextMonthPurchase(String accessToken, Integer expectedMonth) {
        ChallengePurchase purchase = resolvePaidPurchase(accessToken);
        ChallengePurchase first = firstPurchaseOf(purchase);
        // Held until this transaction ends: a second tap (another tab, a double tap) waits here, then finds the
        // pending row the first one created instead of creating its own.
        challengePurchaseRepository.findWithLockById(first.getId());
        List<ChallengePurchase> chain = paidChainOf(purchase);
        Integer nextMonth = nextMonthFor(chain);
        if (nextMonth == null) {
            throw new BadRequestException("No queda ningún mes nuevo del Desafío para comprar.");
        }
        if (expectedMonth != null && !expectedMonth.equals(nextMonth)) {
            throw new BadRequestException("Tu Desafío cambió mientras tanto. Recargá la página para ver cómo sigue.");
        }
        String phone = contactOnFile(chain, ChallengePurchase::getPhone);
        if (phone == null || PhoneNumberUtil.normalize(phone).isEmpty()) {
            throw new BadRequestException("No encontramos un WhatsApp en tu compra. Escribinos y te ayudamos.");
        }
        if (!mercadoPagoService.isConfigured()) {
            throw new BadRequestException("El pago online no está disponible por ahora. Probá de nuevo en un rato.");
        }
        String email = contactOnFile(chain, ChallengePurchase::getEmail);

        Optional<ChallengePurchase> pending = challengePurchaseRepository.findByParentPurchaseIdAndActivoTrue(first.getId()).stream()
                .filter(p -> p.getStatus() == ChallengePurchaseStatus.PENDING && nextMonth.equals(p.getChallengeMonth()))
                .findFirst();
        if (pending.isPresent()) {
            String unlockedUrl = settleEarlierPayment(pending.get(), first);
            if (unlockedUrl != null) {
                return new CreatePurchaseResponse(unlockedUrl);
            }
        }
        ChallengePurchase renewal = pending.orElseGet(() -> {
            ChallengePurchase created = new ChallengePurchase();
            created.setBuyerName(first.getBuyerName());
            created.setStatus(ChallengePurchaseStatus.PENDING);
            created.setAccessToken(UUID.randomUUID().toString());
            created.setChallengeMonth(nextMonth);
            created.setParentPurchaseId(first.getId());
            return created;
        });
        renewal.setPhone(PhoneNumberUtil.normalize(phone));
        renewal.setEmail(email);
        renewal = challengePurchaseRepository.save(renewal);

        try {
            return startCheckout(renewal, returnUrlFor(first));
        } catch (BadRequestException e) {
            // startCheckout already logged what Mercado Pago said; the buyer reads a plain sentence, not its words.
            throw new BadRequestException("No pudimos abrir Mercado Pago. Probá de nuevo en un momento.");
        }
    }

    /**
     * Looks, before charging a pending month again, at what Mercado Pago already holds for it. Charging twice for the
     * same month is the one wrong outcome, so anything unclear refuses the tap.
     *
     * @return where to send the buyer when that payment turned out to be approved (the month is marked PAID here, just
     *         as the webhook would), or null when no payment is in flight and a new checkout may start
     * @throws BadRequestException a payment of this month is still being processed, or its state cannot be read
     */
    private String settleEarlierPayment(ChallengePurchase pending, ChallengePurchase first) {
        Optional<Payment> found;
        try {
            found = mercadoPagoService.findLatestPaymentByExternalReference(String.valueOf(pending.getId()));
        } catch (MPException | MPApiException e) {
            log.warn("Could not check the earlier payment of pending challenge purchase id={}: {}",
                    pending.getId(), e.getMessage());
            throw new BadRequestException(
                    "No pudimos confirmar el estado de tu pago anterior. Probá de nuevo en unos minutos.");
        }
        if (found.isEmpty()) {
            return null;
        }
        Payment payment = found.get();
        String status = payment.getStatus();
        if (STATUS_APPROVED.equals(status)) {
            log.info("Challenge purchase id={} already has an approved MP payment ({}) — marking PAID instead of charging again",
                    pending.getId(), payment.getId());
            markPaid(pending.getId(), String.valueOf(payment.getId()));
            return ownLinkUrl(first) + "?status=approved";
        }
        if (STATUS_REJECTED.equals(status) || STATUS_CANCELLED.equals(status)) {
            return null;
        }
        throw new BadRequestException("Tenés un pago de este mes en proceso. Cuando se acredite se desbloquea solo; "
                + "si pasó más de un día y no se acreditó, escribinos.");
    }

    /** The buyer's own link: the public one when the base is configured, otherwise the player route on the current site. */
    private String ownLinkUrl(ChallengePurchase first) {
        String publicLink = returnUrlFor(first);
        return publicLink != null ? publicLink : "/desafio/" + first.getAccessToken();
    }

    /**
     * Where Mercado Pago sends a renewing buyer back: their own link. Only a public https base can be a return
     * address; otherwise (local development) the default return page is used.
     */
    private String returnUrlFor(ChallengePurchase first) {
        String base = whatsAppProperties.getDesafioPlayBaseUrl();
        if (base == null || !base.strip().toLowerCase().startsWith("https://")) {
            return null;
        }
        String trimmed = base.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/" + first.getAccessToken();
    }

    /** The buyer's first purchase: the one a later month points at, or the purchase itself when it is the first. */
    private ChallengePurchase firstPurchaseOf(ChallengePurchase purchase) {
        if (purchase.getParentPurchaseId() == null) {
            return purchase;
        }
        // A deactivated first purchase (a refunded pack order) opens nothing: the month then stands on its own.
        return challengePurchaseRepository.findById(purchase.getParentPurchaseId())
                .filter(ChallengePurchase::isActivo)
                .orElse(purchase);
    }

    /** The first purchase plus the PAID months bought from the player, lowest month first. */
    private List<ChallengePurchase> paidChainOf(ChallengePurchase purchase) {
        ChallengePurchase first = firstPurchaseOf(purchase);
        List<ChallengePurchase> chain = new java.util.ArrayList<>();
        chain.add(first);
        chain.addAll(challengePurchaseRepository.findByParentPurchaseIdAndActivoTrue(first.getId()));
        // One entry per month (two taps that both got paid must not show two tabs): the earliest purchase wins.
        java.util.Map<Integer, ChallengePurchase> byMonth = new java.util.TreeMap<>();
        chain.stream()
                .filter(p -> p.isActivo() && p.getStatus() == ChallengePurchaseStatus.PAID && p.getPurchaseDate() != null)
                .forEach(p -> byMonth.merge(p.getChallengeMonth(), p,
                        (a, b) -> b.getPurchaseDate().isBefore(a.getPurchaseDate()) ? b : a));
        return List.copyOf(byMonth.values());
    }

    /**
     * The month the player may offer after the ones in {@code chain}: the one right after the highest owned, as long
     * as it is on sale and no purchase under any of the chain's phones already holds it (a month bought separately,
     * before the single link existed, must not be sold twice). Null when there is nothing to offer.
     */
    private Integer nextMonthFor(List<ChallengePurchase> chain) {
        int candidate = chain.stream().mapToInt(ChallengePurchase::getChallengeMonth).max().orElse(0) + 1;
        if (candidate > MONTHS_ON_SALE) {
            return null;
        }
        boolean ownedByPhone = chain.stream()
                .map(ChallengePurchase::getPhone)
                .filter(phone -> phone != null && !phone.isBlank())
                .distinct()
                .anyMatch(phone -> paidMonthsForNormalized(phone).contains(candidate));
        return ownedByPhone ? null : candidate;
    }

    /** The token of the link a purchase's buyer opens: the first purchase's, whichever month this purchase is. */
    String linkTokenOf(ChallengePurchase purchase) {
        return ChallengeLinkTokens.of(purchase, challengePurchaseRepository);
    }

    /**
     * Resolves an access token to its PAID purchase, or throws 404 —
     * deliberately not distinguishing "unknown token" from "known but
     * unpaid/pending/failed" so an unauthenticated caller can't enumerate
     * which tokens exist or their status. Shared with
     * {@link com.tiam.challenge.service.ChallengeDayResultService}, which
     * needs the exact same anti-enumeration behavior — package-private on
     * purpose, not part of the public cross-package API.
     */
    @Transactional(readOnly = true)
    ChallengePurchase resolvePaidPurchase(String accessToken) {
        ChallengePurchase purchase = challengePurchaseRepository.findByAccessTokenAndActivoTrue(accessToken)
                .orElseThrow(() -> new ResourceNotFoundException("Challenge access not found: " + accessToken));

        if (purchase.getStatus() != ChallengePurchaseStatus.PAID || purchase.getPurchaseDate() == null) {
            throw new ResourceNotFoundException("Challenge access not found: " + accessToken);
        }

        return purchase;
    }

    /**
     * Package-private (not private) so {@link ChallengeDayResultService} can
     * reuse the exact same day-unlock arithmetic — no client input, so it's
     * safe to expose within the package without extra validation.
     */
    int computeCurrentDay(Instant purchaseDate) {
        LocalDate purchaseDay = purchaseDate.atZone(ZONE).toLocalDate();
        LocalDate today = LocalDate.now(ZONE);
        long elapsed = ChronoUnit.DAYS.between(purchaseDay, today);
        long weeksElapsed = Math.floorDiv(elapsed, 7L);
        long unlockedThroughBatch = (weeksElapsed + 1) * 7;
        return (int) Math.max(1, Math.min(TOTAL_DAYS, unlockedThroughBatch));
    }

    private String firstName(String buyerName) {
        if (buyerName == null || buyerName.isBlank()) {
            return "";
        }
        String first = buyerName.trim().split("\\s+")[0];
        // Capitalize so a name typed lowercase/uppercase still greets tidily ("maria" → "Maria").
        return first.substring(0, 1).toUpperCase() + first.substring(1).toLowerCase();
    }

    /**
     * Finds the PAID purchase a phone number should keep hearing about via
     * WhatsApp. Prefers the lowest-numbered month that's still IN PROGRESS
     * (day &lt; {@link #TOTAL_DAYS}) over the most recently purchased one —
     * a buyer who bought month 2 while still mid-way through month 1 keeps
     * getting month 1's daily "desafío" replies until it's actually done,
     * only then moving on to month 2. Two PAID purchases for the SAME month
     * (e.g. a support-assisted duplicate) still tie-break by most recent
     * purchaseDate, matching the single-month behavior this replaced. If
     * every PAID purchase is already finished, falls back to the most
     * recently purchased one (so a buyer who completed everything still
     * gets a sensible "you're done" message instead of an arbitrary earlier
     * one). Already normalized or raw phone accepted — this normalizes
     * internally.
     */
    public Optional<ChallengePurchase> findActiveByPhone(String rawPhone) {
        String normalized = PhoneNumberUtil.normalize(rawPhone);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        List<ChallengePurchase> paid = challengePurchaseRepository.findByPhoneAndActivoTrue(normalized).stream()
                .filter(p -> p.getStatus() == ChallengePurchaseStatus.PAID && p.getPurchaseDate() != null)
                .toList();
        Comparator<ChallengePurchase> byMonthThenMostRecentFirst = Comparator
                .comparing(ChallengePurchase::getChallengeMonth)
                .thenComparing(Comparator.comparing(ChallengePurchase::getPurchaseDate).reversed());
        return paid.stream()
                .filter(p -> computeCurrentDay(p.getPurchaseDate()) < TOTAL_DAYS)
                .min(byMonthThenMostRecentFirst)
                .or(() -> paid.stream().max(Comparator.comparing(ChallengePurchase::getPurchaseDate)));
    }

    /**
     * Builds the WhatsApp reply text for an inbound message from the given phone
     * number:
     * <ul>
     *   <li>a matching PAID purchase still mid-challenge gets today's exercise
     *       link plus a note that the same link unlocks a new batch of 7
     *       exercises every week — there's no per-day token, so re-visiting it
     *       once the next batch opens just works;</li>
     *   <li>a matching PAID purchase on day 30 gets a completion message instead
     *       of that weekly-batch note;</li>
     *   <li>no PAID match but a PENDING purchase for the phone gets a
     *       payment-confirmation-in-progress message;</li>
     *   <li>no purchase at all gets a sales-page nudge.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public String buildWhatsAppReply(String rawFromPhone) {
        Optional<ChallengePurchase> activePurchase = findActiveByPhone(rawFromPhone);
        if (activePurchase.isPresent()) {
            ChallengePurchase purchase = activePurchase.get();
            int currentDay = computeCurrentDay(purchase.getPurchaseDate());
            String firstName = firstName(purchase.getBuyerName());
            String link = whatsAppProperties.getDesafioPlayBaseUrl() + "/" + linkTokenOf(purchase);

            // Several months under one link: no single "Día X" is true for all of them, so list the months instead.
            List<ChallengePurchase> chain = paidChainOf(purchase);
            if (chain.size() > 1) {
                return "¡Hola " + firstName + "! 👋 Acá está tu Desafío 30 días:\n" + link
                        + "\n\nTenés los meses " + joinMonths(chain) + " en este mismo link: elegí el que quieras arriba "
                        + "y hacé los ejercicios a tu ritmo. 🌱";
            }

            if (currentDay < TOTAL_DAYS) {
                return "¡Hola " + firstName + "! 👋 Acá está tu ejercicio del Día " + currentDay + " de " + TOTAL_DAYS + ":\n" + link
                        + "\n\nTocá el link, hacelo con calma (son unos minutos) y listo por hoy. 🌱"
                        + "\n\n📌 Entrá cuando quieras a este mismo link: cada semana se desbloquean 7 ejercicios nuevos.";
            }

            return "¡Hola " + firstName + "! 👋 Llegaste al Día " + TOTAL_DAYS + " de " + TOTAL_DAYS + ", tu último ejercicio:\n" + link
                    + "\n\n🎉 ¡Completaste el Desafío 30 días! Fueron 30 días cuidando tu mente. Gracias por acompañarnos. 💙";
        }

        if (hasPendingPurchase(rawFromPhone)) {
            return "¡Hola! 👋 Estamos confirmando tu pago — puede tardar unos minutos."
                    + "\n\nEn cuanto se acredite, escribinos \"desafío\" de nuevo y te mando tu primer ejercicio. 🙌";
        }

        return "¡Hola! No encontramos ninguna compra activa asociada a este número. "
                + "Conocé el Desafío 30 días de TIAM acá: " + whatsAppProperties.getSalesPageUrl();
    }

    /** "1 y 2", "1, 2 y 3". */
    private static String joinMonths(List<ChallengePurchase> chain) {
        List<String> months = chain.stream().map(p -> String.valueOf(p.getChallengeMonth())).toList();
        return months.size() == 1
                ? months.get(0)
                : String.join(", ", months.subList(0, months.size() - 1)) + " y " + months.get(months.size() - 1);
    }

    /**
     * True if the phone has a still-PENDING purchase (payment not yet confirmed
     * by the MP webhook) — used to distinguish "we're waiting on your payment"
     * from "you've never purchased" in {@link #buildWhatsAppReply}.
     */
    private boolean hasPendingPurchase(String rawPhone) {
        String normalized = PhoneNumberUtil.normalize(rawPhone);
        if (normalized.isEmpty()) {
            return false;
        }
        return challengePurchaseRepository.findByPhoneAndActivoTrue(normalized).stream()
                .anyMatch(p -> p.getStatus() == ChallengePurchaseStatus.PENDING);
    }

    /**
     * Called from the webhook handler when an MP payment is approved.
     * Idempotent: re-marking an already-PAID purchase is a no-op, so duplicate
     * webhook deliveries never trigger a second WhatsApp/delivery side effect.
     */
    @Transactional
    public void markPaid(Long purchaseId, String mpPaymentId) {
        ChallengePurchase purchase = challengePurchaseRepository.findWithLockById(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Challenge purchase not found: " + purchaseId));

        if (purchase.getStatus() == ChallengePurchaseStatus.PAID) {
            log.info("Challenge purchase id={} already PAID — ignoring duplicate webhook", purchaseId);
            return;
        }

        purchase.setStatus(ChallengePurchaseStatus.PAID);
        purchase.setPurchaseDate(Instant.now());
        purchase.setMpPaymentId(mpPaymentId);
        challengePurchaseRepository.save(purchase);
        log.info("Challenge purchase id={} marked PAID via MP payment={}", purchaseId, mpPaymentId);

        // The buyer's access email is sent from a listener that only runs AFTER this
        // transaction commits (and off this thread), so a slow/failing email provider
        // can neither hold this row lock nor roll back the payment. Reaching this line
        // means the PENDING/FAILED -> PAID transition just happened under the row lock,
        // so the event fires once per purchase; duplicate webhooks and reconciliation
        // re-runs return early above.
        eventPublisher.publishEvent(new ChallengePurchasePaidEvent(purchase.getId()));

        // TODO(whatsapp): proactive day-1 delivery over WhatsApp once the WhatsApp
        // Business Platform number is registered and approved. Until then the buyer
        // gets the link by email and can still ask for it on WhatsApp.
        // See engram integration/whatsapp-business-platform.
    }

    /**
     * Called from the webhook handler when an MP payment is rejected/cancelled.
     */
    @Transactional
    public void markFailed(Long purchaseId) {
        ChallengePurchase purchase = challengePurchaseRepository.findWithLockById(purchaseId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Challenge purchase not found: " + purchaseId));

        if (purchase.getStatus() == ChallengePurchaseStatus.PAID) {
            log.warn("MP reported failure for purchase id={} but it's already PAID — ignoring", purchaseId);
            return;
        }

        purchase.setStatus(ChallengePurchaseStatus.FAILED);
        challengePurchaseRepository.save(purchase);
        log.info("Challenge purchase id={} marked FAILED", purchaseId);
    }
}
