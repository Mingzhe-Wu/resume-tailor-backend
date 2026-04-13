package com.mingzhe.resumetailor.education;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class UpdateEducationDTO {

    private String schoolName;

    private String degree;

    private String major;

    private LocalDate startDate;

    private LocalDate endDate;

    private BigDecimal gpa;

    private String relevantCoursework;

    private String description;

}
