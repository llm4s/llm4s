package org.llm4s.javaapi.rag;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.llm4s.javaapi.JTokenUsage;

/**
 * What {@link JRag#ask(String)} returns: the model's answer, the question it answered and the chunks it was given to
 * answer from, best match first.
 *
 * <p>A value: two are equal when every field is. Not {@code Serializable}. Built only by this package - its
 * constructor is package-private, so fields can be added without breaking a caller.
 */
public final class JRagAnswer {

  private final String answer;
  private final String question;
  private final List<JRagSource> sources;
  private final Optional<JTokenUsage> usage;

  /** {@code sources} is the package's own unmodifiable copy. */
  JRagAnswer(String answer, String question, List<JRagSource> sources, Optional<JTokenUsage> usage) {
    this.answer = answer;
    this.question = question;
    this.sources = sources;
    this.usage = usage;
  }

  /** The model's answer. */
  public String answer() {
    return answer;
  }

  /** The question, as it was asked. */
  public String question() {
    return question;
  }

  /**
   * The chunks the model was given as context, best match first, at most the pipeline's {@code topK}; empty when
   * nothing is indexed. Unmodifiable.
   */
  public List<JRagSource> sources() {
    return sources;
  }

  /** The tokens the model call used, when the provider reported them. */
  public Optional<JTokenUsage> usage() {
    return usage;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JRagAnswer that
        && Objects.equals(answer, that.answer)
        && Objects.equals(question, that.question)
        && Objects.equals(sources, that.sources)
        && Objects.equals(usage, that.usage);
  }

  @Override
  public int hashCode() {
    return Objects.hash(answer, question, sources, usage);
  }

  /** The answer's full text, so mind what you log. */
  @Override
  public String toString() {
    return "JRagAnswer(" + answer + ", " + sources.size() + " sources)";
  }
}
