package com.mingzhe.resumetailor.resume;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateResumeDTO {

    @NotNull(message = "jobId is required")
    private Long jobId;

    private Integer matchScore;

    private String generatedContent;

    private String pdfFilePath;

}
