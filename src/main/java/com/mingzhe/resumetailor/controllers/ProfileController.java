package com.mingzhe.resumetailor.controllers;

import com.mingzhe.resumetailor.dtos.CreateProfileDTO;
import com.mingzhe.resumetailor.dtos.UpdateProfileDTO;
import com.mingzhe.resumetailor.entities.Profile;
import com.mingzhe.resumetailor.services.ProfileService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profiles")
public class ProfileController {
    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    // Create a new profile
    @PostMapping("/create")
    public ResponseEntity<Profile> createProfile (@RequestBody @Valid CreateProfileDTO profile) {
        Profile createdProfile = profileService.createProfile(profile);
        return ResponseEntity.status(201).body(createdProfile);
    }

    // Fetch the profile of a given user id from DB
    @GetMapping("/{userId}")
    public ResponseEntity<Profile> fetchProfile (@PathVariable Long userId) {
        return ResponseEntity.ok(profileService.fetchProfile(userId));
    }

    // Update the profile of a given user id
    @PutMapping("/update/{userId}")
    public ResponseEntity<Profile> updateProfile (@PathVariable Long userId,
                                                  @RequestBody @Valid UpdateProfileDTO profile) {
        return ResponseEntity.ok(profileService.updateProfile(userId, profile));
    }

    // Delete the profile of a given user id
    @DeleteMapping("/delete/{userId}")
    public ResponseEntity<Void> deleteProfile(@PathVariable Long userId) {
        profileService.deleteProfile(userId);
        return ResponseEntity.noContent().build();
    }

}
