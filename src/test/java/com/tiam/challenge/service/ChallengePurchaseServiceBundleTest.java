package com.tiam.challenge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.tiam.subscription.service.MercadoPagoService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The parts of {@link ChallengePurchaseService} that the printable-pack sale relies on (the games
 * add-on is a Desafío month granted with the pack). Kept apart from ChallengePurchaseServiceTest so the
 * Desafío's own tests stay exactly as they were.
 */
@ExtendWith(MockitoExtension.class)
class ChallengePurchaseServiceBundleTest {

    static final String PHONE = "542211234567";

    @Mock ChallengePurchaseRepository repository;
    @Mock MercadoPagoService mercadoPagoService;
    @Mock WhatsAppProperties whatsAppProperties;
    @Mock ApplicationEventPublisher eventPublisher;

    ChallengePurchaseService service;

    @BeforeEach
    void setUp() {
        service = new ChallengePurchaseService(repository, mercadoPagoService, whatsAppProperties, eventPublisher);
    }

    // --- hasMonthAvailable ----------------------------------------------------------------------

    @Test
    void hasMonthAvailable_aNewPhoneCanBuy() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(List.of());

        assertThat(service.hasMonthAvailable("221 123-4567")).isTrue();
    }

    @Test
    void hasMonthAvailable_someMonthsPaid_stillAvailable() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(paidMonths(1, 2, 3));

        assertThat(service.hasMonthAvailable(PHONE)).isTrue();
    }

    @Test
    void hasMonthAvailable_everyMonthOnSalePaid_notAvailable() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(paidMonths(1, 2, 3, 4));

        assertThat(service.hasMonthAvailable(PHONE)).isFalse();
    }

    @Test
    void hasMonthAvailable_aPendingMonthDoesNotCount() {
        List<ChallengePurchase> purchases = new ArrayList<>(paidMonths(1, 2, 3));
        purchases.add(purchase(4, ChallengePurchaseStatus.PENDING));
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(purchases);

        assertThat(service.hasMonthAvailable(PHONE)).isTrue();
    }

    static List<ChallengePurchase> paidMonths(int... months) {
        List<ChallengePurchase> result = new ArrayList<>();
        for (int month : months) {
            result.add(purchase(month, ChallengePurchaseStatus.PAID));
        }
        return result;
    }

    static ChallengePurchase purchase(int month, ChallengePurchaseStatus status) {
        ChallengePurchase purchase = new ChallengePurchase();
        purchase.setBuyerName("Ana");
        purchase.setPhone(PHONE);
        purchase.setStatus(status);
        purchase.setChallengeMonth(month);
        return purchase;
    }
}
