package com.mingzhe.resumetailor.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

// DTO for profile creation
// userID, fullName, and contactEmail required
@Data
public class CreateProfileDTO {
    @NotNull
    private Long userId;
    @NotBlank
    private String fullName;
    private String phone;
    @NotBlank
    private String contactEmail;
    private String linkedinUrl;
    private String githubUrl;
    private String location;
    private String summary;
}
