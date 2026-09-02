package com.tiam.challenge.service;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.dto.ChallengeAccessResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengePurchaseService {

    private static final BigDecimal PRICE_ARS = BigDecimal.valueOf(100);
    private static final String ITEM_TITLE = "Desafío 30 días - TIAM Digital";
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final int TOTAL_DAYS = 30;

    private final ChallengePurchaseRepository challengePurchaseRepository;
    private final MercadoPagoService mercadoPagoService;
    private final WhatsAppProperties whatsAppProperties;

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
        if (requestedMonth != null && requestedMonth != 1 && requestedMonth != 2 && requestedMonth != 3) {
            // Small allowlist check, not a generic range validator — only months 1-3
            // exist today. Checked before isConfigured()/persistence so a bad
            // value fails fast with a clean 400 instead of a 500 later when the
            // day-catalog lookup rejects it at play time.
            throw new BadRequestException("Unsupported challenge month: " + requestedMonth);
        }

        if (requestedMonth != null) {
            return createPurchaseForMonth(request, requestedMonth);
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
            return createPurchaseForMonth(request, challengeMonth);
        }
    }

    private CreatePurchaseResponse createPurchaseForMonth(CreatePurchaseRequest request, int challengeMonth) {
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
        purchase = challengePurchaseRepository.save(purchase);

        String externalReference = String.valueOf(purchase.getId());
        String itemTitle = itemTitleFor(challengeMonth);

        try {
            String initPoint = mercadoPagoService.createPreference(
                    itemTitle, PRICE_ARS, request.email(), externalReference);
            return new CreatePurchaseResponse(initPoint);
        } catch (MPException | MPApiException e) {
            log.error("Failed to create MP preference for purchase id={}: {}",
                    purchase.getId(), e.getMessage(), e);
            throw new BadRequestException("Could not start checkout: " + e.getMessage());
        }
    }

    /**
     * The lowest month (1-3) this phone doesn't already have a PAID purchase
     * for — first-time buyers get 1, a phone that already has month 1 PAID
     * gets 2, and so on. Only PAID rows count: an abandoned PENDING checkout
     * or a FAILED payment for a month doesn't block buying that same month
     * again. Throws if all 3 are already PAID — there's no month 4 to fall
     * back to, and silently reassigning an already-owned month would charge
     * the buyer again for nothing new.
     *
     * <p>Only ever called from inside {@link #createPurchase}'s per-phone
     * {@code synchronized} block — calling it unguarded would reopen the
     * exact race that locking exists to close.
     */
    private int nextUnpaidMonth(String rawPhone) {
        Set<Integer> paidMonths = paidMonthsFor(rawPhone);
        for (int month = 1; month <= 3; month++) {
            if (!paidMonths.contains(month)) {
                return month;
            }
        }
        throw new BadRequestException(
                "Ya tenés los 3 meses del Desafío activados — no queda ningún mes nuevo para comprar.");
    }

    private Set<Integer> paidMonthsFor(String rawPhone) {
        String normalized = PhoneNumberUtil.normalize(rawPhone);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        return challengePurchaseRepository.findByPhoneAndActivoTrue(normalized).stream()
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

        return new ChallengeAccessResponse(
                firstName(purchase.getBuyerName()), currentDay, TOTAL_DAYS, purchase.getChallengeMonth());
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
        return (int) Math.max(1, Math.min(TOTAL_DAYS, elapsed + 1));
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
     *       link plus a note that the same link updates daily — there's no
     *       per-day token, so re-visiting it tomorrow just works;</li>
     *   <li>a matching PAID purchase on day 30 gets a completion message instead
     *       of that daily-link note;</li>
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
            String link = whatsAppProperties.getDesafioPlayBaseUrl() + "/" + purchase.getAccessToken();

            if (currentDay < TOTAL_DAYS) {
                return "¡Hola " + firstName + "! 👋 Acá está tu ejercicio del Día " + currentDay + " de " + TOTAL_DAYS + ":\n" + link
                        + "\n\nTocá el link, hacelo con calma (son unos minutos) y listo por hoy. 🌱"
                        + "\n\n📌 Entrá todos los días a este mismo link: te va a aparecer un juego nuevo cada día.";
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

        // TODO(whatsapp): trigger day-1 delivery once the WhatsApp Business Platform
        // number is registered and approved. See engram integration/whatsapp-business-platform.
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
