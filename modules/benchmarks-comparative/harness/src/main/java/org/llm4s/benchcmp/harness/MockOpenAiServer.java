package org.llm4s.benchcmp.harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local OpenAI-compatible {@code /chat/completions} server with canned replies and no artificial delay, so that the
 * model is never the variable: what a benchmark measures is the client library plus loopback HTTP, and the loopback
 * part is the same for every library.
 *
 * <p>Every path ending in {@code /chat/completions} is served. A {@code /t<N>/} segment in the path sets how many
 * content chunks a streamed reply has (default 1), so a client whose base URL is {@code http://host:port/t500/v1}
 * streams 500 tokens. A request that declares {@code tools} is answered with one {@code lookup} tool call until it
 * carries {@link #TOOL_ROUNDS} tool results, then with a final answer: a 3-round tool loop in 4 HTTP calls.
 *
 * <p>JDK only ({@code com.sun.net.httpserver}), one virtual thread per exchange.
 */
public final class MockOpenAiServer implements AutoCloseable {

  /** Tool rounds a tool-calling request gets before the final answer. */
  public static final int TOOL_ROUNDS = 3;

  /** The reply text of a non-streamed completion and of the tool loop's final answer. */
  public static final String REPLY = "Hello from the mock server.";

  private static final Pattern TOKENS_SEGMENT = Pattern.compile("/t(\\d+)/");
  private static final Pattern TOOL_MESSAGE = Pattern.compile("\"role\"\\s*:\\s*\"tool\"");
  private static final Pattern STREAM_TRUE = Pattern.compile("\"stream\"\\s*:\\s*true");
  private static final Pattern TOOLS_KEY = Pattern.compile("\"tools\"\\s*:\\s*\\[\\s*\\{"); // a non-empty tools array

  private static final String USAGE = "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}";

  private static final byte[] COMPLETION = json(
      "{\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\"bench-model\","
          + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + REPLY + "\"},"
          + "\"finish_reason\":\"stop\"}]," + USAGE + "}");

  private static final byte[][] TOOL_CALLS = new byte[TOOL_ROUNDS][];

  static {
    for (int i = 0; i < TOOL_ROUNDS; i++) {
      TOOL_CALLS[i] = json(
          "{\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\"bench-model\","
              + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
              + "{\"id\":\"call_" + i + "\",\"type\":\"function\",\"function\":{\"name\":\"lookup\","
              + "\"arguments\":\"{\\\"key\\\":\\\"k" + i + "\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],"
              + USAGE + "}");
    }
  }

  private final HttpServer server;
  private final ExecutorService executor;
  private final Map<Integer, List<byte[]>> streams = new ConcurrentHashMap<>();

  private MockOpenAiServer(HttpServer server, ExecutorService executor) {
    this.server = server;
    this.executor = executor;
  }

  /** Starts a server on a free loopback port. */
  public static MockOpenAiServer start() throws IOException {
    return start(0);
  }

  /** Starts a server on {@code port} of the loopback address; 0 picks a free port. */
  public static MockOpenAiServer start(int port) throws IOException {
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 1024);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    MockOpenAiServer mock = new MockOpenAiServer(http, executor);
    http.createContext("/", mock::handle);
    http.setExecutor(executor);
    http.start();
    return mock;
  }

  /** The port the server listens on. */
  public int port() {
    return server.getAddress().getPort();
  }

  /** An OpenAI-style base URL (ending in {@code /v1}) whose streamed replies have {@code tokens} chunks. */
  public String baseUrl(int tokens) {
    return baseUrl(port(), tokens);
  }

  /** The base URL of a server on loopback {@code port} whose streamed replies have {@code tokens} chunks. */
  public static String baseUrl(int port, int tokens) {
    return "http://127.0.0.1:" + port + "/t" + tokens + "/v1";
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String body;
      try (InputStream in = exchange.getRequestBody()) {
        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      String path = exchange.getRequestURI().getPath();
      if (!"POST".equals(exchange.getRequestMethod()) || !path.endsWith("/chat/completions")) {
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      if (TOOLS_KEY.matcher(body).find()) {
        int toolResults = count(TOOL_MESSAGE, body);
        if (toolResults < TOOL_ROUNDS) {
          send(exchange, TOOL_CALLS[toolResults]);
          return;
        }
      }
      if (STREAM_TRUE.matcher(body).find()) {
        stream(exchange, tokens(path), body.contains("include_usage"));
      } else {
        send(exchange, COMPLETION);
      }
    }
  }

  private static void send(HttpExchange exchange, byte[] json) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, json.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(json);
    }
  }

  private void stream(HttpExchange exchange, int tokens, boolean usage) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
    exchange.sendResponseHeaders(200, 0); // chunked
    try (OutputStream out = exchange.getResponseBody()) {
      for (byte[] event : streams.computeIfAbsent(tokens, MockOpenAiServer::events)) {
        out.write(event);
        out.flush(); // one network write per event, as a real server streams them
      }
      if (usage) {
        out.write(event("{\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
            + "\"model\":\"bench-model\",\"choices\":[]," + USAGE + "}"));
      }
      out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
    }
  }

  private static List<byte[]> events(int tokens) {
    List<byte[]> events = new ArrayList<>(tokens + 1);
    for (int i = 0; i < tokens; i++) {
      String role = i == 0 ? "\"role\":\"assistant\"," : "";
      events.add(event("{\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
          + "\"model\":\"bench-model\",\"choices\":[{\"index\":0,\"delta\":{" + role + "\"content\":\"tok" + i
          + " \"},\"finish_reason\":null}]}"));
    }
    events.add(event("{\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion.chunk\",\"created\":1700000000,"
        + "\"model\":\"bench-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"));
    return events;
  }

  private static int tokens(String path) {
    Matcher m = TOKENS_SEGMENT.matcher(path);
    return m.find() ? Math.max(1, Integer.parseInt(m.group(1))) : 1;
  }

  private static int count(Pattern pattern, String text) {
    Matcher m = pattern.matcher(text);
    int n = 0;
    while (m.find()) n++;
    return n;
  }

  private static byte[] event(String json) {
    return ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] json(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  /** Runs a server until the process is stopped; prints its port on the first line of stdout. */
  public static void main(String[] args) throws Exception {
    MockOpenAiServer mock = start(args.length > 0 ? Integer.parseInt(args[0]) : 0);
    System.out.println(mock.port());
    System.out.flush();
    Thread.currentThread().join();
  }
}
