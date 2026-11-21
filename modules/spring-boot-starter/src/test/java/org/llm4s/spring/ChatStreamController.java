// The WebFlux controller of docs/guide/spring-boot.md, compiled and run by Llm4sStreamingWebSpec. From the first import
// to the closing brace it is the guide's snippet word for word (SpringBootGuideSpec checks that), so change the guide and
// this file together.
package org.llm4s.spring;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatStreamController {

    private final ReactiveLLM4STemplate llm;

    public ChatStreamController(ReactiveLLM4STemplate llm) {
        this.llm = llm;
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam("q") String question) {
        return llm.stream(question);
    }
}
