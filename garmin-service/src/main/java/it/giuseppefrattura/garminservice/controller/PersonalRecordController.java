package it.giuseppefrattura.garminservice.controller;

import it.giuseppefrattura.garminservice.model.ExercisePersonalRecord;
import it.giuseppefrattura.garminservice.service.PersonalRecordService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/personal-records")
public class PersonalRecordController {

    private final PersonalRecordService personalRecordService;

    public PersonalRecordController(PersonalRecordService personalRecordService) {
        this.personalRecordService = personalRecordService;
    }

    /**
     * Get all Personal Record cards for the Trophy Room.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getTrophyRoom() {
        List<PersonalRecordService.TrophyCardDTO> cards = personalRecordService.getTrophyRoomCards();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "totalExercises", cards.size(),
                "data", cards
        ));
    }

    /**
     * Get the progression timeline of PRs for a specific exercise.
     */
    @GetMapping("/timeline")
    public ResponseEntity<Map<String, Object>> getExerciseTimeline(
            @RequestParam(value = "exercise", required = false) String exercise,
            @RequestParam(value = "exerciseName", required = false) String exerciseName) {
        String target = exerciseName != null && !exerciseName.isBlank() ? exerciseName : exercise;
        List<ExercisePersonalRecord> timeline = personalRecordService.getExerciseTimeline(target);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "exercise", target != null ? target : "",
                "data", timeline
        ));
    }

    /**
     * Force full retroactive recalculation of all personal records from historical workouts.
     */
    @PostMapping("/recalculate")
    public ResponseEntity<Map<String, Object>> recalculateRecords() {
        Map<String, Object> result = personalRecordService.recalculateAllRecords();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Personal records recalculated successfully",
                "details", result
        ));
    }
}
