package com.tiam.challenge.service;

import com.tiam.challenge.repository.ChallengeDeviceLog;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Measures how many devices open each Desafío link, to learn whether buyers share it. Measurement only: nothing
 * here can deny or slow down access. It runs after the access was granted, on the async executor, and every
 * failure is logged and swallowed.
 *
 * <p>The device id is a random id the player keeps in the browser; anything that does not look like one (old
 * players send none, storage can be blocked) is ignored rather than counted, so the numbers are not inflated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengeDeviceService {

    /** Bounds the table per purchase; far above any family's devices. It is not a limit on access. */
    static final int MAX_DEVICES_PER_PURCHASE = 50;
    /** crypto.randomUUID() (36 chars) or the 32-hex fallback the player uses when randomUUID is missing. */
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9-]{16,64}");

    private final ChallengeDeviceLog deviceLog;

    @Async
    public void recordVisit(String accessToken, String deviceId, String userAgent) {
        if (!StringUtils.hasText(accessToken) || deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
            return;
        }
        try {
            deviceLog.recordVisit(accessToken, deviceId, DeviceLabels.describe(userAgent), MAX_DEVICES_PER_PURCHASE);
        } catch (RuntimeException e) {
            // The exception text carries the SQL with placeholders, never the token.
            log.warn("Could not record a Desafío device visit: {}", e.toString());
        }
    }
}
