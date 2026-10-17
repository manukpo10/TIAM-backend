package com.tiam.lead.service;

import com.tiam.common.email.EmailAddresses;
import com.tiam.lead.domain.Lead;
import com.tiam.lead.dto.CreateLeadRequest;
import com.tiam.lead.dto.LeadResponse;
import com.tiam.lead.repository.LeadRepository;
import com.tiam.pack.service.PackRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Captures leads from the public forms (/recursos, the platform waiting list) and keeps bots out of the table.
 *
 * <p>Bots filled the form with random names and Gmail addresses dotted between almost every letter. Those
 * inboxes are real (Gmail ignores dots): it is list-bombing, someone's address signed up everywhere. Storing them
 * pollutes the list, and emailing them one day would get tiamdigital.com reported as spam, the same domain that
 * delivers the Desafío links to paying buyers. So a submission is dropped, silently, when the hidden honeypot
 * comes filled in, when the form was sent faster than a person can fill it (or without the fill time, i.e. posted
 * by a script), when the address has that dotted-Gmail shape, or when the same IP sent too many in the last hour.
 * The caller answers a dropped submission exactly like a saved one, so a bot cannot learn which check stopped it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadService {

    /** A person needs a few seconds to read, type an email and tick the consent box. */
    static final long MIN_FILL_MS = 3_000L;
    /**
     * Generous on purpose: a workshop's shared Wi-Fi or a phone carrier's shared address can send several genuine
     * sign-ups in an hour, and a dropped person still sees "¡Listo!". The dotted-Gmail rule is the real filter.
     */
    static final int MAX_LEADS_PER_IP_PER_HOUR = 20;
    static final String RATE_LIMIT_SCOPE = "lead-ip";
    /**
     * The bot's local parts are chopped into tiny pieces (q.i.p.iyotunu1.0.9): many dots, at least one piece of a
     * single character and short pieces on average. Real addresses with many dots are compound names
     * (maria.de.los.angeles.perez, juan.de.la.cruz.lopez) whose pieces are words, so all three must hold.
     */
    static final int DOTTED_GMAIL_MIN_DOTS = 4;
    static final double DOTTED_GMAIL_MAX_AVG_PIECE = 3.0;
    private static final Set<String> GMAIL_DOMAINS = Set.of("gmail.com", "googlemail.com");
    private static final int LOGGED_SOURCE_MAX = 40;

    private final LeadRepository leadRepository;
    private final PackRateLimiter rateLimiter;

    /**
     * Stores the lead unless it looks like a bot. Email is normalised to lowercase.
     *
     * @param clientIp the visitor's address (see {@code ClientIpResolver}); null when unknown, which is not limited
     */
    @Transactional
    public LeadCaptureResult capture(CreateLeadRequest request, String clientIp) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String source = request.source() != null && !request.source().isBlank() ? request.source().trim() : "recursos";

        LeadCaptureResult verdict = screen(request, email, clientIp);
        if (verdict != LeadCaptureResult.SAVED) {
            log.info("Lead dropped ({}): source={} elapsedMs={} email={}", verdict, printable(source),
                    request.elapsedMs(), EmailAddresses.mask(email));
            return verdict;
        }

        Lead lead = new Lead();
        lead.setEmail(email);
        lead.setName(request.name() != null ? request.name().trim() : null);
        lead.setSource(source);
        lead.setConsent(request.consent());
        leadRepository.save(lead);
        return LeadCaptureResult.SAVED;
    }

    /** The cheap checks first; the IP quota last, so only a submission that looks human spends it. */
    private LeadCaptureResult screen(CreateLeadRequest request, String email, String clientIp) {
        if (request.website() != null && !request.website().isBlank()) {
            return LeadCaptureResult.HONEYPOT;
        }
        if (request.elapsedMs() == null || request.elapsedMs() < MIN_FILL_MS) {
            return LeadCaptureResult.TOO_FAST;
        }
        if (isDottedGmail(email)) {
            return LeadCaptureResult.DOTTED_GMAIL;
        }
        if (!rateLimiter.tryAcquireByIp(RATE_LIMIT_SCOPE, clientIp, MAX_LEADS_PER_IP_PER_HOUR, Duration.ofHours(1))) {
            return LeadCaptureResult.RATE_LIMITED;
        }
        return LeadCaptureResult.SAVED;
    }

    private static boolean isDottedGmail(String email) {
        int at = email.lastIndexOf('@');
        if (at <= 0 || !GMAIL_DOMAINS.contains(email.substring(at + 1))) {
            return false;
        }
        String[] pieces = email.substring(0, at).split("\\.", -1);
        if (pieces.length - 1 < DOTTED_GMAIL_MIN_DOTS) {
            return false;
        }
        boolean hasSingleCharPiece = Arrays.stream(pieces).anyMatch(p -> p.length() == 1);
        double averagePiece = Arrays.stream(pieces).mapToInt(String::length).average().orElse(0);
        return hasSingleCharPiece && averagePiece <= DOTTED_GMAIL_MAX_AVG_PIECE;
    }

    /** The source is user input: no control characters (no forged log lines) and a bounded length. */
    private static String printable(String source) {
        String clean = source.replaceAll("\\p{Cntrl}", "?");
        return clean.length() > LOGGED_SOURCE_MAX ? clean.substring(0, LOGGED_SOURCE_MAX) + "…" : clean;
    }

    /** Lists all captured leads, newest first (admin only). */
    @Transactional(readOnly = true)
    public List<LeadResponse> findAll() {
        return leadRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
            .map(l -> new LeadResponse(
                l.getId(), l.getEmail(), l.getName(), l.getSource(), l.isConsent(), l.getCreatedAt()))
            .toList();
    }
}
