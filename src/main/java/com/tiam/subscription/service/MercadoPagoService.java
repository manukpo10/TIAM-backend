package com.tiam.subscription.service;

import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.preapproval.PreApprovalAutoRecurringCreateRequest;
import com.mercadopago.client.preapproval.PreapprovalClient;
import com.mercadopago.client.preapproval.PreapprovalCreateRequest;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.preapproval.Preapproval;
import com.tiam.subscription.config.MercadoPagoProperties;
import com.tiam.subscription.config.PlansProperties;
import com.tiam.subscription.domain.ProfessionalPlan;
import java.math.BigDecimal;
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
     * Returns true if MP_ACCESS_TOKEN is configured.
     */
    public boolean isConfigured() {
        return StringUtils.hasText(mpProperties.getAccessToken());
    }
}
