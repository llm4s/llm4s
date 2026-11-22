package org.llm4s.javaapi.reactor;

import java.util.List;
import java.util.Objects;
import org.llm4s.javaapi.rag.JRagAnswer;
import org.llm4s.javaapi.rag.JRagSource;

/**
 * One element of {@link RagStreams#toFlux}'s {@code Flux} (#2727): a RAG answer streamed with its sources. A
 * subscription receives exactly one {@link Sources}, then a {@link Chunk} for each piece of the answer's text, then one
 * {@link Complete}, and the {@code Flux} completes. A failure ends it with {@code onError} instead, after the elements
 * already delivered.
 *
 * <p>The interface is sealed, so a {@code switch} over its three records needs no {@code default}:
 *
 * <pre>{@code
 * switch (event) {
 *     case RagAnswerEvent.Sources s -> ui.showSources(s.sources());
 *     case RagAnswerEvent.Chunk c -> ui.append(c.text());
 *     case RagAnswerEvent.Complete c -> ui.done(c.answer().usage());
 * }
 * }</pre>
 */
public sealed interface RagAnswerEvent
    permits RagAnswerEvent.Sources, RagAnswerEvent.Chunk, RagAnswerEvent.Complete {

  /**
   * The chunks the answer is generated from, best match first: the first element, before the model is asked.
   *
   * @param sources an unmodifiable list, empty when nothing is indexed
   */
  record Sources(List<JRagSource> sources) implements RagAnswerEvent {

    /** @throws NullPointerException when {@code sources} is {@code null} */
    public Sources {
      Objects.requireNonNull(sources, "sources must not be null");
    }
  }

  /**
   * The next piece of the answer's text.
   *
   * @param text never empty
   */
  record Chunk(String text) implements RagAnswerEvent {

    /** @throws NullPointerException when {@code text} is {@code null} */
    public Chunk {
      Objects.requireNonNull(text, "text must not be null");
    }
  }

  /**
   * The whole answer, with the same sources, as the last element.
   *
   * @param answer what {@code JRag.ask} would have returned
   */
  record Complete(JRagAnswer answer) implements RagAnswerEvent {

    /** @throws NullPointerException when {@code answer} is {@code null} */
    public Complete {
      Objects.requireNonNull(answer, "answer must not be null");
    }
  }
}
