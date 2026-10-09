// The "another provider" configuration of docs/guide/spring-boot.md, compiled and run by SpringBootGuideSpec against the
// application.conf in this module's test resources. From the first import to the closing brace it is the guide's
// snippet word for word (the spec checks that), so change the guide and this file together.
package org.llm4s.spring;

import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OtherProviderConfig {

    @Bean
    JLlmClient llm4sClient() {
        return Llm4s.createDefaultClient().get();
    }
}
