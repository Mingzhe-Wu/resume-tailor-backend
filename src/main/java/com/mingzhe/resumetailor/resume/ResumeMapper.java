package com.mingzhe.resumetailor.resume;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * MyBatis mapper for Resume database operations.
 */
@Mapper
public interface ResumeMapper {

    @Insert("""
        INSERT INTO resume_versions (
            job_id,
            match_score,
            generated_content,
            pdf_file_path
        ) VALUES (
            #{jobId},
            #{matchScore},
            #{generatedContent},
            #{pdfFilePath}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Resume resume);

    @Select("""
        SELECT
            id,
            job_id,
            match_score,
            generated_content,
            pdf_file_path,
            created_at,
            updated_at
        FROM resume_versions
        WHERE id = #{id}
        """)
    Resume findById(Long id);

    @Select("""
        SELECT
            id,
            job_id,
            match_score,
            generated_content,
            pdf_file_path,
            created_at,
            updated_at
        FROM resume_versions
        WHERE job_id = #{jobId}
        ORDER BY created_at DESC, id DESC
        """)
    List<Resume> findByJobId(Long jobId);

    @Update("""
        <script>
        UPDATE resume_versions
        <set>
            <if test="matchScore != null">match_score = #{matchScore},</if>
            <if test="generatedContent != null">generated_content = #{generatedContent},</if>
            <if test="pdfFilePath != null">pdf_file_path = #{pdfFilePath},</if>
            updated_at = NOW()
        </set>
        WHERE id = #{id}
        </script>
        """)
    int updateById(Resume resume);

    @Delete("""
        DELETE FROM resume_versions
        WHERE id = #{id}
        """)
    int deleteById(Long id);

}
