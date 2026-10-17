package com.tiam.challenge.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes the {@code challenge_devices} measurement table (V15). Plain SQL instead of an entity on purpose: the write
 * is a single atomic upsert (two tabs opening at once must not collide), and nothing in the app reads these rows —
 * they are for a person running a query.
 */
@Repository
@RequiredArgsConstructor
public class ChallengeDeviceLog {

    /**
     * Counts one opening of a PAID purchase's link from a device: inserts the device the first time, otherwise adds a
     * visit and refreshes last_seen_at. An unknown or unpaid token matches no purchase and writes nothing. A purchase
     * that already lists {@code maxDevices} devices takes no NEW ones but keeps counting the ones it has (it only
     * bounds the table; nothing is blocked).
     */
    private static final String RECORD_VISIT = """
            INSERT INTO challenge_devices (purchase_id, device_id, device_label)
            SELECT p.id, :deviceId, :deviceLabel
            FROM challenge_purchases p
            WHERE p.access_token = :accessToken
              AND p.status = 'PAID'
              AND p.activo = TRUE
              AND (EXISTS (SELECT 1 FROM challenge_devices d WHERE d.purchase_id = p.id AND d.device_id = :deviceId)
                   OR (SELECT COUNT(*) FROM challenge_devices d WHERE d.purchase_id = p.id) < :maxDevices)
            ON CONFLICT (purchase_id, device_id) DO UPDATE
                SET visits = challenge_devices.visits + 1,
                    last_seen_at = NOW(),
                    device_label = EXCLUDED.device_label
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** @return rows written (1 = inserted or counted, 0 = no paid purchase with that token, or the purchase is full) */
    public int recordVisit(String accessToken, String deviceId, String deviceLabel, int maxDevices) {
        return jdbc.update(RECORD_VISIT, new MapSqlParameterSource()
                .addValue("accessToken", accessToken)
                .addValue("deviceId", deviceId)
                .addValue("deviceLabel", deviceLabel)
                .addValue("maxDevices", maxDevices));
    }
}
