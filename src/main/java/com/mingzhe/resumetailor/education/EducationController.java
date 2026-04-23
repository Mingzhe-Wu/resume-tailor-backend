package com.mingzhe.resumetailor.education;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for managing Education records.
 */
@RestController
@RequestMapping("/api/education")
public class EducationController {

    private final EducationService educationService;

    public EducationController(EducationService educationService) {
        this.educationService = educationService;
    }

    @PostMapping("/create")
    public ResponseEntity<Education> createEducation(@RequestBody @Valid CreateEducationDTO request) {
        Education createdEducation = educationService.createEducation(request);
        return ResponseEntity.status(201).body(createdEducation);
    }

    @GetMapping("/fetch/{profileId}")
    public ResponseEntity<List<Education>> getEducationsByProfileId(@PathVariable Long profileId) {
        return ResponseEntity.ok(educationService.fetchEducationsByProfileId(profileId));
    }

    @PutMapping("/update/{id}")
    public ResponseEntity<Education> updateEducation(@PathVariable Long id,
                                                     @RequestBody UpdateEducationDTO request) {
        return ResponseEntity.ok(educationService.updateEducation(id, request));
    }

    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Void> deleteEducation(@PathVariable Long id) {
        educationService.deleteEducation(id);
        return ResponseEntity.noContent().build();
    }

}
