package com.tiam.pack.service;

import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import com.tiam.pack.config.PackProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * Tells the owner about things only a human can resolve (an amount that does not match, a double payment,
 * a bundled month that could not be granted). It is best effort and must never break the payment flow it is
 * called from: every failure is logged and swallowed. With no address configured the message is only logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PackOwnerNotifier {

    private static final String SUBJECT_PREFIX = "[TIAM Packs] ";

    private final EmailSender emailSender;
    private final PackProperties properties;

    public void notify(String subject, String body) {
        String to = properties.effectiveOwnerNotifyTo();
        if (!StringUtils.hasText(to)) {
            log.warn("Owner notice (no PACK_OWNER_NOTIFY_TO configured, so it is only logged): {} - {}", subject, body);
            return;
        }
        try {
            String html = "<p style=\"font-family:Arial,Helvetica,sans-serif;font-size:16px;\">"
                    + HtmlUtils.htmlEscape(body).replace("\n", "<br>") + "</p>";
            if (!emailSender.send(new EmailMessage(to, SUBJECT_PREFIX + subject, html, body, null))) {
                log.warn("Owner notice could not be emailed: {} - {}", subject, body);
            }
        } catch (RuntimeException e) {
            log.error("Owner notice failed: {} - {} ({})", subject, body, e.getMessage(), e);
        }
    }
}
