package com.mingzhe.resumetailor.resume;

import lombok.Data;

@Data
public class UpdateResumeDTO {

    private Integer matchScore;

    private String generatedContent;

    private String pdfFilePath;

}
