package io.github.pritamnb.cardtxn.fraud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FraudRulesTest {

    @Test
    void approvesNormalTransaction() {
        assertTrue(FraudRules.evaluate(new BigDecimal("25.00"), 1).isEmpty());
    }

    @Test
    void exactlyOneThousandIsNotHighAmount() {
        assertTrue(FraudRules.evaluate(new BigDecimal("1000.00"), 1).isEmpty());
    }

    @Test
    void flagsHighAmount() {
        assertEquals("HIGH_AMOUNT: over 1000.00",
                FraudRules.evaluate(new BigDecimal("1000.01"), 1).orElseThrow());
    }

    @Test
    void flagsVelocityAtThreeSwipes() {
        assertTrue(FraudRules.evaluate(new BigDecimal("5.00"), 2).isEmpty());
        assertEquals("VELOCITY: 3 swipes in 60s",
                FraudRules.evaluate(new BigDecimal("5.00"), 3).orElseThrow());
    }
}
