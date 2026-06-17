package it.giuseppefrattura.garminservice.repository;

import it.giuseppefrattura.garminservice.model.StrengthWorkoutSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA Repository for {@link StrengthWorkoutSet}.
 */
@Repository
public interface StrengthWorkoutSetRepository extends JpaRepository<StrengthWorkoutSet, Long> {

    /**
     * Retrieve all unique exercise names present in the database.
     */
    @Query("SELECT DISTINCT s.exerciseName FROM StrengthWorkoutSet s WHERE s.exerciseName IS NOT NULL AND s.exerciseName <> '' ORDER BY s.exerciseName ASC")
    List<String> findDistinctExerciseNames();
}
