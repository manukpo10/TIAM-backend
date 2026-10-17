package com.tiam.lead.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tiam.lead.domain.Lead;
import com.tiam.lead.dto.CreateLeadRequest;
import com.tiam.lead.repository.LeadRepository;
import com.tiam.pack.service.PackRateLimiter;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LeadServiceTest {

    static final String IP = "203.0.113.7";
    static final long HUMAN_PACE_MS = 9_000L;

    @Mock LeadRepository leadRepository;
    @Mock PackRateLimiter rateLimiter;

    LeadService service;

    @BeforeEach
    void setUp() {
        service = new LeadService(leadRepository, rateLimiter);
    }

    static CreateLeadRequest request(String email, String website, Long elapsedMs) {
        return new CreateLeadRequest(email, " Ana ", "recursos", true, website, elapsedMs);
    }

    void ipHasRoom() {
        when(rateLimiter.tryAcquireByIp(eq(LeadService.RATE_LIMIT_SCOPE), eq(IP), anyInt(), any(Duration.class)))
                .thenReturn(true);
    }

    // --- genuine leads -------------------------------------------------------------------------

    @Test
    void genuineLead_isSavedNormalised_andCountedAgainstTheIp() {
        ipHasRoom();

        LeadCaptureResult result = service.capture(request(" Ana@Example.com ", null, HUMAN_PACE_MS), IP);

        assertThat(result).isEqualTo(LeadCaptureResult.SAVED);
        ArgumentCaptor<Lead> captor = ArgumentCaptor.forClass(Lead.class);
        verify(leadRepository).save(captor.capture());
        Lead lead = captor.getValue();
        assertThat(lead.getEmail()).isEqualTo("ana@example.com");
        assertThat(lead.getName()).isEqualTo("Ana");
        assertThat(lead.getSource()).isEqualTo("recursos");
        assertThat(lead.isConsent()).isTrue();
        verify(rateLimiter).tryAcquireByIp(LeadService.RATE_LIMIT_SCOPE, IP, LeadService.MAX_LEADS_PER_IP_PER_HOUR,
                Duration.ofHours(1));
    }

    @Test
    void blankHoneypot_isTreatedAsEmpty() {
        ipHasRoom();

        assertThat(service.capture(request("ana@example.com", "   ", HUMAN_PACE_MS), IP))
                .isEqualTo(LeadCaptureResult.SAVED);
    }

    @Test
    void submittingExactlyAtTheMinimumFillTime_isAccepted() {
        ipHasRoom();

        assertThat(service.capture(request("ana@example.com", null, LeadService.MIN_FILL_MS), IP))
                .isEqualTo(LeadCaptureResult.SAVED);
    }

    /** Spanish compound names carry many dots too; other providers' dots are part of the address. */
    @ParameterizedTest
    @ValueSource(strings = {"maria.jose.perez@gmail.com", "a.b.c.d.e@empresa.com.ar", "ana.lia@googlemail.com",
            "maria.de.los.angeles.perez@gmail.com", "juan.de.la.cruz.lopez@gmail.com", "a.b.c.d@gmail.com",
            "ana.de.la.paz.ruiz@gmail.com"})
    void everydayAddressesWithDots_areKept(String email) {
        ipHasRoom();

        assertThat(service.capture(request(email, null, HUMAN_PACE_MS), IP)).isEqualTo(LeadCaptureResult.SAVED);
    }

    @Test
    void unknownClientIp_isNotABlocker() {
        when(rateLimiter.tryAcquireByIp(eq(LeadService.RATE_LIMIT_SCOPE), eq(null), anyInt(), any(Duration.class)))
                .thenReturn(true);

        assertThat(service.capture(request("ana@example.com", null, HUMAN_PACE_MS), null))
                .isEqualTo(LeadCaptureResult.SAVED);
    }

    // --- bots: dropped without saving, and without spending the IP's quota -------------------

    @Test
    void filledHoneypot_isDropped() {
        assertThat(service.capture(request("ana@example.com", "https://spam.example", HUMAN_PACE_MS), IP))
                .isEqualTo(LeadCaptureResult.HONEYPOT);
        verifyNoInteractions(leadRepository, rateLimiter);
    }

    @Test
    void honeypotOfAnyLength_isJustAHoneypot() {
        assertThat(service.capture(request("ana@example.com", "x".repeat(5_000), HUMAN_PACE_MS), IP))
                .isEqualTo(LeadCaptureResult.HONEYPOT);
        verifyNoInteractions(leadRepository, rateLimiter);
    }

    /** A workshop's shared Wi-Fi or a phone carrier's shared address sends several genuine sign-ups in an hour. */
    @Test
    void ipQuota_leavesRoomForASharedConnection() {
        assertThat(LeadService.MAX_LEADS_PER_IP_PER_HOUR).isGreaterThanOrEqualTo(20);
    }

    @Test
    void missingFillTime_isDropped_becauseOnlyAScriptPostsWithoutTheForm() {
        assertThat(service.capture(request("ana@example.com", null, null), IP)).isEqualTo(LeadCaptureResult.TOO_FAST);
        verifyNoInteractions(leadRepository, rateLimiter);
    }

    @ParameterizedTest
    @ValueSource(longs = {-5_000L, 0L, 1_200L, LeadService.MIN_FILL_MS - 1})
    void formSentFasterThanAPersonCould_isDropped(long elapsedMs) {
        assertThat(service.capture(request("ana@example.com", null, elapsedMs), IP))
                .isEqualTo(LeadCaptureResult.TOO_FAST);
        verifyNoInteractions(leadRepository, rateLimiter);
    }

    /** The shape seen in production: dots between almost every letter of a real Gmail inbox (Gmail ignores dots). */
    @ParameterizedTest
    @ValueSource(strings = {"q.x.r.ab.cd1.2@gmail.com", "a.b.c.d.e.f.g@gmail.com", "ab.c.de.f.g1@GMAIL.COM",
            "z.y.x.w.vu@googlemail.com", "kim.o.lardo.er5.2@gmail.com", "m.utfar.le.y1.997@gmail.com"})
    void gmailWithDotsBetweenTheLetters_isDropped(String email) {
        assertThat(service.capture(request(email, null, HUMAN_PACE_MS), IP))
                .isEqualTo(LeadCaptureResult.DOTTED_GMAIL);
        verifyNoInteractions(leadRepository, rateLimiter);
    }

    @Test
    void overTheHourlyLimitOfTheIp_isDropped() {
        when(rateLimiter.tryAcquireByIp(eq(LeadService.RATE_LIMIT_SCOPE), eq(IP), anyInt(), any(Duration.class)))
                .thenReturn(false);

        assertThat(service.capture(request("ana@example.com", null, HUMAN_PACE_MS), IP))
                .isEqualTo(LeadCaptureResult.RATE_LIMITED);
        verify(leadRepository, never()).save(any());
    }
}
