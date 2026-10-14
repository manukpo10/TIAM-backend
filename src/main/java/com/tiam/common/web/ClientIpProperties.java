package com.tiam.common.web;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How to find the real visitor behind the hosting platform's proxies ({@code tiam.net.client-ip}).
 *
 * <p>The defaults are the safe reading of "Tomcat already did the standard thing" ({@code
 * server.forward-headers-strategy: native} skips the private-network proxies and leaves the first public
 * address as the peer). They must be confirmed on the live deployment, which is why {@link #diagnostics}
 * exists: see {@link ClientIpResolver}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tiam.net.client-ip")
public class ClientIpProperties {

    /**
     * Optional single-valued header that a proxy YOU trust sets to the visitor's address, for example
     * {@code CF-Connecting-IP}. Only set it when every request really passes through that proxy: a header the
     * visitor can send directly is a header the visitor can forge.
     */
    private String header = "";

    /**
     * How many proxies with PUBLIC addresses (Cloudflare, for example) sit in front of the app on top of the
     * private-network ones Tomcat already skips. Each of them appends one address to X-Forwarded-For, so the
     * visitor is that many positions from the right end. 0 = the peer address Tomcat resolved is the visitor.
     */
    private int trustedProxyHops = 0;

    /** Logs the raw peer, forwarded headers and the resolved address of every pack request. Off by default. */
    private boolean diagnostics = false;
}
