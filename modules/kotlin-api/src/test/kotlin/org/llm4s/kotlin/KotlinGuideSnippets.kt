package org.llm4s.kotlin

import org.llm4s.javaapi.JAgentResult
import org.llm4s.javaapi.JGuardrails

/**
 * The Kotlin blocks of `docs/guide/java.md`, compiled and run by the tests so the guide cannot drift from the API. Keep
 * each body as the guide prints it.
 */
object KotlinGuideSnippets {

    /** The "An agent with a system prompt, guardrails and a context window" Kotlin block (#2534). */
    suspend fun supportAgent(client: LLMClientKt): JAgentResult {
        val built: Result<AgentKt> = agent("support", client) {
            systemPrompt("You are a support assistant. Answer in JSON.")
            tools {
                tool("add", "Adds two numbers") {
                    param<Long>("a", "First")
                    param<Long>("b", "Second")
                    handler { args -> args.get<Long>("a") + args.get<Long>("b") }
                }
            }
            inputGuardrails(JGuardrails.length(1, 10_000), JGuardrails.profanity())
            outputGuardrails(JGuardrails.json())   // inputGuardrails(JGuardrails.json()) does not compile
            contextWindow(lastMessages = 20)
            streaming()
        }

        val support = built.getOrThrow()          // or built.onFailure { e -> log(e.message) }
        val result = support.run("What is 2 + 3?")
        result.status().guardrail().ifPresent { g ->
            println("Blocked by $g: ${result.status().reason().orElse("")}")
        }
        return result
    }
}
