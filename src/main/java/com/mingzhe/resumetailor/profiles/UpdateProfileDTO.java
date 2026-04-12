package com.mingzhe.resumetailor.profiles;

import lombok.Data;

// DTO for profile update
// fullName, and contactEmail required
@Data
public class UpdateProfileDTO {

    private String fullName;

    private String phone;

    private String contactEmail;

    private String linkedinUrl;

    private String githubUrl;

    private String location;

    private String summary;

}
