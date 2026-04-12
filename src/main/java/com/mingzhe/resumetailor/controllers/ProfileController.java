package com.mingzhe.resumetailor.controllers;

import com.mingzhe.resumetailor.dtos.CreateProfileDTO;
import com.mingzhe.resumetailor.dtos.UpdateProfileDTO;
import com.mingzhe.resumetailor.entities.Profile;
import com.mingzhe.resumetailor.services.ProfileService;
import jakarta.validation.Valid;
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
    public Profile createProfile (@RequestBody @Valid CreateProfileDTO profile) {
        return profileService.createProfile(profile);
    }

    // Fetch the profile of a given user id from DB
    @GetMapping("/{userId}")
    public Profile fetchProfile (@PathVariable Long userId) {
        return profileService.fetchProfile(userId);
    }

    // Update the profile of a given user id
    @PutMapping("/update/{userId}")
    public Profile updateProfile (@PathVariable Long userId,
                                  @RequestBody @Valid UpdateProfileDTO profile) {
        return profileService.updateProfile(userId, profile);
    }
}
