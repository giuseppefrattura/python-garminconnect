package it.giuseppefrattura.garminservice.repository;

import it.giuseppefrattura.garminservice.model.RunningHrZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link RunningHrZone}.
 */
@Repository
public interface RunningHrZoneRepository extends JpaRepository<RunningHrZone, Integer> {

    /**
     * Find a record by its Garmin activity ID (for upsert logic).
     */
    Optional<RunningHrZone> findByActivityId(Long activityId);

    /**
     * Find all records sorted by run date descending and run time descending.
     */
    List<RunningHrZone> findAllByOrderByRunDateDescRunTimeDesc();
}
