package org.llm4s.samples;

import org.llm4s.config.Llm4sConfig;
import org.llm4s.llmconnect.LLMConnect;
import org.llm4s.llmconnect.LLMClient;
import org.llm4s.llmconnect.config.ProviderConfig;
import org.llm4s.llmconnect.model.*;
import org.llm4s.model.ModelRegistryService;
import scala.Option;
import scala.collection.immutable.Nil$;

public class HelloLLM4S {
    public static void main(String[] args) {
        System.out.println("Initializing LLM4S Client...");

        ModelRegistryService registry = Llm4sConfig.modelRegistryService().toOption().get();

        ProviderConfig config = Llm4sConfig.defaultProvider().toOption().get();

        LLMClient client = LLMConnect.getClient(config, registry).toOption().get();

        Conversation conversation = Conversation.userOnly("Explain the difference between a class and an object in 2 sentences.");

        CompletionOptions options = new CompletionOptions(
            0.7, 
            1.0, 
            Option.empty(), 
            0.0, 
            0.0, 
            Nil$.MODULE$, 
            Option.empty(), 
            Option.empty(), 
            Option.empty()
        );

        Completion completion = client.complete(conversation, options).toOption().get();

        System.out.println("Response:\n" + completion.message().content());
    }
}
