package com.mingzhe.resumetailor.job;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpdateJobDTO {

    private String title;

    private String company;

    private String jobDescription;

    private String sourceUrl;

    private Integer status;

    private LocalDateTime interviewTime;

}
