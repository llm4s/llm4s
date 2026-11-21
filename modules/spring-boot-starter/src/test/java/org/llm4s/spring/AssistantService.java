// The agent service of docs/guide/spring-boot.md, compiled and run by SpringBootGuideSpec. From the first import to the
// closing brace it is the guide's snippet word for word (the spec checks that), so change the guide and this file
// together.
package org.llm4s.spring;

import org.llm4s.javaapi.JAgent;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {

    private final JAgent agent;

    public AssistantService(JAgent agent) {
        this.agent = agent;
    }

    public String ask(String question) {
        return agent.run(question).get().answer().orElse("");
    }
}
