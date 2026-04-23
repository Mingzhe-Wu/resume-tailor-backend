package com.mingzhe.resumetailor.job;

import com.mingzhe.resumetailor.exceptions.BadRequestException;
import com.mingzhe.resumetailor.exceptions.ResourceNotFoundException;
import com.mingzhe.resumetailor.user.User;
import com.mingzhe.resumetailor.user.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Business logic for validating and managing Job records.
 */
@Service
public class JobService {

    private final JobMapper jobMapper;
    private final UserMapper userMapper;

    private static final int STATUS_SAVED = 1;
    private static final int STATUS_APPLIED = 2;
    private static final int STATUS_INTERVIEW = 3;
    private static final int STATUS_OFFER = 4;
    private static final int STATUS_REJECTED = 5;

    public JobService(JobMapper jobMapper, UserMapper userMapper) {
        this.jobMapper = jobMapper;
        this.userMapper = userMapper;
    }

    public Job createJob(CreateJobDTO request) {
        User user = userMapper.findById(request.getUserId());
        if (user == null) {
            throw new ResourceNotFoundException("User not found");
        }

        if (!isValidStatus(request.getStatus())) {
            throw new BadRequestException("status must be between 1 and 5");
        }

        Job job = new Job();
        job.setUserId(request.getUserId());
        job.setTitle(request.getTitle());
        job.setCompany(request.getCompany());
        job.setJobDescription(request.getJobDescription());
        job.setSourceUrl(request.getSourceUrl());
        job.setStatus(request.getStatus());
        job.setInterviewTime(request.getInterviewTime());

        jobMapper.insert(job);
        return job;
    }

    public List<Job> fetchJobsByUserId(Long userId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("User not found");
        }

        return jobMapper.findByUserId(userId);
    }

    public Job updateJob(Long id, UpdateJobDTO request) {
        Job existingJob = jobMapper.findById(id);
        if (existingJob == null) {
            throw new ResourceNotFoundException("Job not found");
        }

        if (request.getStatus() != null && !isValidStatus(request.getStatus())) {
            throw new BadRequestException("status must be between 1 and 5");
        }

        Job update = new Job();
        update.setId(id);
        update.setTitle(request.getTitle());
        update.setCompany(request.getCompany());
        update.setJobDescription(request.getJobDescription());
        update.setSourceUrl(request.getSourceUrl());
        update.setStatus(request.getStatus());
        update.setInterviewTime(request.getInterviewTime());

        jobMapper.updateById(update);
        return jobMapper.findById(id);
    }

    public void deleteJob(Long id) {
        Job existingJob = jobMapper.findById(id);
        if (existingJob == null) {
            throw new ResourceNotFoundException("Job not found");
        }

        jobMapper.deleteById(id);
    }

    private boolean isValidStatus(Integer status) {
        return status != null && status >= STATUS_SAVED && status <= STATUS_REJECTED;
    }

}
