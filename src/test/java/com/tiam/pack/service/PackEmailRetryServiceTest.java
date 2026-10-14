package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PackEmailRetryServiceTest {

    static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");

    @Mock PackOrderRepository orders;
    @Mock PackOrderEmailService emailService;

    PackProperties properties;
    PackEmailRetryService service;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        service = new PackEmailRetryService(orders, emailService, properties, new MutableClock(NOW));
    }

    @Test
    void disabled_doesNothing() {
        properties.getEmailRetry().setEnabled(false);

        service.retryUndeliveredAccessEmails();

        verifyNoInteractions(orders, emailService);
    }

    @Test
    void withoutARealEmailProvider_doesNothing_ordersStayEligibleForTheWindow() {
        when(emailService.isDeliveryEnabled()).thenReturn(false);

        service.retryUndeliveredAccessEmails();

        verifyNoInteractions(orders);
    }

    @Test
    void withAnUnusableDownloadBase_doesNothing() {
        when(emailService.isDeliveryEnabled()).thenReturn(true);
        when(emailService.isBlockedByUnusableLinkBase()).thenReturn(true);

        service.retryUndeliveredAccessEmails();

        verifyNoInteractions(orders);
        verify(emailService, never()).sendAccessEmail(any());
    }

    @Test
    void retriesPaidOrdersFromTheLastSeventyTwoHours_leavingTheLastFiveMinutesToTheImmediateSend() {
        when(emailService.isDeliveryEnabled()).thenReturn(true);
        when(emailService.isBlockedByUnusableLinkBase()).thenReturn(false);
        when(orders.findIdsAwaitingAccessEmail(any(), any(), any())).thenReturn(List.of());

        service.retryUndeliveredAccessEmails();

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> until = ArgumentCaptor.forClass(Instant.class);
        verify(orders).findIdsAwaitingAccessEmail(eq(PackOrderStatus.PAID), since.capture(), until.capture());
        assertThat(since.getValue()).isEqualTo(NOW.minus(Duration.ofHours(72)));
        assertThat(until.getValue()).isEqualTo(NOW.minus(Duration.ofMinutes(5)));
    }

    @Test
    void sendsEachUndeliveredOrder_andOneFailureDoesNotStopTheOthers() {
        when(emailService.isDeliveryEnabled()).thenReturn(true);
        when(emailService.isBlockedByUnusableLinkBase()).thenReturn(false);
        when(orders.findIdsAwaitingAccessEmail(any(), any(), any())).thenReturn(List.of(1L, 2L, 3L));
        doThrow(new IllegalStateException("provider blip")).when(emailService).sendAccessEmail(2L);

        service.retryUndeliveredAccessEmails();

        verify(emailService).sendAccessEmail(1L);
        verify(emailService).sendAccessEmail(2L);
        verify(emailService).sendAccessEmail(3L);
    }

    @Test
    void aFailureOfTheWholeRun_isSwallowed_soTheSchedulerKeepsRunning() {
        when(emailService.isDeliveryEnabled()).thenThrow(new IllegalStateException("boom"));

        service.retryUndeliveredAccessEmails(); // must not propagate
    }
}
