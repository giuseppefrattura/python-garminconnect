package it.giuseppefrattura.garminservice.repository;

import it.giuseppefrattura.garminservice.model.DailyHealthMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyHealthMetricRepository extends JpaRepository<DailyHealthMetric, LocalDate> {

    Optional<DailyHealthMetric> findByMetricDate(LocalDate metricDate);

    List<DailyHealthMetric> findByMetricDateBetweenOrderByMetricDateAsc(LocalDate startDate, LocalDate endDate);

    @Query("SELECT d FROM DailyHealthMetric d ORDER BY d.metricDate DESC LIMIT :limit")
    List<DailyHealthMetric> findRecentMetrics(@Param("limit") int limit);

    Optional<DailyHealthMetric> findTopByOrderByMetricDateDesc();
}
