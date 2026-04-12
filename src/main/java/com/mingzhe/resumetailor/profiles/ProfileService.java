package com.mingzhe.resumetailor.profiles;

import com.mingzhe.resumetailor.exceptions.BadRequestException;
import com.mingzhe.resumetailor.exceptions.ResourceNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {
    private final ProfileMapper profileMapper;

    public ProfileService(ProfileMapper profileMapper) {
        this.profileMapper = profileMapper;
    }

    // Construct the profile entity from the uploaded profile DTO and call the mapper
    public Profile createProfile(CreateProfileDTO profile) {
        // See if profile already exist for the given user id
        Profile existingProfile = profileMapper.findById(profile.getUserId());
        if (existingProfile != null) {
            throw new BadRequestException("Profile already exists for this user");
        }

        Profile profileEntity = new Profile();
        profileEntity.setUserId(profile.getUserId());
        profileEntity.setFullName(profile.getFullName());
        profileEntity.setPhone(profile.getPhone());
        profileEntity.setContactEmail(profile.getContactEmail());
        profileEntity.setLinkedinUrl(profile.getLinkedinUrl());
        profileEntity.setGithubUrl(profile.getGithubUrl());
        profileEntity.setLocation(profile.getLocation());
        profileEntity.setSummary(profile.getSummary());
        profileMapper.insert(profileEntity);
        return profileEntity;
    }

    // Fetch the profile of a given user id from DB
    public Profile fetchProfile(Long userId) {
        return profileMapper.findById(userId);
    }

    // Fetch the existing profile and update
    public Profile updateProfile(Long userId, UpdateProfileDTO request) {
        Profile existingProfile = profileMapper.findById(userId);
        if (existingProfile == null) {
            throw new ResourceNotFoundException("Profile not found");
        }

        existingProfile.setFullName(request.getFullName());
        existingProfile.setPhone(request.getPhone());
        existingProfile.setContactEmail(request.getContactEmail());
        existingProfile.setLinkedinUrl(request.getLinkedinUrl());
        existingProfile.setGithubUrl(request.getGithubUrl());
        existingProfile.setLocation(request.getLocation());
        existingProfile.setSummary(request.getSummary());

        profileMapper.updateById(existingProfile);
        return profileMapper.findById(userId);
    }

    // Delete the profile of a given user id
    public void deleteProfile(Long userId) {
        Profile existingProfile = profileMapper.findById(userId);
        if (existingProfile == null) {
            throw new ResourceNotFoundException("Profile not found");
        }

        profileMapper.deleteById(userId);
    }

}
