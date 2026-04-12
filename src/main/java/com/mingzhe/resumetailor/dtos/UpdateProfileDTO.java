package com.mingzhe.resumetailor.dtos;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// DTO for profile update
// fullName, and contactEmail required
@Data
public class UpdateProfileDTO {
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
