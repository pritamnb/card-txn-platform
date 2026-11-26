package io.github.pritamnb.cardtxn.fraud;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Pure fraud rules: no AWS calls, so they are trivial to unit test.
 * Real systems use ML scores; these two rules are enough to show the flow.
 */
public final class FraudRules {

    static final BigDecimal HIGH_AMOUNT = new BigDecimal("1000.00");
    static final int VELOCITY_LIMIT = 3;           // swipes on one card...
    static final int VELOCITY_WINDOW_SECONDS = 60; // ...within this window

    private FraudRules() {
    }

    /**
     * @param amount            transaction amount
     * @param swipesInWindow    swipes on the same card in the last 60s, including this one
     * @return a reason if the transaction should be flagged
     */
    public static Optional<String> evaluate(BigDecimal amount, int swipesInWindow) {
        if (amount.compareTo(HIGH_AMOUNT) > 0) {
            return Optional.of("HIGH_AMOUNT: over " + HIGH_AMOUNT);
        }
        if (swipesInWindow >= VELOCITY_LIMIT) {
            return Optional.of("VELOCITY: " + swipesInWindow + " swipes in " + VELOCITY_WINDOW_SECONDS + "s");
        }
        return Optional.empty();
    }
}
