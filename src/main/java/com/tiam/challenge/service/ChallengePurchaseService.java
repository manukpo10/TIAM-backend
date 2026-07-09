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
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengePurchaseService {

    private static final BigDecimal PRICE_ARS = BigDecimal.valueOf(15000);
    private static final String ITEM_TITLE = "Desafío 30 días - TIAM Digital";
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final int TOTAL_DAYS = 30;

    private final ChallengePurchaseRepository challengePurchaseRepository;
    private final MercadoPagoService mercadoPagoService;
    private final WhatsAppProperties whatsAppProperties;

    /**
     * Creates a pending purchase and a Mercado Pago checkout preference for it.
     */
    @Transactional
    public CreatePurchaseResponse createPurchase(CreatePurchaseRequest request) {
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
        purchase = challengePurchaseRepository.save(purchase);

        String externalReference = String.valueOf(purchase.getId());

        try {
            String initPoint = mercadoPagoService.createPreference(
                    ITEM_TITLE, PRICE_ARS, request.email(), externalReference);
            return new CreatePurchaseResponse(initPoint);
        } catch (MPException | MPApiException e) {
            log.error("Failed to create MP preference for purchase id={}: {}",
                    purchase.getId(), e.getMessage(), e);
            throw new BadRequestException("Could not start checkout: " + e.getMessage());
        }
    }

    /**
     * Resolves the buyer's current progress in the 30-day challenge from their
     * access token. Only PAID purchases with a recorded purchase date grant
     * access — anything else (unknown token, still PENDING, FAILED) is treated
     * as not found so we don't leak purchase state to an unauthenticated caller.
     */
    @Transactional(readOnly = true)
    public ChallengeAccessResponse getAccess(String accessToken) {
        ChallengePurchase purchase = challengePurchaseRepository.findByAccessTokenAndActivoTrue(accessToken)
                .orElseThrow(() -> new ResourceNotFoundException("Challenge access not found: " + accessToken));

        if (purchase.getStatus() != ChallengePurchaseStatus.PAID || purchase.getPurchaseDate() == null) {
            throw new ResourceNotFoundException("Challenge access not found: " + accessToken);
        }

        int currentDay = computeCurrentDay(purchase.getPurchaseDate());

        return new ChallengeAccessResponse(firstName(purchase.getBuyerName()), currentDay, TOTAL_DAYS);
    }

    private int computeCurrentDay(Instant purchaseDate) {
        LocalDate purchaseDay = purchaseDate.atZone(ZONE).toLocalDate();
        LocalDate today = LocalDate.now(ZONE);
        long elapsed = ChronoUnit.DAYS.between(purchaseDay, today);
        return (int) Math.max(1, Math.min(TOTAL_DAYS, elapsed + 1));
    }

    private String firstName(String buyerName) {
        if (buyerName == null || buyerName.isBlank()) {
            return buyerName == null ? "" : buyerName;
        }
        return buyerName.trim().split("\\s+")[0];
    }

    /**
     * Finds the most recent active, PAID purchase for a phone number (already
     * normalized or raw — this normalizes internally). Used by the WhatsApp
     * webhook to correlate an inbound message with a purchase.
     */
    public Optional<ChallengePurchase> findActiveByPhone(String rawPhone) {
        String normalized = PhoneNumberUtil.normalize(rawPhone);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return challengePurchaseRepository.findByPhoneAndActivoTrue(normalized).stream()
                .filter(p -> p.getStatus() == ChallengePurchaseStatus.PAID && p.getPurchaseDate() != null)
                .max(Comparator.comparing(ChallengePurchase::getPurchaseDate));
    }

    /**
     * Builds the WhatsApp reply text for an inbound message from the given phone
     * number:
     * <ul>
     *   <li>a matching PAID purchase still mid-challenge gets today's exercise
     *       link plus a reminder to write back tomorrow;</li>
     *   <li>a matching PAID purchase on day 30 gets a completion message instead
     *       of a "come back tomorrow" line;</li>
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
                return "¡Hola " + firstName + "! 👋 Tu ejercicio de hoy (Día " + currentDay + " de " + TOTAL_DAYS
                        + ") te espera acá: " + link
                        + "\n\n📌 Mañana escribinos \"desafío\" de nuevo y te paso el Día " + (currentDay + 1) + ".";
            }

            return "¡Hola " + firstName + "! 👋 Este es tu último ejercicio (Día " + TOTAL_DAYS + " de " + TOTAL_DAYS
                    + "): " + link
                    + "\n\n🎉 ¡Completaste el Desafío 30 días! Gracias por acompañarnos.";
        }

        if (hasPendingPurchase(rawFromPhone)) {
            return "¡Hola! 👋 Estamos confirmando tu pago. En cuanto se acredite, escribinos \"desafío\" de nuevo"
                    + " y te mando tu primer ejercicio. 🙌";
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
