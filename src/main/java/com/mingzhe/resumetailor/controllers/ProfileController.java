package com.mingzhe.resumetailor.controllers;

import com.mingzhe.resumetailor.dtos.CreateProfileDTO;
import com.mingzhe.resumetailor.entities.Profile;
import com.mingzhe.resumetailor.services.ProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profiles")
public class ProfileController {
    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    // Create a new profile.
    @PostMapping("/create")
    public Profile createProfile (@RequestBody @Valid CreateProfileDTO profile) {
        return profileService.createProfile(profile);
    }
}
