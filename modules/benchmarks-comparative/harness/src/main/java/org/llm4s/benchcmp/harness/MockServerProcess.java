package org.llm4s.benchcmp.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link MockOpenAiServer} in a JVM of its own, so that a benchmark's CPU time and its allocations
 * ({@code -prof gc}) are the client library's alone, never the server's.
 */
public final class MockServerProcess implements AutoCloseable {

  private final Process process;
  private final int port;

  private MockServerProcess(Process process, int port) {
    this.process = process;
    this.port = port;
  }

  /** Starts the server with this JVM's {@code java} and class path and waits for it to print its port. */
  public static MockServerProcess start() throws IOException {
    return start(System.getProperty("java.class.path"));
  }

  /** Starts the server with this JVM's {@code java} on {@code classPath}. */
  public static MockServerProcess start(String classPath) throws IOException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    // The JDK server closes idle connections beyond 200 by default; the concurrency scenario keeps 200 open per client.
    Process process = new ProcessBuilder(List.of(java, "-Dsun.net.httpserver.maxIdleConnections=1024", "-cp",
        classPath, MockOpenAiServer.class.getName()))
        // Not INHERIT: a server outliving a failed benchmark would hold the fork's stderr open and JMH would wait on it.
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
    // A failed setup skips tear-down; the server must still go when this JVM does.
    Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
    BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    String line = out.readLine();
    if (line == null) {
      process.destroyForcibly();
      throw new IOException("the mock server exited before printing its port");
    }
    return new MockServerProcess(process, Integer.parseInt(line.trim()));
  }

  public int port() {
    return port;
  }

  /** See {@link MockOpenAiServer#baseUrl(int, int)}. */
  public String baseUrl(int tokens) {
    return MockOpenAiServer.baseUrl(port, tokens);
  }

  @Override
  public void close() throws InterruptedException {
    process.destroy();
    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
  }
}
