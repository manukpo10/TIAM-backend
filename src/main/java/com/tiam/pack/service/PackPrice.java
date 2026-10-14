package com.tiam.pack.service;

import java.math.BigDecimal;

/** A server-computed price breakdown. Every amount carries exactly two decimals. */
public record PackPrice(BigDecimal pack, BigDecimal games, BigDecimal extra, BigDecimal total) {
}
