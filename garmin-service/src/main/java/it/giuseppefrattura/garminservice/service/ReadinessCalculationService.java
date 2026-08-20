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

        // 1. Sleep Component (Weight: 35%)
        double sleepScore = 70.0;
        if (metric.getSleepScore() != null && metric.getSleepScore() > 0) {
            sleepScore = metric.getSleepScore();
        } else if (metric.getSleepDurationSeconds() != null && metric.getSleepDurationSeconds() > 0) {
            double hours = metric.getSleepDurationSeconds() / 3600.0;
            if (hours >= 7.5 && hours <= 9.0) {
                sleepScore = 90.0;
            } else if (hours >= 6.5) {
                sleepScore = 75.0;
            } else if (hours >= 5.5) {
                sleepScore = 60.0;
            } else {
                sleepScore = 40.0;
            }
        }

        // 2. Body Battery Component (Weight: 30%)
        double bbScore = 70.0;
        if (metric.getBodyBatteryWake() != null && metric.getBodyBatteryWake() > 0) {
            bbScore = metric.getBodyBatteryWake();
        } else if (metric.getBodyBatteryMax() != null && metric.getBodyBatteryMax() > 0) {
            bbScore = metric.getBodyBatteryMax();
        }

        // 3. HRV Component (Weight: 20%)
        double hrvScore = 75.0;
        if (metric.getHrvStatus() != null) {
            String status = metric.getHrvStatus().toUpperCase();
            if (status.contains("BALANCED") || status.contains("OPTIMAL")) {
                hrvScore = 95.0;
            } else if (status.contains("UNBALANCED")) {
                hrvScore = 60.0;
            } else if (status.contains("LOW") || status.contains("POOR")) {
                hrvScore = 40.0;
            }
        } else if (metric.getHrvNightlyAvg() != null && metric.getHrvWeeklyAvg() != null && metric.getHrvWeeklyAvg() > 0) {
            double ratio = metric.getHrvNightlyAvg() / metric.getHrvWeeklyAvg();
            if (ratio >= 0.95) {
                hrvScore = 90.0;
            } else if (ratio >= 0.85) {
                hrvScore = 70.0;
            } else {
                hrvScore = 45.0;
            }
        }

        // 4. Stress Component (Weight: 15%)
        double stressScore = 70.0;
        if (metric.getAvgStressLevel() != null && metric.getAvgStressLevel() >= 0) {
            stressScore = Math.max(0.0, Math.min(100.0, 100.0 - metric.getAvgStressLevel()));
        }

        // Calculate weighted sum
        double weighted = (0.35 * sleepScore) + (0.30 * bbScore) + (0.20 * hrvScore) + (0.15 * stressScore);
        int finalScore = (int) Math.round(Math.max(1.0, Math.min(100.0, weighted)));

        String level;
        String advice;

        if (finalScore >= 80) {
            level = "OPTIMAL";
            advice = "Recupero eccellente! Sistema nervoso e riserve energetiche al top. Giorno ideale per carichi pesanti, tentativi di PR o sessioni ad alto volume.";
        } else if (finalScore >= 55) {
            level = "MODERATE";
            advice = "Recupero nella norma. Fisiologia stabile e buona disponibilità energetica: procedi con l'allenamento programmato mantenendo un RPE 7-8.";
        } else {
            level = "FATIGUED";
            advice = "Segnali di affaticamento fisiologico (HRV ridotto o sonno non ottimale). Consigliata una sessione di scarico (deload), cardio Z1/Z2 o riposo attivo.";
        }

        return new ReadinessResult(finalScore, level, advice);
    }
}
