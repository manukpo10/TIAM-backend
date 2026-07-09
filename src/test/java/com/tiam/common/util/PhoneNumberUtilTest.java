package com.tiam.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNumberUtilTest {

    @Test
    void normalize_withCountryCodeAndMobileNine_stripsNine() {
        assertThat(PhoneNumberUtil.normalize("5491122334455")).isEqualTo("541122334455");
    }

    @Test
    void normalize_withCountryCodeNoMobileNine_staysAsIs() {
        assertThat(PhoneNumberUtil.normalize("541122334455")).isEqualTo("541122334455");
    }

    @Test
    void normalize_withoutCountryCode_prependsIt() {
        assertThat(PhoneNumberUtil.normalize("1122334455")).isEqualTo("541122334455");
    }

    @Test
    void normalize_withLeadingTrunkZero_stripsZeroThenPrependsCountryCode() {
        assertThat(PhoneNumberUtil.normalize("01122334455")).isEqualTo("541122334455");
    }

    @Test
    void normalize_withSpacesAndDashesAndParens_stripsFormatting() {
        assertThat(PhoneNumberUtil.normalize("+54 9 11 2233-4455")).isEqualTo("541122334455");
    }

    @Test
    void normalize_null_returnsEmptyString() {
        assertThat(PhoneNumberUtil.normalize(null)).isEqualTo("");
    }

    @Test
    void normalize_blank_returnsEmptyString() {
        assertThat(PhoneNumberUtil.normalize("   ")).isEqualTo("");
    }

    @Test
    void normalize_implausiblyShortInput_returnsEmptyStringInsteadOfBogusKey() {
        // "0" and "abc" both strip down to a handful of digits at most, which would
        // otherwise collapse to the same short, collision-prone canonical key ("54")
        // for two different garbage inputs — reject them instead of pretending
        // they're a valid number.
        assertThat(PhoneNumberUtil.normalize("0")).isEqualTo("");
        assertThat(PhoneNumberUtil.normalize("abc")).isEqualTo("");
    }

    @Test
    void toWhatsAppSendFormat_normalizedNumber_reinsertsNine() {
        assertThat(PhoneNumberUtil.toWhatsAppSendFormat("541122334455")).isEqualTo("5491122334455");
    }

    @Test
    void toWhatsAppSendFormat_nonArgentinePrefix_throwsIllegalArgument() {
        assertThatThrownBy(() -> PhoneNumberUtil.toWhatsAppSendFormat("11122334455"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
