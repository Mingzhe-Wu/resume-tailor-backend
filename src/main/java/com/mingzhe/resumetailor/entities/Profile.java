package com.mingzhe.resumetailor.entities;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Profile {
    private Long id;
    private Long userId;
    private String fullName;
    private String phone;
    private String contactEmail;
    private String linkedinUrl;
    private String githubUrl;
    private String location;
    private String summary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
