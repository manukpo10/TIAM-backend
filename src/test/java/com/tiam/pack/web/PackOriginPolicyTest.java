package com.tiam.pack.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PackOriginPolicyTest {

    PackOriginPolicy policy = new PackOriginPolicy(" https://tiamdigital.com , https://www.tiamdigital.com/ ,");

    @Test
    void configuredOrigins_areAllowed_ignoringCaseSpacesAndTrailingSlashes() {
        assertThat(policy.isAllowed("https://tiamdigital.com")).isTrue();
        assertThat(policy.isAllowed("https://www.tiamdigital.com")).isTrue();
        assertThat(policy.isAllowed("HTTPS://TIAMDIGITAL.COM/")).isTrue();
    }

    @Test
    void anythingElse_isRefused() {
        assertThat(policy.isAllowed("https://evil.example.com")).isFalse();
        assertThat(policy.isAllowed("http://tiamdigital.com")).isFalse();
        assertThat(policy.isAllowed("https://tiamdigital.com.evil.com")).isFalse();
        assertThat(policy.isAllowed("null")).isFalse();
    }

    @Test
    void noOriginHeader_isAccepted() {
        assertThat(policy.isAllowed(null)).isTrue();
        assertThat(policy.isAllowed("  ")).isTrue();
    }
}
