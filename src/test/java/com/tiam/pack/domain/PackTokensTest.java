package com.tiam.pack.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PackTokensTest {

    @Test
    void newToken_carries256BitsEncodedAsUrlSafeBase64() {
        String token = PackTokens.newToken();

        // 32 bytes -> 43 chars of unpadded base64url
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void newToken_neverRepeats() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            assertThat(seen.add(PackTokens.newToken())).isTrue();
        }
    }

    @Test
    void looksValid_acceptsOnlyTheExactShapeWeIssue() {
        assertThat(PackTokens.looksValid(PackTokens.newToken())).isTrue();
        assertThat(PackTokens.looksValid(null)).isFalse();
        assertThat(PackTokens.looksValid("")).isFalse();
        assertThat(PackTokens.looksValid("short")).isFalse();
        assertThat(PackTokens.looksValid("a".repeat(44))).isFalse();
        assertThat(PackTokens.looksValid("a".repeat(42) + "!")).isFalse();
    }

    @Test
    void mask_neverExposesMoreThanAShortPrefix() {
        String token = PackTokens.newToken();

        String masked = PackTokens.mask(token);

        assertThat(masked).startsWith(token.substring(0, 4)).doesNotContain(token.substring(4));
        assertThat(PackTokens.mask(null)).isEqualTo("<none>");
    }
}
