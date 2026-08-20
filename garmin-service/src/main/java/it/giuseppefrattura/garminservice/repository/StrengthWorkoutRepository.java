package it.giuseppefrattura.garminservice.repository;

import it.giuseppefrattura.garminservice.model.StrengthWorkout;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

/**
 * Spring Data JPA Repository for {@link StrengthWorkout}.
 */
@Repository
public interface StrengthWorkoutRepository extends JpaRepository<StrengthWorkout, Long> {

    /**
     * Find the most recent strength training activity with sets eagerly fetched.
     */
    @EntityGraph(attributePaths = {"sets"})
    Optional<StrengthWorkout> findFirstByOrderByWorkoutDateDescWorkoutTimeDesc();

    /**
     * Check if a workout has already been persisted by its Garmin activity ID.
     */
    boolean existsByActivityId(Long activityId);

    /**
     * Retrieve all workouts ordered by date and time descending with sets eagerly fetched.
     */
    @EntityGraph(attributePaths = {"sets"})
    List<StrengthWorkout> findAllByOrderByWorkoutDateDescWorkoutTimeDesc();
}
