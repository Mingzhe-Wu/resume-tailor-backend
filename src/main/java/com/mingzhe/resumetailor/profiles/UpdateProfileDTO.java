package com.mingzhe.resumetailor.profiles;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// DTO for profile update
// fullName, and contactEmail required
@Data
public class UpdateProfileDTO {
    @NotBlank(message = "fullName is required for profile")
    private String fullName;
    private String phone;
    @NotBlank(message = "contactEmail is required for profile")
    private String contactEmail;
    private String linkedinUrl;
    private String githubUrl;
    private String location;
    private String summary;
}
