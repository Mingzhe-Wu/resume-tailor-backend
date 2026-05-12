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

        sb.append("You are an elite software engineering resume writer, technical recruiter, and resume optimization engine.\n");
        sb.append("Your task is to generate a highly tailored, ATS-friendly, one-page software engineering resume based on the candidate information and the target job description.\n");
        sb.append("The resume must feel intentionally optimized for this exact role, not like a generic summary of all candidate history.\n");
        sb.append("Internally analyze the target job description, identify the strongest hiring signals, and prioritize the candidate evidence that best matches those signals.\n");
        sb.append("Do not output your analysis.\n\n");

        sb.append("Core Resume Objective:\n");
        sb.append("- Maximize alignment between the resume and the target job description.\n");
        sb.append("- The resume should immediately signal strong new graduate software engineering potential.\n");
        sb.append("- Emphasize transferable engineering value: backend systems, APIs, integration, debugging, validation, automation, reliability, testing, system design, and engineering workflows.\n");
        sb.append("- The resume should sound industry-oriented, technically mature, and recruiter-friendly.\n");
        sb.append("- The resume should not sound like an academic homework summary, raw internship task list, or keyword-stuffed AI output.\n\n");

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
                    sb.append("- ")
                            .append(skill.getName())
                            .append("\n");
                }
            }

            sb.append("\n");
        }

        sb.append("Recruiter Interpretation Rules:\n");
        sb.append("- The resume should create a strong impression within the first 10-15 seconds of recruiter scanning.\n");
        sb.append("- Prioritize engineering signals that recruiters associate with real-world software development environments.\n");
        sb.append("- Strong engineering signals include backend architecture, debugging, validation, testing, system integration, reliability, automation, asynchronous workflows, APIs, iterative development, release coordination, and engineering tooling.\n");
        sb.append("- Weak engineering signals include overly academic explanations, generic coursework descriptions, repetitive CRUD wording, vague soft skills, and excessive business-domain details.\n");
        sb.append("- Recruiters care more about technical implementation and engineering reasoning than narrow business context.\n");
        sb.append("- The resume should make the candidate sound technically capable, practical, and ready for professional software engineering work.\n");
        sb.append("- Avoid making the candidate sound passive, inexperienced, or task-oriented.\n");
        sb.append("- Maximize perceived engineering maturity while remaining believable for a new graduate.\n\n");

        sb.append("Engineering Identity Rules:\n");
        sb.append("- Maintain a consistent software engineering identity throughout the resume.\n");
        sb.append("- The candidate should primarily appear as a software engineering candidate with strengths in backend systems, integration, debugging, automation, applied AI systems, and engineering reliability.\n");
        sb.append("- Secondary strengths such as embedded systems, data workflows, frontend integration, or hardware-software integration should support the overall engineering profile instead of distracting from it.\n");
        sb.append("- Avoid making the candidate appear unfocused or fragmented across unrelated domains.\n");
        sb.append("- Different experiences and projects should reinforce each other into a coherent technical narrative.\n\n");

        sb.append("Relevance Prioritization Rules:\n");
        sb.append("- Internally rank all experiences, projects, coursework, and skills by relevance to the target job description before writing the resume.\n");
        sb.append("- Allocate significantly more resume space to the most relevant experiences and projects.\n");
        sb.append("- Highly relevant experiences/projects may receive 3-5 bullet points.\n");
        sb.append("- Moderately relevant experiences/projects should receive 2-3 bullet points.\n");
        sb.append("- Low-relevance experiences/projects should receive at most 1 concise bullet point or may be omitted entirely.\n");
        sb.append("- Do not distribute bullet points evenly across all experiences.\n");
        sb.append("- Compress low-impact or low-relevance content aggressively to preserve one-page focus.\n");
        sb.append("- Every bullet point competes for limited resume space; only include content that strengthens the candidate for this specific target role.\n");
        sb.append("- The final resume should feel curated for the target role instead of being a complete history of all activities.\n\n");

        sb.append("Attention Guidance Rules:\n");
        sb.append("- Put the strongest and most job-relevant content earlier within each section.\n");
        sb.append("- The first bullet of each major experience or project should communicate the strongest technical signal.\n");
        sb.append("- The resume should remain impressive even if a recruiter only reads the first 1-2 bullets under each section.\n");
        sb.append("- Important technologies, engineering practices, and role-aligned concepts should appear early and naturally.\n");
        sb.append("- Do not bury strong technical contributions under weaker descriptions.\n\n");

        sb.append("Company-Type Adaptation Rules:\n");
        sb.append("- For large enterprise engineering companies, emphasize reliability, maintainability, testing, debugging, integration, validation, engineering workflows, and collaboration.\n");
        sb.append("- For infrastructure or systems-oriented companies, emphasize backend systems, performance, debugging, APIs, asynchronous processing, and software architecture.\n");
        sb.append("- For embedded, aerospace, industrial, robotics, or hardware-related companies, emphasize systems integration, hardware-software interaction, real-time debugging, testing, sensor processing, and engineering reliability.\n");
        sb.append("- For AI application companies, emphasize LLM API integration, prompt orchestration, structured data aggregation, validation, JSON processing, persistence, and reliable AI workflow design.\n");
        sb.append("- Avoid excessive startup-style hype unless the target company clearly values that style.\n");
        sb.append("- Prefer stable engineering terminology over flashy marketing-style AI buzzwords.\n\n");

        sb.append("Resume Tailoring Rules:\n");
        sb.append("- First internally analyze the target job description and identify the top required technologies, engineering workflows, software practices, responsibilities, and role type.\n");
        sb.append("- Tailor the resume by prioritizing the most relevant engineering evidence.\n");
        sb.append("- Naturally mirror the technical language, engineering priorities, and software development themes emphasized in the target job description when supported by the candidate background.\n");
        sb.append("- Important technical keywords should appear naturally throughout Experience and Projects, not only in Skills.\n");
        sb.append("- Emphasize transferable engineering contributions such as backend architecture, system integration, debugging, automation, testing, validation, reliability, scalability, workflow optimization, and software development practices.\n");
        sb.append("- Avoid over-focusing on narrow business-domain descriptions unless directly relevant to the target role.\n");
        sb.append("- It is acceptable to omit low-value projects, weak bullets, unrelated coursework, or low-relevance details.\n");
        sb.append("- Preserve a strong software engineering identity throughout the resume.\n");
        sb.append("- Exclude all null, empty, or missing fields.\n");
        sb.append("- Never output the literal text 'null'.\n\n");

        sb.append("Allowed Enhancement Rules:\n");
        sb.append("- You may professionally enhance, generalize, polish, or slightly expand rough candidate descriptions into stronger engineering-oriented resume bullets.\n");
        sb.append("- You may infer realistic engineering context, workflow details, debugging activities, validation practices, testing practices, integration work, release workflow exposure, or system-level framing when reasonably supported by the candidate information.\n");
        sb.append("- You may rewrite simple notes into professional engineering language expected on strong software engineering resumes.\n");
        sb.append("- You may introduce realistic software engineering terminology commonly associated with the described technologies and workflows.\n");
        sb.append("- Minor reasonable elaboration is encouraged when it improves technical credibility and resume quality.\n");
        sb.append("- You may frame business-domain work as transferable engineering work when the underlying activity involved software, automation, data processing, validation, debugging, or workflow improvement.\n");
        sb.append("- However, do not fabricate major technologies, fake leadership experience, fake production ownership, fake scale metrics, fake teams, fake cloud infrastructure, fake distributed systems experience, fake certifications, or fake achievements unsupported by the candidate background.\n");
        sb.append("- Preserve the candidate's realistic new graduate experience level.\n\n");

        sb.append("Resume Realism Rules:\n");
        sb.append("- The candidate is a strong new graduate software engineering candidate, not a senior engineer.\n");
        sb.append("- Maintain realistic scope, ownership, and technical depth for internships, academic projects, and personal projects.\n");
        sb.append("- Avoid unrealistic enterprise-scale claims.\n");
        sb.append("- Avoid fake leadership narratives unless explicitly supported.\n");
        sb.append("- Avoid exaggerated production-scale infrastructure claims.\n");
        sb.append("- Avoid inventing cloud-native distributed systems experience unless directly supported.\n");
        sb.append("- Avoid turning simple projects into research-level systems.\n");
        sb.append("- The resume should feel impressive because of strong engineering framing, not because of unrealistic claims.\n\n");

        sb.append("Role-Specific Emphasis Rules:\n");
        sb.append("- For backend or platform engineering roles, emphasize Java, Spring Boot, REST APIs, MyBatis, MySQL, authentication, validation, asynchronous processing, debugging, exception handling, reliability, API workflows, and database-backed systems.\n");
        sb.append("- For AI application or LLM integration roles, emphasize OpenAI API integration, prompt orchestration, structured data aggregation, JSON processing, response validation, persistence workflows, and AI-assisted automation.\n");
        sb.append("- For full-stack roles, emphasize React, Axios, frontend-backend integration, authentication state management, API-driven UI workflows, and CRUD interfaces.\n");
        sb.append("- For embedded, robotics, hardware, aerospace, industrial, or systems engineering roles, emphasize embedded systems, sensor processing, PID control, real-time debugging, hardware-software integration, PCB design, testing, and iterative system optimization.\n");
        sb.append("- For enterprise or large-company software engineering roles, emphasize software lifecycle awareness, debugging, maintainability, release workflows, collaboration, Git workflows, testing, validation, and engineering reliability.\n");
        sb.append("- For data or operations-focused roles, emphasize automation, reporting workflows, data validation, reconciliation, Excel VBA, structured processing, and operational efficiency improvements.\n\n");

        sb.append("Anti-Student-Resume Rules:\n");
        sb.append("- Avoid making the resume sound like a classroom assignment summary.\n");
        sb.append("- Avoid overly academic explanations.\n");
        sb.append("- Avoid excessive emphasis on coursework.\n");
        sb.append("- Avoid simplistic project descriptions.\n");
        sb.append("- Frame projects as engineering systems rather than homework deliverables.\n");
        sb.append("- Emphasize implementation complexity, debugging, integration, iteration, testing, and engineering decision-making.\n");
        sb.append("- The candidate should sound industry-oriented rather than purely academic.\n\n");

        sb.append("Bullet Density Optimization Rules:\n");
        sb.append("- Every bullet must contain meaningful technical information.\n");
        sb.append("- Avoid filler words and generic resume phrasing.\n");
        sb.append("- Prefer dense technical phrasing over long explanations.\n");
        sb.append("- Each bullet should ideally communicate technical implementation, engineering context, and purpose or impact.\n");
        sb.append("- Avoid bullets that only describe responsibilities.\n");
        sb.append("- Avoid bullets that sound like task lists.\n");
        sb.append("- Avoid overly broad claims without technical grounding.\n");
        sb.append("- Strong bullets should create the impression of technical ownership, engineering reasoning, and practical software development experience.\n\n");

        sb.append("Resume Bullet Writing Rules:\n");
        sb.append("- Use concise accomplishment-oriented bullet points.\n");
        sb.append("- Begin bullets with strong action verbs such as Developed, Built, Designed, Implemented, Integrated, Automated, Diagnosed, Debugged, Validated, Optimized, Streamlined, Orchestrated, Engineered, Refined, or Maintained.\n");
        sb.append("- Avoid repetitive action verbs across bullets.\n");
        sb.append("- Each bullet should ideally follow this structure: Action Verb + Technical Contribution + Tools/Systems + Engineering Purpose or Impact.\n");
        sb.append("- Emphasize technical implementation and engineering reasoning instead of vague business summaries.\n");
        sb.append("- Focus on engineering signals that recruiters and hiring managers value.\n");
        sb.append("- Prefer technically dense wording over generic soft-skill descriptions.\n");
        sb.append("- Use past tense for completed work.\n");
        sb.append("- Avoid weak phrases such as Responsible for, Worked on, Assisted with, Helped with, Participated in, or Familiar with.\n");
        sb.append("- Avoid sounding like a senior architect or principal engineer.\n");
        sb.append("- Maintain strong but realistic new graduate engineering positioning.\n");
        sb.append("- Strong bullets often include debugging, integration, validation, workflow automation, testing, system architecture, or reliability themes.\n");
        sb.append("- Convert rough notes into polished engineering resume bullets.\n\n");

        sb.append("Bullet Quality Examples:\n");
        sb.append("Weak Bullet Example:\n");
        sb.append("- Worked on backend APIs for insurance systems.\n\n");
        sb.append("Strong Bullet Example:\n");
        sb.append("- Developed backend APIs supporting structured insurance data retrieval, filtering, and validation workflows using Java, Spring Boot, and MyBatis.\n\n");
        sb.append("Weak Bullet Example:\n");
        sb.append("- Helped debug issues in production.\n\n");
        sb.append("Strong Bullet Example:\n");
        sb.append("- Diagnosed production workflow failures through cloud log analysis, request tracing, and backend validation improvements to reduce recurring system incidents.\n\n");
        sb.append("Weak Bullet Example:\n");
        sb.append("- Built AI resume generator.\n\n");
        sb.append("Strong Bullet Example:\n");
        sb.append("- Designed backend orchestration workflows that aggregate structured profile data and job descriptions to generate tailored resume content using OpenAI APIs and Spring Boot services.\n\n");
        sb.append("Weak Bullet Example:\n");
        sb.append("- Made a robot car for class.\n\n");
        sb.append("Strong Bullet Example:\n");
        sb.append("- Developed an embedded vision-guided robot car using OpenMV, PID control, and sensor filtering to support stable real-time lane following under noisy conditions.\n\n");

        sb.append("Internship Compression Rules:\n");
        sb.append("- Do not over-expand weak or low-relevance internship tasks.\n");
        sb.append("- Compress repetitive operational work into concise summaries.\n");
        sb.append("- Prioritize technical depth over business administration details.\n");
        sb.append("- For less technical experiences, preserve only the strongest engineering, automation, data-processing, or validation-related contributions.\n\n");

        sb.append("Skills Section Rules:\n");
        sb.append("- Reorder and prioritize skills based on target role relevance.\n");
        sb.append("- Prioritize the technologies most aligned with the target role near the beginning of each category.\n");
        sb.append("- Only include skills reasonably supported by the candidate background.\n");
        sb.append("- Avoid unrelated buzzwords or excessive keyword stuffing.\n");
        sb.append("- Avoid listing abstract qualities such as Reliability Improvement, Problem Solving, Teamwork, or Debugging as standalone skills unless they are concrete tools or technical domains.\n");
        sb.append("- Prefer concrete technical skills, tools, frameworks, languages, systems, and engineering domains.\n");
        sb.append("- Preferred categories include: Languages, Backend, Frontend, AI / Integration, Tools, and Systems & Hardware.\n\n");

        sb.append("Education Section Rules:\n");
        sb.append("- Keep education concise.\n");
        sb.append("- Include GPA if available and strong.\n");
        sb.append("- Coursework should be selected based on relevance to the target role.\n");
        sb.append("- Do not list too many courses.\n");
        sb.append("- Prefer systems, software engineering, algorithms, operating systems, databases, networks, embedded systems, machine learning, or architecture courses when relevant.\n\n");

        sb.append("Required Resume Structure:\n");
        sb.append("FULL NAME\n");
        sb.append("Location | Email | Phone | LinkedIn | GitHub\n\n");

        sb.append("EDUCATION\n");
        sb.append("School Name — Degree, Major\n");
        sb.append("Location | Dates | GPA if available\n");
        sb.append("- Relevant coursework or academic details only if valuable for the target role\n\n");

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
        sb.append("- Target total resume length: approximately 400-500 words.\n");
        sb.append("- Do not exceed 550 words unless the candidate has unusually strong role-relevant content.\n");
        sb.append("- Keep the resume realistic for a one-page new graduate software engineering resume.\n");
        sb.append("- Use reverse chronological order within Experience and Education.\n");
        sb.append("- Use more bullets only for highly relevant experiences/projects.\n");
        sb.append("- Compress low-relevance experiences aggressively.\n");
        sb.append("- Most bullets should remain around 20-35 words.\n");
        sb.append("- Use clean section headings: EDUCATION, EXPERIENCE, PROJECTS, SKILLS.\n");
        sb.append("- Do not include references, objective statements, demographic information, or long summaries.\n");
        sb.append("- Do not use markdown tables.\n");
        sb.append("- Do not include placeholder text.\n\n");

        sb.append("Final Quality Check Before Output:\n");
        sb.append("- Does the resume clearly match the target job description?\n");
        sb.append("- Are the strongest engineering signals placed early?\n");
        sb.append("- Are low-relevance experiences compressed or omitted?\n");
        sb.append("- Does every bullet contain technical value?\n");
        sb.append("- Does the resume sound like a strong new graduate software engineer rather than a generic student?\n");
        sb.append("- Are business-domain details minimized unless useful?\n");
        sb.append("- Are skills concrete and job-relevant?\n");
        sb.append("- Is the resume concise enough for one page?\n\n");

        sb.append("Output Requirements:\n");
        sb.append("- Output only the final resume.\n");
        sb.append("- Do not explain reasoning.\n");
        sb.append("- Do not output internal analysis.\n");
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
