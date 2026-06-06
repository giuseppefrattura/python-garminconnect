package it.giuseppefrattura.garminservice.repository;

import it.giuseppefrattura.garminservice.model.ExerciseNameMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository for {@link ExerciseNameMapping}.
 */
@Repository
public interface ExerciseNameMappingRepository extends JpaRepository<ExerciseNameMapping, String> {
}
