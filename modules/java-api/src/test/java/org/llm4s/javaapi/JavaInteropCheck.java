package org.llm4s.javaapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.llm4s.agent.events.AgentEvents;
import org.llm4s.agent.graph.StreamEvent;
import org.llm4s.error.LLMError;
import org.llm4s.llmconnect.model.Conversation;

/**
 * Real Java source compiled by sbt (mixed compilation) against the module's public API. It proves
 * that the documented Java quick-start shapes (static factories, lambdas passed as
 * {@code java.util.function} types, {@link Optional}, {@link CompletableFuture}, try-with-resources)
 * compile and behave from Java, which Scala-only tests cannot show.
 *
 * <p>The methods take already-built clients so that no Scala-only construction is needed here.
 */
public final class JavaInteropCheck {
  private JavaInteropCheck() {}

  /** An LLMError implemented from Java: the failure path must be constructible without Scala syntax. */
  static final class JavaError implements LLMError {
    private final String msg;

    JavaError(String msg) {
      this.msg = msg;
    }

    @Override
    public String message() {
      return msg;
    }

    @Override
    public int productArity() {
      return 1;
    }

    @Override
    public Object productElement(int n) {
      return msg;
    }

    @Override
    public boolean canEqual(Object that) {
      return that instanceof JavaError;
    }
  }

  /** Drives the quick-start from the Scaladoc against a client that answers "4". */
  public static List<String> quickStart(JLlmClient client) throws Exception {
    List<String> log = new ArrayList<>();
    try (JLlmClient c = client) {
      LlmResult<String> r = c.complete("What is 2+2?");
      r.ifSuccess(s -> log.add("ok:" + s)).ifFailure(e -> log.add("err:" + e.getMessage()));
      log.add("get:" + r.get());
      log.add("len:" + r.map(String::length).get());
      Optional<String> opt = r.toOptional();
      log.add("opt:" + opt.orElse("none"));
      CompletableFuture<String> f = r.toCompletableFuture();
      log.add("cf:" + f.get());

      Conversation conv =
          ConversationBuilder.create().system("be brief").user("hi").assistant("hello").user("2+2").build();
      log.add("conv:" + c.complete(conv).get());
      // NOTE: complete(Conversation, CompletionOptions) is intentionally not driven here:
      // CompletionOptions cannot be built from Java without scala.Option/scala.Seq arguments
      // (known gap, see the PR review).
    }
    return log;
  }

  /**
   * Streams a turn from Java: a lambda as the listener, a thread id as a String, events read with
   * StreamEvents and instanceof, the result from await, and a cancel on the finished stream. The
   * listener holds its first event until {@code gate} opens.
   */
  public static List<String> streaming(JAgent agent, CountDownLatch gate) {
    List<String> log = new CopyOnWriteArrayList<>();
    AtomicBoolean first = new AtomicBoolean(true);
    AgentStreamListener listener =
        event -> {
          if (first.getAndSet(false)) {
            awaitQuietly(gate);
          }
          if (event instanceof StreamEvent.Durable) {
            log.add("durable");
          } else if (event instanceof StreamEvent.LiveGap) {
            log.add("gap:" + ((StreamEvent.LiveGap) event).dropped());
          }
          StreamEvents.decode(AgentEvents.TextDelta(), event).ifPresent(d -> log.add("delta:" + d.text()));
        };
    AgentStream stream = agent.stream("java-thread", "hi", listener).get();
    LlmResult<JAgentResult> result = stream.await();
    stream.cancel();
    log.add("answer:" + result.get().answer().get());
    log.add("refused:" + agent.stream("java-thread-2", " ", listener).isFailure());
    log.add("resume-refused:" + agent.streamResume("java-thread", List.of(Answer.approve("no-such-interrupt")), listener).isFailure());
    return log;
  }

  /**
   * A suspended turn from Java: run, switch over the status's kind, read what it waits for from the
   * status - a switch over the Java enum, JSON as Strings, Optional for the kind-specific field -
   * answer each with Answer, and resume until the turn is no longer suspended. Approvals are
   * approved; a question gets {@code reply}. {@code JAgent.pending} is the same list.
   */
  public static List<String> answering(JAgent agent, String query, String reply) {
    List<String> log = new ArrayList<>();
    LlmResult<JAgentResult> turn = agent.run(query);
    while (turn.get().status().kind() == AgentStatusKind.SUSPENDED) {
      List<PendingInterrupt> pending = turn.get().status().pending();
      if (!pending.equals(JAgent.pending(turn.get()))) {
        log.add("UNEXPECTED-PENDING-MISMATCH");
      }
      List<Answer> answers = new ArrayList<>();
      for (PendingInterrupt p : pending) {
        switch (p.kind()) {
          case APPROVAL -> {
            log.add("approve:" + p.toolName().orElse("?") + ":" + p.argumentsJson().orElse("?") + ":" + p.reason().orElse("?"));
            answers.add(Answer.approve(p.id()));
          }
          case QUESTION -> {
            log.add("reply:" + p.toolName().orElse("?") + ":" + p.questionJson().orElse("?"));
            answers.add(Answer.reply(p.id(), reply));
          }
          case MIDDLEWARE_QUESTION -> {
            log.add("middleware:" + p.middleware().orElse("?") + ":" + p.questionJson().orElse("?"));
            answers.add(Answer.reply(p.id(), reply));
          }
          case BREAKPOINT -> {
            log.add("breakpoint:" + p.node().orElse("?") + ":" + p.phase().map(Enum::name).orElse("?"));
            answers.add(Answer.proceed(p.id()));
          }
        }
      }
      turn = agent.resume(turn.get().threadId(), answers);
    }
    log.add("answer:" + turn.get().answer().get());
    return log;
  }

