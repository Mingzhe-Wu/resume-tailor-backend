package com.mingzhe.resumetailor.skill;

import com.mingzhe.resumetailor.exceptions.ResourceNotFoundException;
import com.mingzhe.resumetailor.profile.Profile;
import com.mingzhe.resumetailor.profile.ProfileMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Business logic for validating and managing Skill records.
 */
@Service
public class SkillService {

    private final SkillMapper skillMapper;
    private final ProfileMapper profileMapper;

    public SkillService(SkillMapper skillMapper, ProfileMapper profileMapper) {
        this.skillMapper = skillMapper;
        this.profileMapper = profileMapper;
    }

    public Skill createSkill(CreateSkillDTO request) {
        Profile profile = profileMapper.findById(request.getProfileId());
        if (profile == null) {
            throw new ResourceNotFoundException("Profile not found");
        }

        Skill skill = new Skill();
        skill.setProfileId(request.getProfileId());
        skill.setCategory(request.getCategory());
        skill.setName(request.getName());

        skillMapper.insert(skill);
        return skill;
    }

    public List<Skill> fetchSkillsByProfileId(Long profileId) {
        Profile profile = profileMapper.findById(profileId);
        if (profile == null) {
            throw new ResourceNotFoundException("Profile not found");
        }

        return skillMapper.findByProfileId(profileId);
    }

    public Skill updateSkill(Long id, UpdateSkillDTO request) {
        Skill existingSkill = skillMapper.findById(id);
        if (existingSkill == null) {
            throw new ResourceNotFoundException("Skill not found");
        }

        Skill update = new Skill();
        update.setId(id);
        update.setCategory(request.getCategory());
        update.setName(request.getName());

        skillMapper.updateById(update);
        return skillMapper.findById(id);
    }

    public void deleteSkill(Long id) {
        Skill existingSkill = skillMapper.findById(id);
        if (existingSkill == null) {
            throw new ResourceNotFoundException("Skill not found");
        }

        skillMapper.deleteById(id);
    }

}
