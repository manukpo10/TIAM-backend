package com.tiam.challenge.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Bean-validation constraints on the completion payload. The service layer treats
 * a result with zero attempts as full participation (papel-y-lápiz days), so the
 * DTO must accept it too — the two must not disagree, or those days 400 at the
 * controller before ever reaching the service.
 */
class CompleteDayRequestTest {

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

    @Test
    void paperAndPencilDay_zeroAttemptsZeroMistakes_isValid() {
        // Días 6, 12, 18, 30 report {mistakes:0, totalAttempts:0}. This MUST pass
        // validation — computeStars treats 0 attempts as full accuracy (3 stars por
        // participar) without dividing by zero.
        assertThat(validator.validate(new CompleteDayRequest(0, 0))).isEmpty();
    }

    @Test
    void scoredDay_isValid() {
        assertThat(validator.validate(new CompleteDayRequest(3, 20))).isEmpty();
    }

    @Test
    void negativeMistakes_isInvalid() {
        assertThat(validator.validate(new CompleteDayRequest(-1, 5))).isNotEmpty();
    }

    @Test
    void negativeTotalAttempts_isInvalid() {
        assertThat(validator.validate(new CompleteDayRequest(0, -1))).isNotEmpty();
    }

    @Test
    void nullFields_areInvalid() {
        assertThat(validator.validate(new CompleteDayRequest(null, null))).isNotEmpty();
    }
}
