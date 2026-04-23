package com.mingzhe.resumetailor.resume;

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
 * REST endpoints for managing Resume records.
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @PostMapping("/create")
    public ResponseEntity<Resume> createResume(@RequestBody @Valid CreateResumeDTO request) {
        Resume createdResume = resumeService.createResume(request);
        return ResponseEntity.status(201).body(createdResume);
    }

    @GetMapping("/fetch/{jobId}")
    public ResponseEntity<List<Resume>> getResumesByJobId(@PathVariable Long jobId) {
        return ResponseEntity.ok(resumeService.fetchResumesByJobId(jobId));
    }

    @PutMapping("/update/{id}")
    public ResponseEntity<Resume> updateResume(@PathVariable Long id,
                                               @RequestBody UpdateResumeDTO request) {
        return ResponseEntity.ok(resumeService.updateResume(id, request));
    }

    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Void> deleteResume(@PathVariable Long id) {
        resumeService.deleteResume(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/generate/{jobId}")
    public ResponseEntity<String> generateResume(@PathVariable Long jobId) {
        return ResponseEntity.ok(resumeService.generateResume(jobId));
    }

}
