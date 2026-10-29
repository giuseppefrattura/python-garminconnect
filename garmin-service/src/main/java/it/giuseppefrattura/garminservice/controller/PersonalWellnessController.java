package it.giuseppefrattura.garminservice.controller;

import it.giuseppefrattura.garminservice.service.PersonalWellnessService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/personal-wellness")
public class PersonalWellnessController {
    private final PersonalWellnessService service;
    public PersonalWellnessController(PersonalWellnessService service) { this.service = service; }

    @GetMapping
    public PersonalWellnessService.Result get(@RequestParam(defaultValue = "30") int days,
                                               @RequestParam(required = false) LocalDate end) {
        if (days != 7 && days != 30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 7 or 30");
        try { return service.load(end == null ? LocalDate.now() : end, days); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Garmin Personal API unavailable", e); }
    }
}
