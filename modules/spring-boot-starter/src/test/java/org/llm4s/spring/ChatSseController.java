// The Spring MVC controller of docs/guide/spring-boot.md, compiled and run by Llm4sStreamingWebSpec. From the first
// import to the closing brace it is the guide's snippet word for word (SpringBootGuideSpec checks that), so change the
// guide and this file together.
package org.llm4s.spring;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ChatSseController {

    private final SseLLM4STemplate llm;

    public ChatSseController(SseLLM4STemplate llm) {
        this.llm = llm;
    }

    @GetMapping("/chat/sse")
    public SseEmitter stream(@RequestParam("q") String question) {
        return llm.stream(question);
    }
}
