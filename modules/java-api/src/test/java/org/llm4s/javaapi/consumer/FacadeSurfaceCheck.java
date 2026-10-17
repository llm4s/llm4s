package org.llm4s.javaapi.consumer;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.llm4s.javaapi.JAgent;
import org.llm4s.javaapi.JAgentResult;
import org.llm4s.javaapi.JAgentStatus;
import org.llm4s.javaapi.JCompletion;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.JMessage;
import org.llm4s.javaapi.JModelUsage;
import org.llm4s.javaapi.JToolCall;
import org.llm4s.javaapi.JUsageSummary;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.llmconnect.LLMClient;

/**
 * The facade as a Java caller in another package sees it (#1666): it can read every value the library hands
 * it, but not build one. A package other than {@code org.llm4s.javaapi}, so package-private members are as
 * out of reach here as in a user's code.
 */
public final class FacadeSurfaceCheck {
  private FacadeSurfaceCheck() {}

  /**
   * Wraps {@code model} with {@link Llm4s#wrapClient}, then reads a reply and an agent turn through every
   * accessor of the value types, as a Java caller does.
   */
  public static List<String> readValues(LLMClient model) {
    List<String> log = new ArrayList<>();
    JLlmClient client = Llm4s.wrapClient(model);
    JCompletion reply = client.completion("What is 2+2?").get();
    log.add("completion:" + reply.content() + ":" + reply.model() + ":" + reply.toolCalls().size());
    reply.usage().ifPresent(u -> log.add("tokens:" + u.promptTokens() + "+" + u.completionTokens()));

    JAgent agent = Llm4s.createAgent(client);
    JAgentResult turn = agent.run("What is 2+2?").get();
    JAgentStatus status = turn.status();
    log.add("status:" + status.kind() + ":" + status.answer().orElse("-") + ":" + status.pending().size());
    for (JMessage m : turn.messages()) {
      log.add(m.role() + ":" + m.content());
      for (JToolCall c : m.toolCalls()) {
        log.add("call:" + c.name() + c.argumentsJson());
      }
    }
    JUsageSummary usage = turn.usage();
    log.add("usage:" + usage.requestCount() + ":" + usage.inputTokens() + ":" + usage.outputTokens());
    for (Map.Entry<String, JModelUsage> e : usage.byModel().entrySet()) {
      BigDecimal cost = e.getValue().totalCost();
      log.add("model:" + e.getKey() + ":" + e.getValue().requestCount() + ":" + cost.signum());
    }
    log.add("same:" + turn.equals(turn) + ":" + (turn.hashCode() == turn.hashCode()));
    return log;
  }

  /**
   * Tries every public constructor of {@code type} through reflection - the one way code outside the package
   * can reach a constructor whose parameter it cannot name - with {@code null}s and zeros, and reports what
   * each did: {@code "built"}, or the exception it threw. An empty list: there is no public constructor.
   */
  public static List<String> forge(Class<?> type) {
    List<String> outcomes = new ArrayList<>();
    for (Constructor<?> c : type.getConstructors()) {
      Object[] args = new Object[c.getParameterCount()];
      Class<?>[] params = c.getParameterTypes();
      for (int i = 0; i < args.length; i++) {
        args[i] = defaultOf(params[i]);
      }
      try {
        c.newInstance(args);
        outcomes.add("built");
      } catch (InvocationTargetException e) {
        outcomes.add(e.getCause().getClass().getSimpleName());
      } catch (ReflectiveOperationException e) {
        outcomes.add(e.getClass().getSimpleName());
      }
    }
    return outcomes;
  }

  /** Whether {@code type}'s constructors all take a first parameter this package cannot name. */
  public static boolean firstParameterUnnamable(Class<?> type) {
    for (Constructor<?> c : type.getConstructors()) {
      if (c.getParameterCount() == 0 || Modifier.isPublic(c.getParameterTypes()[0].getModifiers())) {
        return false;
      }
    }
    return true;
  }

  private static Object defaultOf(Class<?> type) {
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == double.class) return 0.0;
    if (type == float.class) return 0.0f;
    return null;
  }
}
