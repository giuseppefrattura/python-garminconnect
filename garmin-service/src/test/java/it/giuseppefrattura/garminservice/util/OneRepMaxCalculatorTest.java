package it.giuseppefrattura.garminservice.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OneRepMaxCalculatorTest {

    @Test
    @DisplayName("estimate1Rm returns 0 for non-positive weight or reps")
    void returnsZeroForInvalidInput() {
        assertEquals(0.0, OneRepMaxCalculator.estimate1Rm(0, 10));
        assertEquals(0.0, OneRepMaxCalculator.estimate1Rm(-5, 10));
        assertEquals(0.0, OneRepMaxCalculator.estimate1Rm(100, 0));
        assertEquals(0.0, OneRepMaxCalculator.estimate1Rm(100, -3));
    }

    @Test
    @DisplayName("1 rep equals exact lifted weight")
    void singleRepEqualsWeight() {
        assertEquals(100.0, OneRepMaxCalculator.estimate1Rm(100.0, 1));
    }

    @Test
    @DisplayName("2-10 reps use Brzycki formula")
    void brzyckiForLowReps() {
        // 6 reps at 70 kg: 70 / (1.0278 - 0.0278 * 6) = 70 / 0.861 = ~81.3 kg
        double est6reps = OneRepMaxCalculator.estimate1Rm(70.0, 6);
        assertTrue(est6reps > 81.0 && est6reps < 82.0, "Expected ~81.3 kg, got: " + est6reps);

        // 10 reps at 60 kg: 60 / (1.0278 - 0.278) = 60 / 0.7498 = ~80.02 kg
        double est10reps = OneRepMaxCalculator.estimate1Rm(60.0, 10);
        assertTrue(est10reps > 79.5 && est10reps < 80.5, "Expected ~80.0 kg, got: " + est10reps);
    }

    @Test
    @DisplayName(">10 reps use Epley formula")
    void epleyForHighReps() {
        // 15 reps at 50 kg: 50 * (1 + 15/30) = 75.0 kg
        assertEquals(75.0, OneRepMaxCalculator.estimate1Rm(50.0, 15), 0.01);

        // 20 reps at 40 kg: 40 * (1 + 20/30) = ~66.67 kg
        assertEquals(66.67, OneRepMaxCalculator.estimate1Rm(40.0, 20), 0.01);
    }

    @Test
    @DisplayName("estimates are monotonically increasing with weight")
    void monotonicInWeight() {
        assertTrue(OneRepMaxCalculator.estimate1Rm(50, 5) < OneRepMaxCalculator.estimate1Rm(60, 5));
        assertTrue(OneRepMaxCalculator.estimate1Rm(50, 15) < OneRepMaxCalculator.estimate1Rm(60, 15));
    }
}
