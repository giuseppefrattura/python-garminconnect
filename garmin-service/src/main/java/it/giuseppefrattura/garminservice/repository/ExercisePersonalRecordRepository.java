package it.giuseppefrattura.garminservice.repository;

import it.giuseppefrattura.garminservice.model.ExercisePersonalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExercisePersonalRecordRepository extends JpaRepository<ExercisePersonalRecord, Long> {

    List<ExercisePersonalRecord> findByExerciseNameOrderByAchievedAtAsc(String exerciseName);

    List<ExercisePersonalRecord> findByActivityId(Long activityId);

    List<ExercisePersonalRecord> findBySetId(Long setId);

    @Modifying
    @Query("DELETE FROM ExercisePersonalRecord r WHERE r.activityId = :activityId")
    void deleteByActivityId(@Param("activityId") Long activityId);

    @Modifying
    @Query("DELETE FROM ExercisePersonalRecord r WHERE r.setId = :setId")
    void deleteBySetId(@Param("setId") Long setId);

    @Query("SELECT r FROM ExercisePersonalRecord r ORDER BY r.muscleGroup ASC, r.exerciseName ASC, r.recordType ASC")
    List<ExercisePersonalRecord> findAllOrdered();

    @Query(value = """
        SELECT DISTINCT ON (exercise_name, record_type) *
        FROM exercise_personal_record
        ORDER BY exercise_name, record_type, record_value DESC, achieved_at DESC
        """, nativeQuery = true)
    List<ExercisePersonalRecord> findCurrentBestRecords();
}
