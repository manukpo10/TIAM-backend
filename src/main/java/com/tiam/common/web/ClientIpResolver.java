package com.tiam.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Works out which visitor a request came from, for the rate limits and the "same visit" checks.
 *
 * <p>Never trusts the left end of {@code X-Forwarded-For}: any client can write that. It starts from the
 * right, where the proxies you control append what THEY saw, and walks in {@code trustedProxyHops} positions.
 * The chain it reads is {@code [remaining X-Forwarded-For entries..., peer address]}: Tomcat's remote-ip valve
 * (server.forward-headers-strategy: native) has already removed the private-network proxies and made the first
 * public address the peer, so with no extra public proxy the peer IS the visitor.
 *
 * <p>When the address cannot be determined the answer is {@code null} ("unknown"), never the proxy's own
 * address: callers must then skip IP-based limits (fail open) rather than put every buyer into one bucket.
 *
 * <p>Nothing here resolves names: an entry that is not an IP literal is rejected before any {@link InetAddress}
 * call, so a hostile header cannot trigger a DNS lookup. IPv6 visitors are bucketed by their /64, since one
 * host usually owns the whole prefix and could otherwise rotate through addresses to dodge a limit.
 */
@Slf4j
@Component
@EnableConfigurationProperties(ClientIpProperties.class)
public class ClientIpResolver {

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?::\\d{1,5})?");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]{2,45}");

    private final ClientIpProperties properties;

    public ClientIpResolver(ClientIpProperties properties) {
        this.properties = properties;
    }

    /** @return the visitor's address as a stable rate-limit key, or null when it cannot be determined */
    public String resolve(HttpServletRequest request) {
        String resolved = doResolve(request);
        if (properties.isDiagnostics()) {
            log.info("client-ip diagnostics: peer={} x-forwarded-for={} cf-connecting-ip={} true-client-ip={} "
                            + "hops={} header={} -> resolved={}",
                    request.getRemoteAddr(), request.getHeader("X-Forwarded-For"),
                    request.getHeader("CF-Connecting-IP"), request.getHeader("True-Client-IP"),
                    properties.getTrustedProxyHops(), properties.getHeader(), resolved);
        }
        return resolved;
    }

    private String doResolve(HttpServletRequest request) {
        String headerName = properties.getHeader();
        if (StringUtils.hasText(headerName)) {
            String fromHeader = canonical(request.getHeader(headerName.strip()));
            if (fromHeader != null) {
                return fromHeader;
            }
        }

        List<String> chain = new ArrayList<>();
        Collections.list(request.getHeaders("X-Forwarded-For")).forEach(line -> {
            for (String part : line.split(",")) {
                if (StringUtils.hasText(part)) {
                    chain.add(part.strip());
                }
            }
        });
        String peer = request.getRemoteAddr();
        if (!StringUtils.hasText(peer)) {
            return null;
        }
        chain.add(peer.strip());

        int index = chain.size() - 1 - Math.max(0, properties.getTrustedProxyHops());
        if (index < 0) {
            return null;
        }
        return canonical(chain.get(index));
    }

    /** The rate-limit key for an IP literal (IPv4 as is, IPv6 as its /64), or null if it is not one. */
    static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty() || value.length() > 60) {
            return null;
        }
        var v4 = IPV4.matcher(value);
        if (v4.matches()) {
            int[] octets = new int[4];
            for (int i = 0; i < 4; i++) {
                octets[i] = Integer.parseInt(v4.group(i + 1));
                if (octets[i] > 255) {
                    return null;
                }
            }
            return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
        }
        if (value.startsWith("[") && value.contains("]")) {
            value = value.substring(1, value.indexOf(']'));
        }
        if (value.indexOf(':') < 0 || !IPV6_CHARS.matcher(value).matches()) {
            return null;
        }
        try {
            // The brackets make the JDK treat it strictly as an IPv6 literal: a malformed one is an error, never a DNS query.
            InetAddress address = InetAddress.getByName("[" + value + "]");
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + "." + (bytes[3] & 0xff);
            }
            StringBuilder prefix = new StringBuilder("v6:");
            for (int i = 0; i < 8; i++) {
                prefix.append(String.format("%02x", bytes[i] & 0xff));
                if (i % 2 == 1 && i < 7) {
                    prefix.append(':');
                }
            }
            return prefix.append("::/64").toString();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
