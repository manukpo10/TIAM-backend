package com.tiam.challenge.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeviceLabelsTest {

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
        "Mozilla/5.0 (Linux; Android 13; SM-A135M) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"
                + " | Android · Chrome",
        "Mozilla/5.0 (Linux; Android 13; SAMSUNG SM-A135M) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/25.0"
                + " Chrome/121.0.0.0 Mobile Safari/537.36 | Android · Samsung Internet",
        "Mozilla/5.0 (Linux; Android 12; moto g(30) Build/S0RCS32; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0"
                + " Chrome/129.0.6668.81 Mobile Safari/537.36 Instagram 350.0.0.42.81 Android | Android · Instagram",
        "Mozilla/5.0 (Linux; Android 12; moto g(30) Build/S0RCS32; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0"
                + " Chrome/129.0.6668.81 Mobile Safari/537.36 | Android · WebView",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5"
                + " Mobile/15E148 Safari/604.1 | iPhone · Safari",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/129.0.6668.69"
                + " Mobile/15E148 Safari/604.1 | iPhone · Chrome",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
                + " [FBAN/FBIOS;FBAV/480.0.0.38.107] | iPhone · Facebook",
        "Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148"
                + " Safari/604.1 | iPad · Safari",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"
                + " Edg/129.0.0.0 | Windows · Edge",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14.6; rv:131.0) Gecko/20100101 Firefox/131.0 | Mac · Firefox",
        "curl/8.4.0 | Otro · Otro",
    })
    void describesTheDeviceCoarsely(String userAgent, String expected) {
        assertThat(DeviceLabels.describe(userAgent)).isEqualTo(expected);
    }

    @Test
    void missingUserAgent_isUnknown() {
        assertThat(DeviceLabels.describe(null)).isEqualTo("Desconocido");
        assertThat(DeviceLabels.describe("  ")).isEqualTo("Desconocido");
    }

    @Test
    void theLabelAlwaysFitsItsColumn() {
        assertThat(DeviceLabels.describe("x".repeat(10_000))).hasSizeLessThanOrEqualTo(DeviceLabels.MAX_LENGTH);
    }
}
