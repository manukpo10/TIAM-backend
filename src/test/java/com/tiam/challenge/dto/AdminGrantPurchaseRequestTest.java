package com.tiam.challenge.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean-validation constraints on the admin grant payload. The DTO must NOT
 * cap {@code challengeMonth}: the service bounds it by
 * {@code ChallengeDayCatalog#hasMonth}, so a month that has a catalog but is
 * not on sale yet (pre-launch test links) has to get past the controller.
 * A {@code @Max} here would silently block it again — the service and
 * controller tests never run Bean Validation, so only this test catches it.
 */
class AdminGrantPurchaseRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static AdminGrantPurchaseRequest grant(Integer month) {
        return new AdminGrantPurchaseRequest("Ana", "+5492215555555", null, month);
    }

    @Test
    void monthNotOnSaleYet_isValid() {
        assertThat(validator.validate(grant(5))).isEmpty();
    }

    @Test
    void monthOnSale_isValid() {
        assertThat(validator.validate(grant(1))).isEmpty();
    }

    @Test
    void monthZeroOrNegative_isInvalid() {
        assertThat(validator.validate(grant(0))).isNotEmpty();
        assertThat(validator.validate(grant(-3))).isNotEmpty();
    }

    @Test
    void nullMonth_isInvalid() {
        assertThat(validator.validate(grant(null))).isNotEmpty();
    }
}
