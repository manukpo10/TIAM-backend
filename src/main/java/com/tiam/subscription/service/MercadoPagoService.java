package com.tiam.subscription.service;

import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.preapproval.PreApprovalAutoRecurringCreateRequest;
import com.mercadopago.client.preapproval.PreapprovalClient;
import com.mercadopago.client.preapproval.PreapprovalCreateRequest;
import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferencePayerRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.MPResultsResourcesPage;
import com.mercadopago.net.MPSearchRequest;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preapproval.Preapproval;
import com.mercadopago.resources.preference.Preference;
import com.tiam.subscription.config.ChallengeProperties;
import com.tiam.subscription.config.MercadoPagoProperties;
import com.tiam.subscription.config.PlansProperties;
import com.tiam.subscription.domain.ProfessionalPlan;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class MercadoPagoService {

    private final MercadoPagoProperties mpProperties;
    private final PlansProperties plansProperties;
    private final ChallengeProperties challengeProperties;

    /**
     * Initializes the MP SDK with the configured access token.
     * Called lazily — only when an MP operation is needed.
     * Throws IllegalStateException if token is not configured.
     */
    private void initSdk() {
        String token = mpProperties.getAccessToken();
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException(
                    "Mercado Pago is not configured yet. Set MP_ACCESS_TOKEN environment variable.");
        }
        MercadoPagoConfig.setAccessToken(token);
    }

    /**
     * Creates a recurring preapproval (subscription) for the given plan.
     *
     * @param plan              the professional plan
     * @param payerEmail        the subscriber's email
     * @param externalReference the local subscription id (for webhook correlation)
     * @return the init_point URL to redirect the user to MP checkout
     */
    public String createPreapproval(ProfessionalPlan plan, String payerEmail,
            String externalReference) throws MPException, MPApiException {
        initSdk();

        PreApprovalAutoRecurringCreateRequest autoRecurring =
                PreApprovalAutoRecurringCreateRequest.builder()
                        .frequency(plan.getFrequency())
                        .frequencyType(plan.getFrequencyType())
                        .transactionAmount(BigDecimal.valueOf(plan.getAmount()))
                        .currencyId("ARS")
                        .build();

        PreapprovalCreateRequest request = PreapprovalCreateRequest.builder()
                .reason(plan.getDisplayName())
                .autoRecurring(autoRecurring)
                .payerEmail(payerEmail)
                .backUrl(plansProperties.getBackUrlSuccess())
                .externalReference(externalReference)
                .status("pending")
                .build();

        PreapprovalClient client = new PreapprovalClient();
        Preapproval preapproval = client.create(request);

        log.info("Created MP preapproval id={} for externalReference={}",
                preapproval.getId(), externalReference);

        return preapproval.getInitPoint();
    }

    /**
     * Fetches a preapproval by its MP id.
     */
    public Preapproval getPreapproval(String preapprovalId) throws MPException, MPApiException {
        initSdk();
        PreapprovalClient client = new PreapprovalClient();
        return client.get(preapprovalId);
    }

    /**
     * Creates a one-time-payment checkout preference (Checkout Pro) for a single item.
     *
     * @param itemTitle         the item description shown at checkout
     * @param amount            the amount to charge, in ARS
     * @param payerEmail        the buyer's email, may be null/blank
     * @param externalReference the local purchase id (for webhook correlation)
     * @return the init_point URL to redirect the user to MP checkout
     */
    public String createPreference(String itemTitle, BigDecimal amount, String payerEmail,
            String externalReference) throws MPException, MPApiException {
        initSdk();

        PreferenceItemRequest item = PreferenceItemRequest.builder()
                .title(itemTitle)
                .quantity(1)
                .unitPrice(amount)
                .currencyId("ARS")
                .build();

        PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder()
                .success(challengeProperties.getBackUrl())
                .failure(challengeProperties.getBackUrl())
                .pending(challengeProperties.getBackUrl())
                .build();

        PreferenceRequest.PreferenceRequestBuilder requestBuilder = PreferenceRequest.builder()
                .items(List.of(item))
                .backUrls(backUrls)
                // Auto-redirect the buyer back to our success page once the payment is approved,
                // instead of leaving them on Mercado Pago's confirmation screen.
                .autoReturn("approved")
                .externalReference(externalReference);

        if (StringUtils.hasText(payerEmail)) {
            requestBuilder.payer(PreferencePayerRequest.builder().email(payerEmail).build());
        }

        // Attach a per-preference notification_url so MP posts the payment webhook for THIS
        // payment explicitly (the app-level Webhooks config is unreliable in sandbox/test mode).
        if (StringUtils.hasText(plansProperties.getNotificationUrl())) {
            requestBuilder.notificationUrl(plansProperties.getNotificationUrl());
        }

        PreferenceClient client = new PreferenceClient();
        Preference preference = client.create(requestBuilder.build());

        log.info("Created MP preference id={} for externalReference={}",
                preference.getId(), externalReference);

        return preference.getInitPoint();
    }

    /**
     * Fetches a payment by its MP id.
     */
    public Payment getPayment(String paymentId) throws MPException, MPApiException {
        initSdk();
        PaymentClient client = new PaymentClient();
        return client.get(Long.parseLong(paymentId));
    }

    /**
     * Searches Mercado Pago for payments carrying the given {@code external_reference}
     * (the local purchase id) — used by
     * {@link com.tiam.challenge.service.ChallengePurchaseReconciliationService} to
     * recover purchases whose webhook notification was never delivered, since a PENDING
     * purchase has no {@code mp_payment_id} to look up directly.
     *
     * <p>A checkout retry (e.g. a first card declined, then a second attempt) can
     * produce more than one {@link Payment} under the same external_reference. When
     * that happens we prefer an approved one — the purchase was ultimately paid, even
     * if an earlier attempt failed — and otherwise fall back to the most recently
     * created payment.
     */
    public Optional<Payment> findLatestPaymentByExternalReference(String externalReference)
            throws MPException, MPApiException {
        initSdk();

        // limit/offset MUST be set explicitly: MPSearchRequest.getParameters() inserts
        // "limit"/"offset" into the query-param map even when unset (as a null value),
        // and the SDK's UrlFormatter.format() calls toString() on every param value with
        // no null guard — an unset limit/offset throws a NullPointerException at search
        // time. 10 is comfortably more than the checkout retries a single purchase could
        // realistically produce.
        MPSearchRequest searchRequest = MPSearchRequest.builder()
                .filters(Map.of("external_reference", externalReference))
                .limit(10)
                .offset(0)
                .build();

        PaymentClient client = new PaymentClient();
        MPResultsResourcesPage<Payment> page = client.search(searchRequest);
        List<Payment> results = page.getResults();

        if (results == null || results.isEmpty()) {
            return Optional.empty();
        }

        return results.stream()
                .filter(p -> "approved".equals(p.getStatus()))
                .findFirst()
                .or(() -> results.stream().max(Comparator.comparing(Payment::getDateCreated)));
    }

    /**
     * Returns true if MP_ACCESS_TOKEN is configured.
     */
    public boolean isConfigured() {
        return StringUtils.hasText(mpProperties.getAccessToken());
    }
}
