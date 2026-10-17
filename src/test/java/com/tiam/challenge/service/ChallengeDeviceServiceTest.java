package com.tiam.challenge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tiam.challenge.repository.ChallengeDeviceLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(MockitoExtension.class)
class ChallengeDeviceServiceTest {

    static final String TOKEN = "3f2a9c1e-5b7d-4e2f-9a1b-0c3d5e7f9a1b";
    static final String DEVICE = "8d6e4c2a-1b3f-4a5c-9e7d-2f4b6d8a0c1e";
    static final String ANDROID_CHROME =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36";

    @Mock ChallengeDeviceLog deviceLog;

    ChallengeDeviceService service;

    @BeforeEach
    void setUp() {
        service = new ChallengeDeviceService(deviceLog);
    }

    @Test
    void aVisitIsRecordedWithACoarseDescriptionOfTheDevice() {
        service.recordVisit(TOKEN, DEVICE, ANDROID_CHROME);

        verify(deviceLog).recordVisit(TOKEN, DEVICE, "Android · Chrome", ChallengeDeviceService.MAX_DEVICES_PER_PURCHASE);
    }

    @Test
    void aShortHexIdFromTheFallbackGenerator_isAccepted() {
        service.recordVisit(TOKEN, "0123456789abcdef0123456789abcdef", ANDROID_CHROME);

        verify(deviceLog).recordVisit(anyString(), anyString(), anyString(), anyInt());
    }

    /** Old players send no id, storage can be blocked, and anything else is junk: nothing is recorded. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "abc", "not a uuid at all, with spaces", "8d6e4c2a'; DROP TABLE x; --",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void aMissingOrMalformedDeviceId_recordsNothing(String deviceId) {
        service.recordVisit(TOKEN, deviceId, ANDROID_CHROME);

        verifyNoInteractions(deviceLog);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void aMissingToken_recordsNothing(String token) {
        service.recordVisit(token, DEVICE, ANDROID_CHROME);

        verifyNoInteractions(deviceLog);
    }

    /** The write runs on the async executor, never on the request that opens the Desafío. */
    @Test
    void recordVisit_runsOffTheRequestThread() throws NoSuchMethodException {
        assertThat(ChallengeDeviceService.class.getMethod("recordVisit", String.class, String.class, String.class)
                .isAnnotationPresent(org.springframework.scheduling.annotation.Async.class)).isTrue();
    }

    /** Measuring must never break the Desafío: a database hiccup is logged and swallowed. */
    @Test
    void aFailingDatabase_neverReachesTheBuyer() {
        doThrow(new DataAccessResourceFailureException("pool exhausted"))
                .when(deviceLog).recordVisit(anyString(), anyString(), anyString(), anyInt());

        assertThatCode(() -> service.recordVisit(TOKEN, DEVICE, ANDROID_CHROME)).doesNotThrowAnyException();
    }
}
