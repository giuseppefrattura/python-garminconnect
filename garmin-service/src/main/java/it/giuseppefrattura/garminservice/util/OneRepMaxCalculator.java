package it.giuseppefrattura.garminservice.util;

/**
 * Single source of truth for estimated one-rep-max (1RM) calculation.
 * <p>
 * Formula selection:
 * <ul>
 *   <li>1 rep: exact lifted weight</li>
 *   <li>2–10 reps: Brzycki — weight / (1.0278 - 0.0278 × reps)</li>
 *   <li>&gt;10 reps: Epley — weight × (1 + reps / 30)</li>
 * </ul>
 */
public final class OneRepMaxCalculator {

    private OneRepMaxCalculator() {
    }

    /**
     * Estimate the one-rep max for a given lifted weight and repetition count.
     *
     * @param weightKg lifted weight in kilograms (must be positive)
     * @param reps     performed repetitions (must be positive)
     * @return estimated 1RM in kg, or 0.0 for non-positive inputs
     */
    public static double estimate1Rm(double weightKg, int reps) {
        if (weightKg <= 0 || reps <= 0) {
            return 0.0;
        }
        if (reps == 1) {
            return weightKg;
        }
        if (reps <= 10) {
            double denom = 1.0278 - (0.0278 * reps);
            return denom > 0 ? (weightKg / denom) : weightKg;
        }
        return weightKg * (1.0 + (reps / 30.0));
    }
}
