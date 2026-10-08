// The service of docs/guide/spring-boot.md, compiled and run by SpringBootGuideSpec. From the first import to the
// closing brace it is the guide's snippet word for word (the spec checks that), so change the guide and this file
// together.
package org.llm4s.spring;

import org.llm4s.spring.LLM4STemplate;
import org.springframework.stereotype.Service;

@Service
public class SummaryService {

    private final LLM4STemplate llm;

    public SummaryService(LLM4STemplate llm) {
        this.llm = llm;
    }

    public String summarise(String text) {
        return llm.complete("Summarise in one sentence: " + text);
    }
}
