// The controller of docs/guide/java-migration.md, compiled with spring-web (a test-only dependency of this module) and
// run by AskControllerSpec. From the first import to the closing brace it is the guide's snippet word for word
// (JavaMigrationGuideSpec in llm4s-java-api checks that), so change the guide and this file together.
package org.llm4s.spring;

import org.llm4s.spring.LLM4STemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AskController {

    private final LLM4STemplate llm;

    public AskController(LLM4STemplate llm) {
        this.llm = llm;
    }

    @GetMapping("/ask")
    public String ask(@RequestParam("question") String question) {
        return llm.complete(question);
    }
}
