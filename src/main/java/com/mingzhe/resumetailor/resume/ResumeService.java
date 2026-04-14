package com.mingzhe.resumetailor.resume;

import com.mingzhe.resumetailor.OpenAiService;
import com.mingzhe.resumetailor.education.Education;
import com.mingzhe.resumetailor.education.EducationMapper;
import com.mingzhe.resumetailor.exceptions.BadRequestException;
import com.mingzhe.resumetailor.exceptions.ResourceNotFoundException;
import com.mingzhe.resumetailor.experience.Experience;
import com.mingzhe.resumetailor.experience.ExperienceMapper;
import com.mingzhe.resumetailor.job.Job;
import com.mingzhe.resumetailor.job.JobMapper;
import com.mingzhe.resumetailor.profile.Profile;
import com.mingzhe.resumetailor.profile.ProfileMapper;
import com.mingzhe.resumetailor.project.Project;
import com.mingzhe.resumetailor.project.ProjectMapper;
import com.mingzhe.resumetailor.skill.Skill;
import com.mingzhe.resumetailor.skill.SkillMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Service
public class ResumeService {

    private final JobMapper jobMapper;
    private final ProfileMapper profileMapper;
    private final ExperienceMapper experienceMapper;
    private final EducationMapper educationMapper;
    private final ProjectMapper projectMapper;
    private final SkillMapper skillMapper;
    private final ResumeMapper resumeMapper;

    private final OpenAiService openAiService;

    public ResumeService(JobMapper jobMapper, ProfileMapper profileMapper, ExperienceMapper experienceMapper, EducationMapper educationMapper, ProjectMapper projectMapper, SkillMapper skillMapper, ResumeMapper resumeMapper, OpenAiService openAiService) {
        this.jobMapper = jobMapper;
        this.profileMapper = profileMapper;
        this.experienceMapper = experienceMapper;
        this.educationMapper = educationMapper;
        this.projectMapper = projectMapper;
        this.skillMapper = skillMapper;
        this.resumeMapper = resumeMapper;
        this.openAiService = openAiService;
    }

    public Resume createResume(CreateResumeDTO request) {
        Job job = jobMapper.findById(request.getJobId());
        if (job == null) {
            throw new ResourceNotFoundException("Job not found");
        }

        if (request.getMatchScore() != null && !isValidMatchScore(request.getMatchScore())) {
            throw new BadRequestException("matchScore must be between 0 and 100");
        }

        Resume resume = new Resume();
        resume.setJobId(request.getJobId());
        resume.setMatchScore(request.getMatchScore());
        resume.setGeneratedContent(request.getGeneratedContent());
        resume.setPdfFilePath(request.getPdfFilePath());

        resumeMapper.insert(resume);
        return resume;
    }

    public List<Resume> fetchResumesByJobId(Long jobId) {
        Job job = jobMapper.findById(jobId);
        if (job == null) {
            throw new ResourceNotFoundException("Job not found");
        }

        return resumeMapper.findByJobId(jobId);
    }

    public Resume updateResume(Long id, UpdateResumeDTO request) {
        Resume existingResume = resumeMapper.findById(id);
        if (existingResume == null) {
            throw new ResourceNotFoundException("Resume not found");
        }

        if (request.getMatchScore() != null && !isValidMatchScore(request.getMatchScore())) {
            throw new BadRequestException("matchScore must be between 0 and 100");
        }

        Resume update = new Resume();
        update.setId(id);
        update.setMatchScore(request.getMatchScore());
        update.setGeneratedContent(request.getGeneratedContent());
        update.setPdfFilePath(request.getPdfFilePath());

        resumeMapper.updateById(update);
        return resumeMapper.findById(id);
    }

    public void deleteResume(Long id) {
        Resume existingResume = resumeMapper.findById(id);
        if (existingResume == null) {
            throw new ResourceNotFoundException("Resume not found");
        }

        resumeMapper.deleteById(id);
    }

    private boolean isValidMatchScore(Integer matchScore) {
        return matchScore != null && matchScore >= 0 && matchScore <= 100;
    }

