package it.giuseppefrattura.garminservice.service;

import it.giuseppefrattura.garminservice.model.DailyHealthMetric;
import org.springframework.stereotype.Service;

@Service
public class ReadinessCalculationService {

    public static class ReadinessResult {
        private final int score;
        private final String level; // OPTIMAL, MODERATE, FATIGUED
        private final String advice;

        public ReadinessResult(int score, String level, String advice) {
            this.score = score;
            this.level = level;
            this.advice = advice;
        }

        public int getScore() { return score; }
        public String getLevel() { return level; }
        public String getAdvice() { return advice; }
    }

    /**
     * Calculate readiness score (0-100) and advice from physiological metrics.
     */
    public ReadinessResult calculateReadiness(DailyHealthMetric metric) {
        if (metric == null) {
            return new ReadinessResult(70, "MODERATE", "Dati fisiologici non disponibili. Procedi con allenamento standard a sensazione.");
        }

        double sleepScore = computeSleepScore(metric);
        double bbScore = computeBodyBatteryScore(metric);
        double hrvScore = computeHrvScore(metric);
        double stressScore = computeStressScore(metric);

        double weighted = (0.35 * sleepScore) + (0.30 * bbScore) + (0.20 * hrvScore) + (0.15 * stressScore);
        int finalScore = (int) Math.round(Math.max(1.0, Math.min(100.0, weighted)));

        return toReadinessResult(finalScore);
    }

    private double computeSleepScore(DailyHealthMetric metric) {
        if (metric.getSleepScore() != null && metric.getSleepScore() > 0) {
            return metric.getSleepScore();
        }
        if (metric.getSleepDurationSeconds() == null || metric.getSleepDurationSeconds() <= 0) {
            return 70.0;
        }
        double hours = metric.getSleepDurationSeconds() / 3600.0;
        if (hours >= 7.5 && hours <= 9.0) return 90.0;
        if (hours >= 6.5) return 75.0;
        if (hours >= 5.5) return 60.0;
        return 40.0;
    }

    private double computeBodyBatteryScore(DailyHealthMetric metric) {
        if (metric.getBodyBatteryWake() != null && metric.getBodyBatteryWake() > 0) {
            return metric.getBodyBatteryWake();
        }
        if (metric.getBodyBatteryMax() != null && metric.getBodyBatteryMax() > 0) {
            return metric.getBodyBatteryMax();
        }
        return 70.0;
    }

    private double computeHrvScore(DailyHealthMetric metric) {
        if (metric.getHrvStatus() != null) {
            return scoreFromHrvStatus(metric.getHrvStatus());
        }
        if (metric.getHrvNightlyAvg() != null && metric.getHrvWeeklyAvg() != null && metric.getHrvWeeklyAvg() > 0) {
            double ratio = metric.getHrvNightlyAvg() / metric.getHrvWeeklyAvg();
            if (ratio >= 0.95) return 90.0;
            if (ratio >= 0.85) return 70.0;
            return 45.0;
        }
        return 75.0;
    }

    private double scoreFromHrvStatus(String rawStatus) {
        String status = rawStatus.toUpperCase();
        if (status.contains("BALANCED") || status.contains("OPTIMAL")) {
            return 95.0;
        }
        if (status.contains("UNBALANCED")) {
            return 60.0;
        }
        if (status.contains("LOW") || status.contains("POOR")) {
            return 40.0;
        }
        return 75.0;
    }

    private double computeStressScore(DailyHealthMetric metric) {
        if (metric.getAvgStressLevel() != null && metric.getAvgStressLevel() >= 0) {
            return Math.max(0.0, Math.min(100.0, 100.0 - metric.getAvgStressLevel()));
        }
        return 70.0;
    }

    private ReadinessResult toReadinessResult(int finalScore) {
        if (finalScore >= 80) {
            return new ReadinessResult(
                    finalScore,
                    "OPTIMAL",
                    "Recupero eccellente! Sistema nervoso e riserve energetiche al top. Giorno ideale per carichi pesanti, tentativi di PR o sessioni ad alto volume.");
        }
        if (finalScore >= 55) {
            return new ReadinessResult(
                    finalScore,
                    "MODERATE",
                    "Recupero nella norma. Fisiologia stabile e buona disponibilità energetica: procedi con l'allenamento programmato mantenendo un RPE 7-8.");
        }
        return new ReadinessResult(
                finalScore,
                "FATIGUED",
                "Segnali di affaticamento fisiologico (HRV ridotto o sonno non ottimale). Consigliata una sessione di scarico (deload), cardio Z1/Z2 o riposo attivo.");
    }
}
