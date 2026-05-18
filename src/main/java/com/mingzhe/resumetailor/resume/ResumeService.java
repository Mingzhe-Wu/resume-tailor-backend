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

    private static final long CACHE_TTL_MILLIS = 300_000;

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
        // System.out.println("===== PROMPT =====");
        // System.out.println(prompt);

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

        // =========================================================================
        // Core System Prompt
        // =========================================================================

        sb.append("You are a senior technical resume writer specializing in software engineering resumes for highly competitive technology companies.\n");
        sb.append("Generate a concise, ATS-friendly, technically credible resume tailored to the target job description and the candidate's structured background.\n");
        sb.append("The resume should feel intentionally curated for the target role while maintaining believable engineering scope for a strong new graduate software engineering candidate.\n");
        sb.append("Output only the final resume text.\n\n");

        // =========================================================================
        // Identity & Credibility Rules
        // =========================================================================

        sb.append("Identity and Credibility Rules:\n");
        sb.append("- Maintain a coherent engineering identity throughout the resume based on the target role.\n");
        sb.append("- Keep technical depth realistic for a strong new graduate software engineering candidate.\n");
        sb.append("- Ground technical claims in concrete implementation details rather than abstract buzzwords.\n");
        sb.append("- Avoid vague or exaggerated terminology such as advanced AI infrastructure, distributed AI systems, hallucination reduction, AI governance, or large-scale production architecture unless directly supported.\n");
        sb.append("- Avoid making projects sound like classroom assignments.\n");
        sb.append("- Resume content should sound believable to experienced software engineers conducting technical interviews.\n\n");

        // =========================================================================
        // Content Prioritization Rules
        // =========================================================================

        sb.append("Content Prioritization Rules:\n");
        sb.append("- Prioritize experiences and projects that best match the target role.\n");
        sb.append("- Allocate more space to highly relevant engineering work and compress low-relevance content aggressively.\n");
        sb.append("- Prioritize implementation, APIs, workflows, debugging, automation, validation, testing, persistence, and integration work over conceptual descriptions.\n");
        sb.append("- AI-related claims should be tied to concrete engineering implementation details.\n");
        sb.append("- Reorder skills based on target role relevance.\n");
        sb.append("- Avoid listing routine engineering practices such as validation logic, logging, exception handling, or Git workflows as isolated standalone skill categories.\n\n");

        // =========================================================================
        // Writing Style Rules
        // =========================================================================

        sb.append("Writing Style Rules:\n");
        sb.append("- Write concise, technically dense, accomplishment-oriented bullet points.\n");
        sb.append("- Keep most bullets around 20-35 words.\n");
        sb.append("- Vary sentence structure and density naturally across bullets.\n");
        sb.append("- Some bullets may emphasize implementation while others emphasize debugging, integration, testing, or workflow improvements.\n");
        sb.append("- Avoid making every bullet equally dense or mechanically optimized.\n");
        sb.append("- Prefer engineering-oriented language over business-oriented summaries.\n");
        sb.append("- Avoid weak phrases such as Responsible for, Worked on, Helped with, Assisted with, or Participated in.\n");
        sb.append("- Use strong action verbs such as Developed, Built, Designed, Implemented, Integrated, Automated, Diagnosed, Debugged, Optimized, Refined, and Streamlined.\n");
        sb.append("- Use past tense for completed work.\n\n");

        sb.append("Example Writing Transformation:\n");
        sb.append("Weak: Worked on backend APIs using Spring Boot and handled errors.\n");
        sb.append("Strong: Developed Spring Boot REST APIs with centralized exception handling and validation logic to improve reliability across database-backed workflows.\n\n");

        // =========================================================================
        // Dynamic Role Detection
        // =========================================================================

        String jdText = (
                (job.getTitle() == null ? "" : job.getTitle()) + " " +
                        (job.getJobDescription() == null ? "" : job.getJobDescription())
        ).toLowerCase();

        if (
                jdText.contains("robotics") ||
                        jdText.contains("embedded") ||
                        jdText.contains("autonomous") ||
                        jdText.contains("vehicle") ||
                        jdText.contains("sensor") ||
                        jdText.contains("hardware") ||
                        jdText.contains("linux") ||
                        jdText.contains("c++")
        ) {

            sb.append("Detected Role Focus:\n");
            sb.append("This appears to be a systems, embedded, robotics, autonomous vehicle, or hardware-software engineering role.\n");
            sb.append("Prioritize C/C++, Linux, networking, embedded systems, sensor processing, hardware-software integration, low-level debugging, runtime behavior analysis, testing, and performance refinement.\n");
            sb.append("Emphasize embedded systems projects and low-level engineering work more than generic web applications.\n\n");
        }

        if (
                jdText.contains("llm") ||
                        jdText.contains("openai") ||
                        jdText.contains("generative ai") ||
                        jdText.contains("prompt") ||
                        jdText.contains("ai application") ||
                        jdText.contains("machine learning") ||
                        jdText.contains("copilot") ||
                        jdText.contains("inference") ||
                        jdText.contains("agent") ||
                        jdText.contains("orchestration") ||
                        jdText.contains("content safety") ||
                        jdText.contains("evaluation") ||
                        jdText.contains("moderation") ||
                        jdText.contains("multimodal")
        ) {

            sb.append("Detected Role Focus:\n");
            sb.append("This appears to be an AI application, LLM integration, AI platform, or machine learning-adjacent software engineering role.\n");
            sb.append("Prioritize AI API integration, structured prompt construction, backend workflows, structured data aggregation, JSON processing, persistence workflows, automation, debugging, and reliable service orchestration.\n");
            sb.append("Avoid overstating AI research, ML infrastructure, or advanced distributed AI systems unless explicitly supported.\n");
            sb.append("Emphasize AI-related backend projects and API integration experience.\n\n");
        }

        if (
                jdText.contains("backend") ||
                        jdText.contains("api") ||
                        jdText.contains("spring") ||
                        jdText.contains("java") ||
                        jdText.contains("database") ||
                        jdText.contains("microservice")
        ) {

            sb.append("Detected Role Focus:\n");
            sb.append("This appears to be a backend, platform, or service-oriented software engineering role.\n");
            sb.append("Prioritize Java, Spring Boot, REST APIs, MyBatis, MySQL, validation, authentication, logging, reliability, database-backed workflows, and production debugging.\n");
            sb.append("Emphasize backend internship experience and backend engineering projects.\n\n");
        }

        // =========================================================================
        // Required Output Structure
        // =========================================================================

        sb.append("Required Resume Structure:\n");
        sb.append("FULL NAME\n");
        sb.append("Location | Email | Phone | LinkedIn | GitHub\n\n");

        sb.append("EDUCATION\n");
        sb.append("School Name — Degree, Major\n");
        sb.append("Location | Dates | GPA if available\n");
        sb.append("- Relevant coursework only if useful for the target role\n\n");

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
        sb.append("Category: skills...\n\n");

        // =========================================================================
        // Target Job Data
        // =========================================================================

        sb.append("<TargetJob>\n");
        appendIfPresent(sb, "Title: ", job.getTitle());
        appendIfPresent(sb, "Company: ", job.getCompany());
        appendBlockIfPresent(sb, "Description:\n", job.getJobDescription());
        sb.append("</TargetJob>\n\n");

        // =========================================================================
        // Candidate Profile
        // =========================================================================

        sb.append("<CandidateProfile>\n");
        appendIfPresent(sb, "Full Name: ", profile.getFullName());
        appendIfPresent(sb, "Location: ", profile.getLocation());
        appendIfPresent(sb, "Email: ", profile.getContactEmail());
        appendIfPresent(sb, "Phone: ", profile.getPhone());
        appendIfPresent(sb, "LinkedIn: ", profile.getLinkedinUrl());
        appendIfPresent(sb, "GitHub: ", profile.getGithubUrl());
        appendIfPresent(sb, "Summary: ", profile.getSummary());
        sb.append("</CandidateProfile>\n\n");

        // =========================================================================
        // Prior Resume
        // =========================================================================

        if (hasText(profile.getPriorResume())) {
            sb.append("<PriorResumeReference>\n");
            sb.append("Use this prior resume as reference material for tone, structure, and existing achievements.\n");
            sb.append("Improve, compress, reorder, expand, or rewrite content as needed for stronger alignment with the target role.\n");
            sb.append("Do not blindly copy weak or repetitive bullets.\n\n");
            sb.append(profile.getPriorResume()).append("\n");
            sb.append("</PriorResumeReference>\n\n");
        }

        // =========================================================================
        // Experiences
        // =========================================================================

        if (experiences != null && !experiences.isEmpty()) {

            sb.append("<Experiences>\n");

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

            sb.append("</Experiences>\n\n");
        }

        // =========================================================================
        // Educations
        // =========================================================================

        if (educations != null && !educations.isEmpty()) {

            sb.append("<Educations>\n");

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

            sb.append("</Educations>\n\n");
        }

        // =========================================================================
        // Projects
        // =========================================================================

        if (projects != null && !projects.isEmpty()) {

            sb.append("<Projects>\n");

            for (Project project : projects) {

                sb.append("- Project\n");

                appendIfPresent(sb, "  Project Name: ", project.getProjectName());
                appendIfPresent(sb, "  Tech Stack: ", project.getTechStack());
                appendIfPresent(sb, "  Start Date: ", project.getStartDate());
                appendIfPresent(sb, "  End Date: ", project.getEndDate());
                appendIfPresent(sb, "  Description: ", project.getDescription());

                sb.append("\n");
            }

            sb.append("</Projects>\n\n");
        }

        // =========================================================================
        // Skills
        // =========================================================================

        if (skills != null && !skills.isEmpty()) {

            sb.append("<Skills>\n");

            for (Skill skill : skills) {

                if (hasText(skill.getCategory()) && hasText(skill.getName())) {

                    sb.append("- ")
                            .append(skill.getCategory())
                            .append(": ")
                            .append(skill.getName())
                            .append("\n");

                } else if (hasText(skill.getName())) {

                    sb.append("- ")
                            .append(skill.getName())
                            .append("\n");
                }
            }

            sb.append("</Skills>\n\n");
        }

        // =========================================================================
        // Final Constraints
        // =========================================================================

        sb.append("Final Constraints:\n");
        sb.append("- Output only the final resume.\n");
        sb.append("- Do not include analysis, markdown tables, explanations, or commentary.\n");
        sb.append("- Exclude null or empty fields.\n");
        sb.append("- Keep the resume concise enough for a realistic one-page software engineering resume.\n");

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
