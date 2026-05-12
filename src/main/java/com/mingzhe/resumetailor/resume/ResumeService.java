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

    private static final long CACHE_TTL_MILLIS = 60_000;

    private final Map<String, Long> resumeGenerateTimeCache = new ConcurrentHashMap<>();

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

    public Resume fetchResumesByJobId(Long jobId) {
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
        log.info("Async resume generation started for jobId={}, thread={}",
                jobId, Thread.currentThread().getName());

        try {
            String result = generateResume(jobId);
            log.info("Async resume generation finished for jobId={}, result={}", jobId, result);
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

        Long lastGeneratedAt = resumeGenerateTimeCache.get(cacheKey);

        if (lastGeneratedAt != null &&
                System.currentTimeMillis() - lastGeneratedAt < CACHE_TTL_MILLIS) {
            log.info("Cache hit for jobId={}, profileId={}", jobId, profileId);
            log.info("Resume generation skipped because it was generated within 60 seconds");
            return "Skipped";
        }

        // build structured prompt for calling OpenAI api with the context
        String prompt = buildPrompt(context);
        System.out.println("===== PROMPT =====");
        System.out.println(prompt);

        // call OpenAi api up to three times to generate resume
        String aiResponse = callLlmWithRetry(prompt);

        // construct resume and save to database
        Resume resume = new Resume();
        resume.setJobId(jobId);
        resume.setGeneratedContent(aiResponse);
        resume.setMatchScore(null);
        resume.setPdfFilePath(null);

        Resume existingResume = resumeMapper.findByJobId(jobId);

        if (existingResume == null) {
            resumeMapper.insert(resume);
        } else {
            resume.setId(existingResume.getId());
            resumeMapper.updateById(resume);
        }

        // store the response in cache if first time generated
        resumeGenerateTimeCache.put(cacheKey, System.currentTimeMillis());

        return "Resume Generated";
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

        sb.append("You are an expert software engineering resume writer.\n");
        sb.append("Your task is to generate a tailored, ATS-friendly, one-page resume based on the candidate information and the target job description.\n");
        sb.append("Do not simply insert keywords. Instead, identify the most important requirements in the job description and emphasize the candidate's most relevant evidence.\n");
        sb.append("Focus on software engineering, backend engineering, AI application engineering, full-stack development, data workflow automation, or embedded systems depending on the job description.\n");
        sb.append("Internally analyze the target job description, but do not output the analysis.\n\n");

        sb.append("Target Job Information:\n");
        appendIfPresent(sb, "Title: ", job.getTitle());
        appendIfPresent(sb, "Company: ", job.getCompany());
        appendBlockIfPresent(sb, "Description:", job.getJobDescription());
        sb.append("\n");

        sb.append("Candidate Profile:\n");
        appendIfPresent(sb, "Full Name: ", profile.getFullName());
        appendIfPresent(sb, "Location: ", profile.getLocation());
        appendIfPresent(sb, "Email: ", profile.getContactEmail());
        appendIfPresent(sb, "Phone: ", profile.getPhone());
        appendIfPresent(sb, "LinkedIn: ", profile.getLinkedinUrl());
        appendIfPresent(sb, "GitHub: ", profile.getGithubUrl());
        appendIfPresent(sb, "Summary: ", profile.getSummary());
        sb.append("\n");

        if (experiences != null && !experiences.isEmpty()) {
            sb.append("Experiences:\n");
            for (Experience exp : experiences) {
                sb.append("- Experience\n");
                appendIfPresent(sb, "  Company: ", exp.getCompanyName());
                appendIfPresent(sb, "  Position: ", exp.getPosition());
                appendIfPresent(sb, "  Location: ", exp.getLocation());
                appendIfPresent(sb, "  Start Date: ", exp.getStartDate());
                appendIfPresent(sb, "  End Date: ", exp.getEndDate());
                appendIfPresent(sb, "  Description: ", exp.getDescription());
                sb.append("\n");
            }
        }

        if (educations != null && !educations.isEmpty()) {
            sb.append("Educations:\n");
            for (Education edu : educations) {
                sb.append("- Education\n");
                appendIfPresent(sb, "  School: ", edu.getSchoolName());
                appendIfPresent(sb, "  Degree: ", edu.getDegree());
                appendIfPresent(sb, "  Major: ", edu.getMajor());
                appendIfPresent(sb, "  GPA: ", edu.getGpa());
                appendIfPresent(sb, "  Relevant Coursework: ", edu.getRelevantCoursework());
                appendIfPresent(sb, "  Description: ", edu.getDescription());
                sb.append("\n");
            }
        }

        if (projects != null && !projects.isEmpty()) {
            sb.append("Projects:\n");
            for (Project project : projects) {
                sb.append("- Project\n");
                appendIfPresent(sb, "  Project Name: ", project.getProjectName());
                appendIfPresent(sb, "  Tech Stack: ", project.getTechStack());
                appendIfPresent(sb, "  Start Date: ", project.getStartDate());
                appendIfPresent(sb, "  End Date: ", project.getEndDate());
                appendIfPresent(sb, "  Description: ", project.getDescription());
                sb.append("\n");
            }
        }

        if (skills != null && !skills.isEmpty()) {
            sb.append("Skills:\n");
            for (Skill skill : skills) {
                if (hasText(skill.getCategory()) && hasText(skill.getName())) {
                    sb.append("- ")
                            .append(skill.getCategory())
                            .append(": ")
                            .append(skill.getName())
                            .append("\n");
                } else if (hasText(skill.getName())) {
                    sb.append("- ").append(skill.getName()).append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("Resume Tailoring Rules:\n");
        sb.append("- First analyze the target job description and identify the top required skills, technologies, responsibilities, and role type.\n");
        sb.append("- Tailor the resume by prioritizing the most relevant experience, projects, skills, and technologies.\n");
        sb.append("- Important job keywords should appear not only in the Skills section, but also in Experience or Projects when supported by the candidate data.\n");
        sb.append("- It is acceptable to omit less relevant projects, skills, or details to preserve one-page focus.\n");
        sb.append("- Exclude any fields with null, empty, or missing values from the resume content; never output the literal text 'null'.\n");
        sb.append("- Prefer technical credibility over aggressive keyword stuffing.\n");
        sb.append("- Do not fabricate technologies, metrics, scale, leadership, employment history, certifications, or achievements that are not supported by the provided candidate information.\n");
        sb.append("- Do not exaggerate the candidate's frontend, AI, cloud, leadership, or production-scale experience beyond the provided data.\n");
        sb.append("- Preserve the candidate's actual level of experience; do not make internship, academic, or personal project experience sound like senior-level production ownership.\n");
        sb.append("- When the candidate data is vague, use conservative wording and avoid converting vague notes into specific achievements unless explicitly supported.\n\n");

        sb.append("Role-Specific Emphasis Rules:\n");
        sb.append("- For backend or platform engineering roles, emphasize Java, Spring Boot, REST APIs, MyBatis, MySQL, authentication, validation, debugging, reliability, service architecture, and database-backed workflows.\n");
        sb.append("- For AI application or LLM integration roles, emphasize OpenAI API integration, prompt orchestration, structured data aggregation, response validation, JSON processing, and persistence of generated outputs.\n");
        sb.append("- For full-stack roles, emphasize React, Axios, API integration, frontend-backend communication, form workflows, and authentication state management only when supported by the candidate data.\n");
        sb.append("- For embedded, robotics, or hardware-related roles, emphasize sensor processing, PID control, embedded systems, PCB design, hardware-software integration, and iterative debugging.\n");
        sb.append("- For data, business systems, or operations roles, emphasize automation, data processing, Excel VBA, reporting workflows, documentation, reconciliation, and cross-functional coordination.\n\n");

        sb.append("Resume Style Reference:\n");
        sb.append("- Strong resume bullets are concise, technically specific, accomplishment-oriented, and focused on engineering impact.\n");
        sb.append("- Preferred writing style emphasizes technical implementation, system context, debugging, validation, reliability, automation, and workflow improvement.\n");
        sb.append("- Good bullets often combine: action verb + technical contribution + tools/systems + engineering or business purpose.\n");
        sb.append("- Maintain a professional new graduate software engineering tone instead of exaggerated startup-founder or senior-engineer language.\n");
        sb.append("- Avoid repetitive sentence structures or overusing identical action verbs across all bullets.\n\n");

        sb.append("Experience and Project Writing Rules:\n");
        sb.append("- Use accomplishment-oriented bullet points instead of paragraphs.\n");
        sb.append("- Begin each bullet with a strong action verb such as Developed, Designed, Implemented, Built, Optimized, Integrated, Automated, Diagnosed, Validated, Streamlined, Debugged, or Orchestrated.\n");
        sb.append("- Each bullet should follow this style when possible: Action Verb + Technical Work + Tools/Systems + Purpose or Impact.\n");
        sb.append("- Prefer concrete technical details over vague soft skills.\n");
        sb.append("- Use past tense for completed roles and projects.\n");
        sb.append("- Avoid weak phrases such as Responsible for, Worked on, Helped with, Participated in, or Familiar with.\n");
        sb.append("- If an experience or project description is brief, improve wording using only the provided facts, technologies, domain, and job-relevant context.\n");
        sb.append("- Do not invent unsupported tools, scale, metrics, responsibilities, or business outcomes.\n");
        sb.append("- When possible, transform rough descriptions into polished resume bullets by clarifying the action, technical implementation, system context, and business or engineering purpose.\n");
        sb.append("- Convert rough notes into professional bullet points using this pattern: Action Verb + Technical Contribution + Tools/Systems + Purpose or Impact.\n");
        sb.append("- Example: 'built backend API for category' may become 'Developed backend APIs for category management workflows, supporting structured data retrieval, filtering, and validation.' only if the related facts are supported by the candidate data.\n\n");

        sb.append("Skills Section Rules:\n");
        sb.append("- Reorder and group skills to match the target job description.\n");
        sb.append("- Only include skills supported by the candidate information.\n");
        sb.append("- For software engineering roles, prefer categories such as Languages, Backend, Frontend, AI / Integration, Tools, and Systems & Hardware.\n");
        sb.append("- Prioritize job-relevant skills near the beginning of each category.\n");
        sb.append("- Do not include unrelated or unsupported buzzwords.\n\n");

        sb.append("Required Resume Structure:\n");
        sb.append("FULL NAME\n");
        sb.append("Location | Email | Phone | LinkedIn | GitHub\n\n");
        sb.append("EDUCATION\n");
        sb.append("School Name — Degree, Major\n");
        sb.append("Location | Dates | GPA if available\n");
        sb.append("- Relevant coursework or academic details only if useful for the target role\n\n");
        sb.append("EXPERIENCE\n");
        sb.append("Company Name — Position\n");
        sb.append("Location | Dates\n");
        sb.append("- Bullet point\n");
        sb.append("- Bullet point\n\n");
        sb.append("PROJECTS\n");
        sb.append("Project Name — Tech Stack\n");
        sb.append("Dates if available\n");
        sb.append("- Bullet point\n");
        sb.append("- Bullet point\n\n");
        sb.append("SKILLS\n");
        sb.append("Languages: ...\n");
        sb.append("Backend: ...\n");
        sb.append("Frontend: ...\n");
        sb.append("AI / Integration: ...\n");
        sb.append("Tools: ...\n");
        sb.append("Systems & Hardware: ...\n\n");

        sb.append("Length and Formatting Rules:\n");
        sb.append("- Target total resume length: 400-500 words.\n");
        sb.append("- Do not exceed 550 words.\n");
        sb.append("- Keep the resume suitable for a one-page new graduate software engineering resume.\n");
        sb.append("- Use clear section headings: EDUCATION, EXPERIENCE, PROJECTS, SKILLS.\n");
        sb.append("- Use reverse chronological order within Experience and Education.\n");
        sb.append("- Use 2-5 bullet points per major experience or project.\n");
        sb.append("- Use fewer bullets for less relevant experiences or projects.\n");
        sb.append("- Keep most bullet points around 20-35 words.\n");
        sb.append("- Do not include references, personal demographic information, or a long professional summary.\n");
        sb.append("- Do not use markdown tables.\n");
        sb.append("- Do not include placeholder text.\n\n");

        sb.append("Output Requirements:\n");
        sb.append("- Output only the final resume content.\n");
        sb.append("- Do not explain your reasoning.\n");
        sb.append("- Do not output the job description analysis.\n");
        sb.append("- Do not include markdown tables.\n");
        sb.append("- Do not include placeholder text.\n");
        sb.append("- Preserve accurate candidate contact information.\n");

        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, String label, Object value) {
        if (value != null && hasText(String.valueOf(value))) {
            sb.append(label).append(value).append("\n");
        }
    }

    private void appendBlockIfPresent(StringBuilder sb, String label, Object value) {
        if (value != null && hasText(String.valueOf(value))) {
            sb.append(label).append("\n").append(value).append("\n");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim());
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
