package com.tiam.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The client IP feeds the rate limits. The rules that matter: only entries a trusted proxy appended (the right
 * end of X-Forwarded-For) are believed, a spoofed left end is ignored, and when the IP cannot be determined the
 * answer is "unknown" (null) so the limits fail open instead of bucketing every buyer behind one shared address.
 */
class ClientIpResolverTest {

    ClientIpProperties properties;
    MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        properties = new ClientIpProperties();
        request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
    }

    private String resolve() {
        return new ClientIpResolver(properties).resolve(request);
    }

    @Test
    void noProxyHeaders_usesThePeerAddress() {
        assertThat(resolve()).isEqualTo("203.0.113.9");
    }

    @Test
    void zeroHops_ignoresAnyForwardedForHeader_becauseTomcatAlreadyAppliedTheTrustedOnes() {
        request.addHeader("X-Forwarded-For", "6.6.6.6");

        assertThat(resolve()).isEqualTo("203.0.113.9");
    }

    @Test
    void oneHop_behindACloudflareEdge_takesTheEntryTheEdgeAppended_notTheSpoofedLeftEnd() {
        properties.setTrustedProxyHops(1);
        request.setRemoteAddr("172.64.10.20"); // the Cloudflare edge Tomcat left as the peer
        request.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.7");

        assertThat(resolve()).isEqualTo("198.51.100.7");
    }

    @Test
    void moreHopsThanKnownAddresses_isUnknown_notTheProxyAddress() {
        properties.setTrustedProxyHops(2);
        request.setRemoteAddr("172.64.10.20");
        request.addHeader("X-Forwarded-For", "198.51.100.7");

        assertThat(resolve()).isNull();
    }

    @Test
    void anUnparseableEntryAtThePickedPosition_isUnknown() {
        properties.setTrustedProxyHops(1);
        request.addHeader("X-Forwarded-For", "not-an-ip");

        assertThat(resolve()).isNull();
    }

    @Test
    void aConfiguredHeader_winsWhenItHoldsAnAddress() {
        properties.setHeader("CF-Connecting-IP");
        request.addHeader("CF-Connecting-IP", "198.51.100.77");
        request.addHeader("X-Forwarded-For", "6.6.6.6");

        assertThat(resolve()).isEqualTo("198.51.100.77");
    }

    @Test
    void aConfiguredHeaderWithGarbage_fallsBackToTheChain() {
        properties.setHeader("CF-Connecting-IP");
        request.addHeader("CF-Connecting-IP", "<script>");

        assertThat(resolve()).isEqualTo("203.0.113.9");
    }

    @Test
    void ipv6Clients_areBucketedByTheirSlash64_soOneHostCannotRotateThroughItsWholeRange() {
        request.setRemoteAddr("2001:db8:abcd:12:ffff:ffff:ffff:ffff");
        String first = resolve();
        request.setRemoteAddr("2001:db8:abcd:12:1:2:3:4");

        assertThat(resolve()).isEqualTo(first);
        request.setRemoteAddr("2001:db8:abcd:13:1:2:3:4");
        assertThat(resolve()).isNotEqualTo(first);
    }

    @Test
    void ipv4MappedIpv6_isTheIpv4Address() {
        request.setRemoteAddr("::ffff:198.51.100.5");

        assertThat(resolve()).isEqualTo("198.51.100.5");
    }

    @Test
    void anIpv4WithAPort_losesThePort() {
        properties.setTrustedProxyHops(1);
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.7:51234");

        assertThat(resolve()).isEqualTo("198.51.100.7");
    }

    @Test
    void aMissingOrBlankPeer_isUnknown() {
        request.setRemoteAddr("");

        assertThat(resolve()).isNull();
    }

    @Test
    void neverResolvesNames_soAHostileHeaderCannotTriggerADnsLookup() {
        properties.setTrustedProxyHops(1);
        request.addHeader("X-Forwarded-For", "attacker.example.com");

        assertThat(resolve()).isNull();
    }

    @Test
    void aMultiValuedHeaderRepeatedAcrossLines_isJoined() {
        properties.setTrustedProxyHops(1);
        request.setRemoteAddr("172.64.10.20");
        request.addHeader("X-Forwarded-For", "6.6.6.6");
        request.addHeader("X-Forwarded-For", "198.51.100.7");

        assertThat(resolve()).isEqualTo("198.51.100.7");
    }
}