  /**
   * Reads a turn's whole result from Java with nothing but JDK types and this package's: a switch over
   * the status's kind, the history with a switch over each message's role, tool calls with their JSON
   * as text, and the usage totals and per-model map.
   */
  public static List<String> reading(JAgentResult result) {
    List<String> log = new ArrayList<>();
    JAgentStatus status = result.status();
    switch (status.kind()) {
      case COMPLETED -> log.add("completed:" + status.answer().orElseThrow());
      case BLOCKED -> log.add("blocked:" + status.guardrail().orElseThrow() + ":" + status.reason().orElseThrow());
      case STEP_LIMIT_REACHED -> log.add("step-limit");
      case SUSPENDED -> log.add("suspended:" + status.pending().size());
    }
    log.add("answer:" + result.answer().orElse("-"));
    log.add("thread:" + result.threadId().isEmpty() + ":" + result.runId().isEmpty() + ":" + result.activeAgent());
    for (JMessage m : result.messages()) {
      switch (m.role()) {
        case SYSTEM -> log.add("system:" + m.content());
        case USER -> log.add("user:" + m.content());
        case ASSISTANT -> {
          StringBuilder calls = new StringBuilder();
          for (JToolCall c : m.toolCalls()) {
            calls.append(c.id()).append('=').append(c.name()).append(c.argumentsJson());
          }
          log.add("assistant:" + m.content() + ":" + calls + ":" + m.thinking().orElse("-"));
        }
        case TOOL -> log.add("tool:" + m.toolCallId().orElseThrow() + ":" + m.content());
      }
    }
    JUsageSummary usage = result.usage();
    long tokens = usage.inputTokens() + usage.outputTokens() + usage.thinkingTokens();
    log.add("usage:" + usage.requestCount() + ":" + tokens + ":" + usage.totalCost().signum());
    for (Map.Entry<String, JModelUsage> e : usage.byModel().entrySet()) {
      JModelUsage m = e.getValue();
      long modelTokens = m.inputTokens() + m.outputTokens() + m.thinkingTokens();
      log.add("model:" + e.getKey() + ":" + m.requestCount() + ":" + modelTokens + ":" + m.totalCost().signum());
    }
    return log;
  }

  /** Recovers a thread from Java, by its id as a String; a failed result for one with nothing to recover. */
  public static List<String> recovering(JAgent agent, String threadId) {
    List<String> log = new ArrayList<>();
    LlmResult<JAgentResult> recovered = agent.recover(threadId);
    log.add("answer:" + recovered.get().answer().get());
    log.add("again-refused:" + agent.recover(threadId).isFailure());
    log.add("resume-refused:" + agent.resume(threadId, List.of(Answer.approve("no-such-interrupt"))).isFailure());
    return log;
  }

  private static void awaitQuietly(CountDownLatch gate) {
    try {
      gate.await(60, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Failure handling from Java: getOrNull, getError, Optional, CompletableFuture, get() throwing. */
  public static List<String> failurePath() throws Exception {
    List<String> log = new ArrayList<>();
    LlmResult<String> r = LlmResult.failure(new JavaError("boom"));
    log.add("isFailure:" + r.isFailure());
    log.add("orNull:" + r.getOrNull());
    log.add("opt:" + r.toOptional().isPresent());
    log.add("err:" + r.getError().getMessage());
    r.ifSuccess(s -> log.add("UNEXPECTED")).ifFailure(e -> log.add("cb:" + e.getMessage()));
    try {
      r.get();
      log.add("UNEXPECTED-NO-THROW");
    } catch (LlmException e) {
      log.add("caught:" + e.getMessage());
    }
    try {
      r.toCompletableFuture().get();
    } catch (ExecutionException e) {
      log.add("cf:" + (e.getCause() instanceof LlmException));
    }
    return log;
  }
}
