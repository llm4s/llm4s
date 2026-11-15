package org.llm4s.quarkus.runtime;

import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.runtime.configuration.ConfigurationException;
import java.util.Set;
import java.util.function.Function;
import org.llm4s.javaapi.FrameworkConfig;
import org.llm4s.javaapi.JEmbeddingClient;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmResult;

/** Builds the clients the extension's beans hold, from the properties of the running application. */
@Recorder
public class Llm4sRecorder {

    /** The client of section {@code section} of {@code llm4s.providers}. */
    public Function<SyntheticCreationalContext<JLlmClient>, JLlmClient> client(String section) {
        return context -> {
            LlmResult<JLlmClient> client = FrameworkConfig.client(Llm4sProperties.current(), section, null);
            if (client.isFailure()) {
                String prefix = "llm4s.providers." + section;
                throw new ConfigurationException(
                    "Cannot build the llm4s client of " + prefix + ": " + client.getError().getMessage(),
                    Set.of(prefix));
            }
            return client.get();
        };
    }

    /** The client of the embedding model {@code llm4s.embeddings.model} names. */
    public Function<SyntheticCreationalContext<JEmbeddingClient>, JEmbeddingClient> embeddingClient() {
        return context -> {
            var properties = Llm4sProperties.current();
            String model = FrameworkConfig.embeddingModel(properties).orElseThrow(() -> new ConfigurationException(
                "llm4s.embeddings.model is not set", Set.of("llm4s.embeddings.model")));
            LlmResult<JEmbeddingClient> client = FrameworkConfig.embeddingClient(properties, model);
            if (client.isFailure()) {
                throw new ConfigurationException(
                    "Cannot build the llm4s embedding client of llm4s.embeddings.model=" + model + ": "
                        + client.getError().getMessage(),
                    Set.of("llm4s.embeddings.model"));
            }
            return client.get();
        };
    }
}
