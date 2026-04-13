package com.mingzhe.resumetailor.job;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Job {

    private Long id;

    private Long userId;

    private String title;

    private String company;

    private String jobDescription;

    private String sourceUrl;

    private Integer status;

    private LocalDateTime interviewTime;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
