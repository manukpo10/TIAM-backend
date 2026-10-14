package com.tiam.pack.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PackExternalReferenceTest {

    @Test
    void of_buildsTheReferenceMercadoPagoCarries() {
        assertThat(PackExternalReference.of(42)).isEqualTo("pack:42");
    }

    @Test
    void parse_extractsTheOrderId() {
        assertThat(PackExternalReference.parse("pack:42")).hasValue(42L);
        assertThat(PackExternalReference.parse("pack:9007199254740")).hasValue(9007199254740L);
    }

    @Test
    void parse_rejectsAnythingThatIsNotAPositiveNumberAfterThePrefix() {
        assertThat(PackExternalReference.parse("pack:")).isEmpty();
        assertThat(PackExternalReference.parse("pack:abc")).isEmpty();
        assertThat(PackExternalReference.parse("pack:-1")).isEmpty();
        assertThat(PackExternalReference.parse("pack:0")).isEmpty();
        assertThat(PackExternalReference.parse("pack:12 ")).isEmpty();
        assertThat(PackExternalReference.parse("pack:1e3")).isEmpty();
        assertThat(PackExternalReference.parse("pack:99999999999999999999")).isEmpty();
    }

    @Test
    void parse_ignoresReferencesOfOtherProducts() {
        assertThat(PackExternalReference.parse("42")).isEmpty();
        assertThat(PackExternalReference.parse("curso:42")).isEmpty();
        assertThat(PackExternalReference.parse("PACK:42")).isEmpty();
        assertThat(PackExternalReference.parse(null)).isEmpty();
    }

    @Test
    void isPackReference_isTrueOnlyForThePrefix() {
        assertThat(PackExternalReference.isPackReference("pack:abc")).isTrue();
        assertThat(PackExternalReference.isPackReference("42")).isFalse();
        assertThat(PackExternalReference.isPackReference(null)).isFalse();
    }
}
