package com.mingzhe.resumetailor.mappers;

import com.mingzhe.resumetailor.entities.Profile;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface ProfileMapper {
    @Insert("""
    INSERT INTO profiles (
        user_id,
        full_name,
        phone,
        contact_email,
        linkedin_url,
        github_url,
        location,
        summary,
        created_at,
        updated_at
    ) VALUES (
        #{userId},
        #{fullName},
        #{phone},
        #{contactEmail},
        #{linkedinUrl},
        #{githubUrl},
        #{location},
        #{summary},
        NOW(),
        NOW()
    )
    """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Profile profile);
}
