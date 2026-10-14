package com.tiam.pack.service;

import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackOwnerAlert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * Tells the owner about things only a human can resolve (an amount that does not match, a double payment, a
 * bundled month that could not be granted).
 *
 * <p>{@link #notify} does no I/O: it publishes a {@link PackOwnerAlert}. The email itself is sent by
 * {@link #deliver} on another thread and only after the surrounding transaction commits (or immediately when
 * there is none). That matters because these alerts are raised inside the payment transaction, which holds a
 * row lock and one of very few pooled connections; a slow email provider must not be able to hold either, and
 * an alert about a state that was rolled back must not go out.
 *
 * <p>Best effort: every failure is logged and swallowed, never propagated into the payment flow. With no
 * address configured only the subject is logged (the body can carry the buyer's email).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PackOwnerNotifier {

    private static final String SUBJECT_PREFIX = "[TIAM Packs] ";

    private final ApplicationEventPublisher events;
    private final EmailSender emailSender;
    private final PackProperties properties;

    public void notify(String subject, String body) {
        events.publishEvent(new PackOwnerAlert(subject, body));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void deliver(PackOwnerAlert alert) {
        String to = properties.effectiveOwnerNotifyTo();
        if (!StringUtils.hasText(to)) {
            log.warn("Owner notice not emailed (no PACK_OWNER_NOTIFY_TO configured): {}", alert.subject());
            return;
        }
        try {
            String html = "<p style=\"font-family:Arial,Helvetica,sans-serif;font-size:16px;\">"
                    + HtmlUtils.htmlEscape(alert.body()).replace("\n", "<br>") + "</p>";
            if (!emailSender.send(new EmailMessage(to, SUBJECT_PREFIX + alert.subject(), html, alert.body(), null))) {
                log.warn("Owner notice could not be emailed: {}", alert.subject());
            }
        } catch (RuntimeException e) {
            log.error("Owner notice failed: {} ({})", alert.subject(), e.getMessage(), e);
        }
    }
}
