package com.tiam.pack.web;

import com.tiam.common.web.ClientIpProperties;
import com.tiam.common.web.ClientIpResolver;

/** The resolver as production builds it by default (no trusted extra proxy), for controller tests. */
final class TestIps {

    static final ClientIpResolver RESOLVER = new ClientIpResolver(new ClientIpProperties());

    private TestIps() {
    }
}
