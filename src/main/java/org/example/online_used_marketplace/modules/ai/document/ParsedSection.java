package org.example.online_used_marketplace.modules.ai.document;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ParsedSection {
    private String sectionId;
    private List<String> headingPath = new ArrayList<>();
    private Integer pageFrom;
    private Integer pageTo;
    private String contentType = "PARAGRAPH";
    private String content;
    private Integer sourceStart;
    private Integer sourceEnd;
}
