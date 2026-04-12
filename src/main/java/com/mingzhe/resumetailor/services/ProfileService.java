package com.mingzhe.resumetailor.services;

import com.mingzhe.resumetailor.dtos.CreateProfileDTO;
import com.mingzhe.resumetailor.entities.Profile;
import com.mingzhe.resumetailor.mappers.ProfileMapper;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {
    private final ProfileMapper profileMapper;

    public ProfileService(ProfileMapper profileMapper) {
        this.profileMapper = profileMapper;
    }

    // Construct the profile entity from the uploaded profile DTO and call the mapper
    public Profile createProfile(CreateProfileDTO profile) {
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

}
