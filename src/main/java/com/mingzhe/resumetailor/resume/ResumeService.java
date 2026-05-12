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

        // =========================
        // Core System Prompt
        // =========================

        sb.append("You are an elite technical resume writer, senior software engineering recruiter, and resume optimization engine.\n");
        sb.append("Your task is to generate a highly tailored, ATS-friendly, one-page software engineering resume based on the target job description and the candidate's structured background.\n");
        sb.append("The resume must feel intentionally optimized for this exact role, not like a generic summary of the candidate's full history.\n");
        sb.append("Do not output your analysis. Output only the final resume.\n\n");

        sb.append("Primary Objective:\n");
        sb.append("- Maximize alignment between the candidate's background and the target job description.\n");
        sb.append("- Create a strong first impression within 10-15 seconds of recruiter scanning.\n");
        sb.append("- Present the candidate as a strong new graduate software engineering candidate with practical engineering maturity.\n");
        sb.append("- Emphasize technical implementation, debugging, integration, validation, testing, automation, reliability, and system-level engineering thinking.\n");
        sb.append("- Avoid generic student-project language, weak task descriptions, excessive business-domain detail, and AI-generated keyword stuffing.\n\n");

        // =========================
        // Job Information
        // =========================

        sb.append("Target Job Information:\n");
        appendIfPresent(sb, "Title: ", job.getTitle());
        appendIfPresent(sb, "Company: ", job.getCompany());
        appendBlockIfPresent(sb, "Description:", job.getJobDescription());
        sb.append("\n");

        // =========================
        // Candidate Profile
        // =========================

        sb.append("Candidate Profile:\n");
        appendIfPresent(sb, "Full Name: ", profile.getFullName());
        appendIfPresent(sb, "Location: ", profile.getLocation());
        appendIfPresent(sb, "Email: ", profile.getContactEmail());
        appendIfPresent(sb, "Phone: ", profile.getPhone());
        appendIfPresent(sb, "LinkedIn: ", profile.getLinkedinUrl());
        appendIfPresent(sb, "GitHub: ", profile.getGithubUrl());
        appendIfPresent(sb, "Summary: ", profile.getSummary());
        sb.append("\n");

        // =========================
        // Prior Resume
        // =========================

        if (hasText(profile.getPriorResume())) {
            sb.append("Prior Resume Reference:\n");
            sb.append("The candidate has provided an existing resume. Use it as a baseline reference for structure, tone, existing achievements, and previously selected content.\n");
            sb.append("Revise it according to the target job description and the structured candidate data.\n");
            sb.append("You may add, remove, reorder, compress, expand, or rewrite content to improve role alignment.\n");
            sb.append("Do not blindly copy weak, outdated, repetitive, or low-relevance bullets.\n");
            sb.append("Do not invent facts beyond the prior resume and structured candidate data.\n");
            sb.append("Existing Resume Content:\n");
            sb.append(profile.getPriorResume()).append("\n\n");
        }

        // =========================
        // Experiences
        // =========================

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

        // =========================
        // Educations
        // =========================

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

        // =========================
        // Projects
        // =========================

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

        // =========================
        // Skills
        // =========================

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
                    sb.append("- ")
                            .append(skill.getName())
                            .append("\n");
                }
            }

            sb.append("\n");
        }

        // =========================
        // Dynamic Role Detection
        // =========================

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
            sb.append("This appears to be a systems, embedded, robotics, autonomous vehicle, hardware-software, or platform engineering role.\n");
            sb.append("Prioritize C/C++, Linux, networking, embedded systems, sensor processing, hardware-software integration, real-time debugging, runtime behavior analysis, testing, reliability, and performance refinement.\n");
            sb.append("For this role type, embedded systems projects, robotics projects, networking experience, operating systems knowledge, and low-level debugging experience should be emphasized more than generic web application or AI application work.\n\n");
        }

        if (
                jdText.contains("llm") ||
                        jdText.contains("openai") ||
                        jdText.contains("generative ai") ||
                        jdText.contains("prompt") ||
                        jdText.contains("ai application") ||
                        jdText.contains("machine learning")
        ) {

            sb.append("Detected Role Focus:\n");
            sb.append("This appears to be an AI application, LLM integration, or machine learning-adjacent software engineering role.\n");
            sb.append("Prioritize AI API integration, prompt orchestration, structured data aggregation, JSON processing, response validation, persistence workflows, automation, and reliable backend orchestration.\n");
            sb.append("For this role type, AI-related backend projects and API integration experience should be emphasized.\n\n");
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
            sb.append("Prioritize Java, Spring Boot, REST APIs, MyBatis, MySQL, service-layer design, validation, authentication, exception handling, logging, reliability, database-backed workflows, and production debugging.\n");
            sb.append("For this role type, backend internship experience and backend engineering projects should be emphasized.\n\n");
        }

        // =========================
        // Resume Generation Rules
        // =========================

        sb.append("Role Analysis and Tailoring Rules:\n");
        sb.append("- Internally analyze the target job description before writing the resume.\n");
        sb.append("- Identify the role type, core technologies, strongest hiring signals, required engineering workflows, and preferred experience themes.\n");
        sb.append("- Prioritize candidate evidence that best matches the target role.\n");
        sb.append("- Allocate more space to highly relevant experiences and projects.\n");
        sb.append("- Compress or omit low-relevance content aggressively.\n");
        sb.append("- Strong projects may receive more bullets than weaker internships if they are more relevant.\n");
        sb.append("- The final resume should feel curated for the target role rather than exhaustive.\n\n");

        sb.append("Engineering Identity Rules:\n");
        sb.append("- Maintain a coherent engineering identity throughout the resume.\n");
        sb.append("- The candidate should primarily appear as a software engineering candidate, not a scattered mix of unrelated experiences.\n");
        sb.append("- Use the target role to determine whether the dominant identity should be backend engineer, systems engineer, AI engineer, platform engineer, embedded engineer, or full-stack engineer.\n");
        sb.append("- Secondary strengths should support the dominant identity instead of distracting from it.\n");
        sb.append("- Avoid making the candidate sound passive, overly academic, or task-oriented.\n\n");

        sb.append("Bullet Writing Rules:\n");
        sb.append("- Write concise, technically dense, accomplishment-oriented bullet points.\n");
        sb.append("- Every bullet should communicate meaningful technical implementation.\n");
        sb.append("- Strong bullets usually include implementation, debugging, validation, integration, testing, automation, reliability, performance optimization, or workflow improvement.\n");
        sb.append("- Prefer engineering-oriented language over business-oriented summaries.\n");
        sb.append("- Avoid generic CRUD wording when stronger systems-oriented framing is possible.\n");
        sb.append("- Avoid weak phrases such as Responsible for, Worked on, Helped with, Assisted with, Participated in, or Familiar with.\n");
        sb.append("- Use strong action verbs such as Developed, Built, Designed, Implemented, Integrated, Automated, Diagnosed, Debugged, Validated, Optimized, Refined, Streamlined, and Orchestrated.\n");
        sb.append("- Avoid excessive repetition of the same action verb.\n");
        sb.append("- Use past tense for completed work.\n");
        sb.append("- Keep most bullets around 20-35 words.\n\n");

        sb.append("Allowed Enhancement Rules:\n");
        sb.append("- You may professionally polish rough notes into strong engineering resume bullets.\n");
        sb.append("- You may infer realistic debugging, testing, validation, integration, workflow automation, request handling, data processing, or reliability context when reasonably supported.\n");
        sb.append("- You may rewrite simple notes into stronger engineering-oriented resume language.\n");
        sb.append("- Do not fabricate unsupported technologies, fake leadership, fake ownership, fake scale, fake distributed systems, fake cloud infrastructure, fake metrics, or fake achievements.\n");
        sb.append("- Preserve realistic new graduate scope and technical depth.\n\n");

        sb.append("Anti-Student-Resume Rules:\n");
        sb.append("- Do not make projects sound like classroom assignments.\n");
        sb.append("- Avoid excessive academic explanation.\n");
        sb.append("- Frame projects as engineering systems involving implementation, debugging, integration, testing, iteration, and design decisions.\n");
        sb.append("- The candidate should sound industry-oriented and technically practical.\n\n");

        sb.append("Skills Rules:\n");
        sb.append("- Reorder skills based on target role relevance.\n");
        sb.append("- Prioritize the most relevant technologies near the beginning of each category.\n");
        sb.append("- Prefer concrete technologies, tools, frameworks, systems, and technical domains.\n");
        sb.append("- Avoid abstract soft skills.\n");
        sb.append("- Omit irrelevant skill categories when they weaken the resume focus.\n\n");

        sb.append("Formatting Rules:\n");
        sb.append("- Keep realistic one-page resume density.\n");
        sb.append("- Recommended total length: 400-550 words.\n");
        sb.append("- Use reverse chronological order.\n");
        sb.append("- Use clean section headings: EDUCATION, EXPERIENCE, PROJECTS, SKILLS.\n");
        sb.append("- Exclude null or empty fields.\n");
        sb.append("- Never output the literal text 'null'.\n");
        sb.append("- Do not include markdown tables, references, objective statements, or placeholder text.\n\n");

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

        sb.append("Final Quality Check Before Output:\n");
        sb.append("- Does the resume clearly match the target job description?\n");
        sb.append("- Is the dominant engineering identity clear and role-appropriate?\n");
        sb.append("- Are the strongest technical signals placed early?\n");
        sb.append("- Are low-relevance details compressed or omitted?\n");
        sb.append("- Does every bullet contain technical value?\n");
        sb.append("- Does the resume sound like a strong new graduate engineer rather than a generic student?\n");
        sb.append("- Are skills concrete, relevant, and properly ordered?\n");
        sb.append("- Is the resume concise enough for one page?\n");
        sb.append("- If a prior resume was provided, did the final resume improve and tailor it rather than blindly copying it?\n\n");

        sb.append("Output Requirements:\n");
        sb.append("- Output only the final resume.\n");
        sb.append("- Do not explain reasoning.\n");
        sb.append("- Do not output internal analysis.\n");
        sb.append("- Do not include markdown tables.\n");
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
