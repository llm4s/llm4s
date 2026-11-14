package org.llm4s.quarkus.runtime;

import io.quarkus.arc.BeanDestroyer;
import jakarta.enterprise.context.spi.CreationalContext;
import java.util.Map;
import org.llm4s.javaapi.JLlmClient;

/** Closes a client bean when the application stops. */
public class JLlmClientDestroyer implements BeanDestroyer<JLlmClient> {

    @Override
    public void destroy(JLlmClient client, CreationalContext<JLlmClient> context, Map<String, Object> params) {
        client.close();
    }
}
