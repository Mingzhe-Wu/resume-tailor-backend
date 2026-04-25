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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Business logic for validating and managing Resume records.
 */
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

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    private final Map<String, String> resumeCache = new ConcurrentHashMap<>();

    private String buildCacheKey(Long jobId, Long profileId) {
        return jobId + "_" + profileId;
    }

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

    @Async
    public void generateResumeAsync(Long jobId) {
        log.info("Async resume generation started for jobId={}, thread={}", jobId, Thread.currentThread().getName());

        try {
            generateResume(jobId);
            log.info("Async resume generation finished for jobId={}", jobId);
        } catch (Exception e) {
            log.error("Async resume generation failed for jobId={}: {}", jobId, e.getMessage(), e);
        }
    }

    public String generateResume(Long jobId) {
        // fetch resume context with given job id
        ResumeGenerationContext context = fetchResumeContext(jobId);

        // build cache key to check if resume already exists in the memory cache
        Long profileId = context.getProfile().getId();
        String cacheKey = buildCacheKey(jobId, profileId);

        String cachedResume = resumeCache.get(cacheKey);
        if (cachedResume != null) {
            log.info("Cache hit for jobId={}, profileId={}", jobId, profileId);
            return cachedResume;
        }

        // build structured prompt for calling OpenAI api with the context
        String prompt = buildPrompt(context);
        System.out.println("===== PROMPT =====");
        System.out.println(prompt);

        // call OpenAi api up to three times to generate resume
        String aiResponse = callLlmWithRetry(prompt);

        // store the response in cache if first time generated
        resumeCache.put(cacheKey, aiResponse);

        // construct resume and save to database
        Resume resume = new Resume();
        resume.setJobId(jobId);
        resume.setGeneratedContent(aiResponse);
        resume.setMatchScore(null);
        resume.setPdfFilePath(null);

        resumeMapper.insert(resume);

        return aiResponse;
    }

    private String callLlmWithRetry(String prompt) {
        int maxAttempts = 3;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("LLM attempt {} started", attempt);

                // call OpenAi api to generate a response
                String aiResponse = openAiService.generate(prompt);

                // validate the response
                validateGeneratedResume(aiResponse);

                log.info("LLM attempt {} succeeded", attempt);
                return aiResponse;

            } catch (Exception e) {
                log.warn("LLM attempt {} failed: {}", attempt, e.getMessage());

                if (attempt == maxAttempts) {
                    throw new RuntimeException("Resume generation failed after " + maxAttempts + " attempts", e);
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }

        throw new RuntimeException("Unexpected retry failure");
    }

    private ResumeGenerationContext fetchResumeContext(Long jobId) {
        Job job = jobMapper.findById(jobId);
        if (job == null) {
            throw new ResourceNotFoundException("Job not found");
        }

        Profile profile = profileMapper.findByUserId(job.getUserId());
        if (profile == null) {
            throw new ResourceNotFoundException("Profile not found for user id: " + job.getUserId());
        }

        ResumeGenerationContext context = new ResumeGenerationContext();
        context.setJob(job);
        context.setProfile(profile);
        context.setExperiences(experienceMapper.findByProfileId(profile.getId()));
        context.setEducations(educationMapper.findByProfileId(profile.getId()));
        context.setProjects(projectMapper.findByProfileId(profile.getId()));
        context.setSkills(skillMapper.findByProfileId(profile.getId()));

        return context;
    }

    private String buildPrompt(ResumeGenerationContext context) {
        Job job = context.getJob();
        Profile profile = context.getProfile();
        List<Experience> experiences = context.getExperiences();
        List<Education> educations = context.getEducations();
        List<Project> projects = context.getProjects();
        List<Skill> skills = context.getSkills();

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

    public void validateGeneratedResume(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Generated resume is empty");
        }

        if (content.length() < 100) {
            throw new IllegalArgumentException("Generated resume is too short");
        }

        String lower = content.toLowerCase();

        if (lower.contains("i'm sorry") || lower.contains("cannot help with")) {
            throw new IllegalArgumentException("Generated resume contains failure-like text");
        }

        boolean hasExperience = lower.contains("experience");
        boolean hasProject = lower.contains("project");
        boolean hasSkills = lower.contains("skills");

        if ((!hasExperience && !hasProject) || !hasSkills) {
            throw new IllegalArgumentException("Generated resume is missing expected sections");
        }
    }

}
