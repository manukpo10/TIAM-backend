package com.tiam.pack.service;

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
    void request_threePerEmailPerDay_thenSilentlyDropped() {
        for (int i = 0; i < 6; i++) {
            service.request("ana@example.com", "1.2.3." + i);
        }

        verify(resender, org.mockito.Mockito.times(3)).resendAsync("ana@example.com");
    }

    @Test
    void request_theEmailQuotaRefillsAfterADay() {
        for (int i = 0; i < 3; i++) {
            service.request("ana@example.com", "1.2.3." + i);
        }
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        service.request("ana@example.com", "9.9.9.9");

        verify(resender, org.mockito.Mockito.times(4)).resendAsync("ana@example.com");
    }

    @Test
    void request_tenPerIpPerHour_thenSilentlyDropped() {
        for (int i = 0; i < 15; i++) {
            service.request("buyer" + i + "@example.com", "1.2.3.4");
        }

        verify(resender, org.mockito.Mockito.times(10)).resendAsync(any());
    }

    @Test
    void request_limitsAreConfigurable() {
        properties.getRateLimits().setResendPerEmail(1);

        service.request("ana@example.com", "1.2.3.4");
        service.request("ana@example.com", "5.6.7.8");

        verify(resender, org.mockito.Mockito.times(1)).resendAsync("ana@example.com");
    }

    @Test
    void request_notAnEmail_isIgnoredWithoutTouchingAnything() {
        service.request("not an email", "1.2.3.4");
        service.request("", "1.2.3.4");
        service.request(null, "1.2.3.4");

        verifyNoInteractions(resender);
    }
}
