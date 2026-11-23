package org.llm4s.javaapi.rag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import org.llm4s.javaapi.JEmbeddingClient;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.JMessage;
import org.llm4s.javaapi.LlmException;
import org.llm4s.javaapi.LlmResult;

/**
 * {@link JRag}'s conversation history and question condensers (#2766) from real Java source, compiled by javac and
 * driven by {@code JRagHistorySpec}.
 */
final class JRagHistoryCheck {

  private JRagHistoryCheck() {}

  static final List<JMessage> HISTORY =
      List.of(JMessage.user("Which animal sat on the mat?"), JMessage.assistant("The cat."));

  /** A pipeline holding the cat and dog documents, with {@code condenser} applied to its builder. */
  static JRag pipeline(
      JEmbeddingClient embeddings, JLlmClient llm, java.util.function.UnaryOperator<JRagBuilder> condenser) {
    JRag rag = condenser.apply(JRag.builder().embeddings(embeddings).llm(llm)).build().get();
    rag.ingestText("The cat sat on the mat.", "cats").get();
    rag.ingestText("The dog chewed a bone.", "dogs").get();
    return rag;
  }

  /** Each answer method with the history: what each answers, as {@code method:answer}. */
  static List<String> everyMethod(JRag rag, String question) {
    List<String> log = new ArrayList<>();
    log.add("ask:" + rag.ask(question, HISTORY).get().answer());
    log.add("askTopK:" + rag.ask(question, 1, HISTORY).get().sources().size());
    List<String> pieces = new ArrayList<>();
    log.add("askStream:" + rag.askStream(question, HISTORY, pieces::add).get().await().get().answer());
    log.add("askStreamTopK:"
        + rag.askStream(question, 1, HISTORY, pieces::add).get().await().get().sources().size());
    return log;
  }

  /** A builder whose condenser is a lambda over the question and the last message of the history. */
  static JRagBuilder lambdaCondenser(JRagBuilder builder) {
    return builder.questionCondenser(
        (question, history) -> history.get(history.size() - 1).content() + " " + question);
  }

  /** A builder whose condenser returns {@code reply}, or throws when it is {@code "throw"}. */
  static JRagBuilder fixedCondenser(JRagBuilder builder, String reply) {
    BiFunction<String, List<JMessage>, String> condenser = (question, history) -> {
      if ("throw".equals(reply)) {
        throw new IllegalStateException("condenser broke");
      }
      return reply;
    };
    return builder.questionCondenser(condenser);
  }

  /** The history each call refuses, as {@code kind:message}. */
  static List<String> refused(JRag rag, JMessage system) {
    List<String> log = new ArrayList<>();
    log.add(describe(rag.ask("Q?", (List<JMessage>) null)));
    log.add(describe(rag.ask("Q?", Arrays.asList(JMessage.user("hi"), null))));
    log.add(describe(rag.ask("Q?", List.of(system))));
    log.add(describe(rag.askStream("Q?", List.of(system), text -> {})));
    return log;
  }

  /** The builder's condensers, set and cleared, as {@code toString} shows them. */
  static List<String> builders(JEmbeddingClient embeddings, JLlmClient llm) {
    JRagBuilder base = JRag.builder().embeddings(embeddings);
    List<String> log = new ArrayList<>();
    log.add(base.llmQuestionCondenser(llm).toString());
    log.add(base.llmQuestionCondenser(llm).llmQuestionCondenser(null).toString());
    log.add(lambdaCondenser(base).questionCondenser(null).toString());
    return log;
  }

  static String describe(LlmResult<?> result) {
    if (result.isSuccess()) {
      return "ok:" + result.get();
    }
    LlmException error = result.getError();
    return error.getKind() + ":" + error.getMessage();
  }
}
