package com.mingzhe.resumetailor.profiles;

import org.apache.ibatis.annotations.*;

@Mapper
public interface ProfileMapper {
    // Insert the new profile into the database, and set id of the profile to the auto-generated
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
    void insert(Profile profile);

    // Fetch the profile of a given user id from DB
    @Select("""
        SELECT
            id,
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
        FROM profiles
        WHERE user_id = #{userId}
        """)
    Profile findById(Long userId);

    // Update the profile of a given user id
    @Update("""
        <script>
        UPDATE profiles
        <set>
            <if test="fullName != null">full_name = #{fullName},</if>
            <if test="phone != null">phone = #{phone},</if>
            <if test="contactEmail != null">contact_email = #{contactEmail},</if>
            <if test="linkedinUrl != null">linkedin_url = #{linkedinUrl},</if>
            <if test="githubUrl != null">github_url = #{githubUrl},</if>
            <if test="location != null">location = #{location},</if>
            <if test="summary != null">summary = #{summary},</if>
        </set>
        WHERE user_id = #{userId}
        </script>
        """)
    void updateById(Profile profile);

    // Delete the profile of a given user id
    @Delete("""
        DELETE FROM profiles
        WHERE user_id = #{userId}
        """)
    void deleteById(Long userId);

}
