package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tiam.pack.config.PackProperties;
import com.tiam.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PackAccessResendServiceTest {

    @Mock PackAccessResender resender;

    PackProperties properties;
    MutableClock clock;
    PackAccessResendService service;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
        service = new PackAccessResendService(resender, new PackRateLimiter(clock), properties);
    }

    @Test
    void request_handsTheWorkToTheAsyncResender_withTheEmailNormalised() {
        service.request("  Ana@Example.COM ", "1.2.3.4");

        verify(resender).resendAsync("ana@example.com");
    }

    @Test
    void request_neverThrowsAndNeverSaysAnythingAboutWhetherTheEmailBought() {
        service.request("nobody@example.com", "1.2.3.4"); // returns normally whether or not an order exists
    }

    @Test
    void request_theEmailQuotaIsNotCountedHere_soStrangersEmailsLeaveNoTraceInTheLimiter() {
        PackRateLimiter limiter = new PackRateLimiter(clock);
        PackAccessResendService withLimiter = new PackAccessResendService(resender, limiter, properties);

        for (int i = 0; i < 100; i++) {
            withLimiter.request("stranger" + i + "@example.com", "1.2.3.4");
        }

        assertThat(limiter.trackedKeys()).as("one entry for the IP, none per email").isEqualTo(1);
    }

    @Test
    void request_perIpPerHour_thenSilentlyDropped() {
        properties.getRateLimits().setResendPerIp(10);
        for (int i = 0; i < 15; i++) {
            service.request("buyer" + i + "@example.com", "1.2.3.4");
        }

        verify(resender, org.mockito.Mockito.times(10)).resendAsync(any());
    }

    @Test
    void request_theIpQuotaRefillsAfterAnHour() {
        properties.getRateLimits().setResendPerIp(1);
        service.request("a@example.com", "1.2.3.4");
        service.request("b@example.com", "1.2.3.4");
        clock.advance(Duration.ofMinutes(61));

        service.request("c@example.com", "1.2.3.4");

        verify(resender, org.mockito.Mockito.times(2)).resendAsync(any());
    }

    @Test
    void request_anUnknownIp_isNotLimitedByIp() {
        properties.getRateLimits().setResendPerIp(1);

        for (int i = 0; i < 5; i++) {
            service.request("buyer" + i + "@example.com", null);
        }

        verify(resender, org.mockito.Mockito.times(5)).resendAsync(any());
    }

    @Test
    void request_notAnEmail_isIgnoredWithoutTouchingAnything() {
        service.request("not an email", "1.2.3.4");
        service.request("", "1.2.3.4");
        service.request(null, "1.2.3.4");

        verifyNoInteractions(resender);
    }
}