    public String generateResume(Long jobId) {
        Job job = jobMapper.findById(jobId);
        Profile profile = profileMapper.findByUserId(job.getUserId());
        List<Experience> experiences = experienceMapper.findByProfileId(profile.getId());
        List<Education> educations = educationMapper.findByProfileId(profile.getId());
        List<Project> projects = projectMapper.findByProfileId(profile.getId());
        List<Skill> skills = skillMapper.findByProfileId(profile.getId());

        String prompt = buildPrompt(job, profile, experiences, educations, projects, skills);

        System.out.println("===== PROMPT =====");
        System.out.println(prompt);

        String aiResponse = openAiService.generate(prompt);

        System.out.println("===== RAW AI RESPONSE =====");
        System.out.println(aiResponse);

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode root = objectMapper.readTree(aiResponse);

        String content = root.path("choices")
                .get(0)
                .path("message")
                .path("content").asString();

        System.out.println("===== Text Obtained From Json =====");
        System.out.println(content);

        return content;
    }

    private String buildPrompt(Job job,
                               Profile profile,
                               List<Experience> experiences,
                               List<Education> educations,
                               List<Project> projects,
                               List<Skill> skills) {

        StringBuilder sb = new StringBuilder();

        sb.append("You are a professional resume writer.\n");
        sb.append("Generate a tailored resume based on the candidate information and job description.\n");
        sb.append("Focus on the most relevant qualifications.\n\n");

        sb.append("Job Information:\n");
        sb.append("Title: ").append(job.getTitle()).append("\n");
        sb.append("Company: ").append(job.getCompany()).append("\n");
        sb.append("Description: ").append(job.getJobDescription()).append("\n\n");

        sb.append("Candidate Profile:\n");
        sb.append("Full Name: ").append(profile.getFullName()).append("\n");
        sb.append("Location: ").append(profile.getLocation()).append("\n");
        sb.append("Email: ").append(profile.getContactEmail()).append("\n");
        sb.append("Phone: ").append(profile.getPhone()).append("\n");
        sb.append("LinkedIn: ").append(profile.getLinkedinUrl()).append("\n");
        sb.append("GitHub: ").append(profile.getGithubUrl()).append("\n");
        sb.append("Summary: ").append(profile.getSummary()).append("\n\n");

        sb.append("Experiences:\n");
        for (Experience exp : experiences) {
            sb.append("- Company: ").append(exp.getCompanyName()).append("\n");
            sb.append("  Position: ").append(exp.getPosition()).append("\n");
            sb.append("  Location: ").append(exp.getLocation()).append("\n");
            sb.append("  Start Date: ").append(exp.getStartDate()).append("\n");
            sb.append("  End Date: ").append(exp.getEndDate()).append("\n");
            sb.append("  Description: ").append(exp.getDescription()).append("\n\n");
        }

        sb.append("Educations:\n");
        for (Education edu : educations) {
            sb.append("- School: ").append(edu.getSchoolName()).append("\n");
            sb.append("  Degree: ").append(edu.getDegree()).append("\n");
            sb.append("  Major: ").append(edu.getMajor()).append("\n");
            sb.append("  GPA: ").append(edu.getGpa()).append("\n");
            sb.append("  Relevant Coursework: ").append(edu.getRelevantCoursework()).append("\n");
            sb.append("  Description: ").append(edu.getDescription()).append("\n\n");
        }

        sb.append("Projects:\n");
        for (Project project : projects) {
            sb.append("- Project Name: ").append(project.getProjectName()).append("\n");
            sb.append("  Tech Stack: ").append(project.getTechStack()).append("\n");
            sb.append("  Start Date: ").append(project.getStartDate()).append("\n");
            sb.append("  End Date: ").append(project.getEndDate()).append("\n");
            sb.append("  Description: ").append(project.getDescription()).append("\n\n");
        }

        sb.append("Skills:\n");
        for (Skill skill : skills) {
            sb.append("- ").append(skill.getCategory()).append(": ").append(skill.getName()).append("\n");
        }

        sb.append("\nInstructions:\n");
        sb.append("Generate a concise, professional resume tailored to the job description.\n");
        sb.append("Highlight the most relevant experience, projects, and skills.\n");
        sb.append("Use strong action verbs and resume-style bullet points.\n");

        return sb.toString();
    }

}
