package com.mingzhe.resumetailor.skill;

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
 * REST endpoints for managing Skill records.
 */
@RestController
@RequestMapping("/api/skill")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @PostMapping("/create")
    public ResponseEntity<Skill> createSkill(@RequestBody @Valid CreateSkillDTO request) {
        Skill createdSkill = skillService.createSkill(request);
        return ResponseEntity.status(201).body(createdSkill);
    }

    @GetMapping("/fetch/{profileId}")
    public ResponseEntity<List<Skill>> getSkillsByProfileId(@PathVariable Long profileId) {
        return ResponseEntity.ok(skillService.fetchSkillsByProfileId(profileId));
    }

    @PutMapping("/update/{id}")
    public ResponseEntity<Skill> updateSkill(@PathVariable Long id,
                                             @RequestBody UpdateSkillDTO request) {
        return ResponseEntity.ok(skillService.updateSkill(id, request));
    }

    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Void> deleteSkill(@PathVariable Long id) {
        skillService.deleteSkill(id);
        return ResponseEntity.noContent().build();
    }

}
