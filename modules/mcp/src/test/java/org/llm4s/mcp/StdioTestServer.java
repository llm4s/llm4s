package org.llm4s.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A scripted MCP stdio server for the transport specs: plain JDK, so a spec launches it with
 * {@code java -cp <test-classes> org.llm4s.mcp.StdioTestServer ...} on every OS, where it used to need {@code bash}.
 * It must stay free of Scala and other libraries: its classpath is this class's directory alone, which keeps the
 * command line short and the quoting trivial on Windows.
 *
 * <p>Arguments (none contains a quote, so no platform's argument parsing can mangle them):
 * <ul>
 *   <li>{@code method:BASE64URL} - answer each request whose method is {@code method} (or any, for {@code any}) with
 *       the decoded reply, in which {@code $id} is the request's id. A request of another method, or without an
 *       id (a notification), is not answered.</li>
 *   <li>{@code --ready} - write a line to stderr before reading anything.</li>
 *   <li>{@code --delay=N} - sleep a random 0 to N-1 ms before each reply.</li>
 *   <li>{@code --pidfile=PATH} - write this process's id to PATH first.</li>
 *   <li>{@code --gotfile=PATH} - write to PATH once a request line has been read.</li>
 *   <li>{@code --hello} - write a line to stdout before reading anything.</li>
 *   <li>{@code --hang} - after the above, sleep for 30 seconds instead of answering.</li>
 *   <li>{@code --exit-after-read} - read one line, then exit 0.</li>
 *   <li>{@code --stderr-flood} - read one line, write about 3 KB of JSON with a secret to stderr, exit 1.</li>
 *   <li>{@code --stderr-kb=N} - on the first request, write N KB to stderr in 100-byte lines, ending with
 *       {@code last-words-marker}, before replying. The transport must read stderr while the request is in flight,
 *       or this blocks once the pipe is full.</li>
 *   <li>{@code --die-after-stderr} - with {@code --stderr-kb}, exit 1 after that write instead of replying.</li>
 *   <li>{@code --stderr-secret-line} - on the first request, write one line of about 6 KB to stderr whose
 *       {@code "api_key":"} key ends at the 4096th character, so a read of 4096 ends between the key and its value;
 *       then exit 1.</li>
 *   <li>{@code --stderr-endless} - on the first request, write 1 MB to stderr with no newline, starting
 *       with a {@code "api_key":"} value and ending {@code tail-SENTINEL}, then exit 1.</li>
 *   <li>{@code --exit=N} - exit N at once.</li>
 * </ul>
 * Lines are read with {@code readLine}, so CRLF and LF both work; replies end in {@code \n}.
 */
public final class StdioTestServer {
  private StdioTestServer() {}

  private static final Pattern ID = Pattern.compile("\"id\":\"([^\"]*)\"");
  private static final Pattern METHOD = Pattern.compile("\"method\":\"([^\"]*)\"");

  public static void main(String[] args) throws Exception {
    Map<String, String> replies = new LinkedHashMap<>();
    boolean ready = false, hello = false, hang = false, exitAfterRead = false, flood = false;
    int delay = 0, stderrKb = 0;
    boolean dieAfterStderr = false, secretLine = false, endless = false;
    String pidFile = null, gotFile = null;
    for (String a : args) {
      if (a.equals("--ready")) ready = true;
      else if (a.equals("--hello")) hello = true;
      else if (a.equals("--hang")) hang = true;
      else if (a.equals("--exit-after-read")) exitAfterRead = true;
      else if (a.equals("--stderr-flood")) flood = true;
      else if (a.equals("--die-after-stderr")) dieAfterStderr = true;
      else if (a.equals("--stderr-secret-line")) secretLine = true;
      else if (a.equals("--stderr-endless")) endless = true;
      else if (a.startsWith("--stderr-kb=")) stderrKb = Integer.parseInt(a.substring(12));
      else if (a.startsWith("--delay=")) delay = Integer.parseInt(a.substring(8));
      else if (a.startsWith("--pidfile=")) pidFile = a.substring(10);
      else if (a.startsWith("--gotfile=")) gotFile = a.substring(10);
      else if (a.startsWith("--exit=")) System.exit(Integer.parseInt(a.substring(7)));
      else {
        int colon = a.indexOf(':');
        replies.put(
            a.substring(0, colon),
            new String(Base64.getUrlDecoder().decode(a.substring(colon + 1)), StandardCharsets.UTF_8));
      }
    }
    if (pidFile != null) writeFile(pidFile, Long.toString(ProcessHandle.current().pid()));
    if (ready) {
      System.err.println("ready");
      System.err.flush();
    }
    PrintStream out = new PrintStream(System.out, false, "UTF-8");
    if (hello) {
      out.print("ready\n");
      out.flush();
    }
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    if (exitAfterRead || flood || hang) {
      String line = in.readLine();
      if (line == null) return;
      if (gotFile != null) writeFile(gotFile, "got");
      if (flood) {
        // Over PayloadLog.MaxChars (2048) so the cut is exercised, yet under a Windows anonymous pipe's 4096-byte
        // buffer: the transport does not read stderr while a request is in flight, and a bigger write would block
        // here before the exit, so the spec would end on the response timeout instead of the process exit.
        // Assembled at run time: the transport logs its command line, and the secret must not be in it.
        String k = "hunter2-SENT";
        System.err.print("{\"api_key\":\"" + k + "INEL\",\"x\":\"" + "x".repeat(3000) + "\"}\n");
        System.err.flush();
        System.exit(1);
      }
      if (hang) Thread.sleep(30_000);
      return;
    }
    String line;
    boolean stderrWritten = false;
    while ((line = in.readLine()) != null) {
      Matcher id = ID.matcher(line);
      if (!id.find()) continue;
      if (secretLine || endless) {
        // Assembled at run time: the transport logs its command line, and the secret must not be in it.
        String key = "\"api_key\":\"";
        String secret = "hunter2-SENT" + "INEL";
        if (secretLine) {
          System.err.print("x".repeat(4096 - key.length()) + key);
          System.err.flush();
          System.err.print(secret + "\",\"y\":\"" + "y".repeat(2000) + "\"}\n");
        } else {
          System.err.print(key + secret + "\"");
          for (int i = 0; i < 1024; i++) System.err.print("z".repeat(1024));
          System.err.print("tail-SENTINEL");
        }
        System.err.flush();
        System.exit(1);
      }
      if (stderrKb > 0 && !stderrWritten) {
        stderrWritten = true;
        String filler = "x".repeat(88);
        for (int i = 0; i < stderrKb * 10; i++) {
          System.err.print(String.format("%08d ", i) + filler + "\n");
        }
        System.err.print("last-words-marker\n");
        System.err.flush();
        if (dieAfterStderr) System.exit(1);
      }
      Matcher method = METHOD.matcher(line);
      String m = method.find() ? method.group(1) : "";
      String reply = replies.containsKey(m) ? replies.get(m) : replies.get("any");
      if (reply == null) continue;
      if (delay > 0) Thread.sleep(ThreadLocalRandom.current().nextInt(delay));
      out.print(reply.replace("$id", id.group(1)) + "\n");
      out.flush();
    }
  }

  private static void writeFile(String path, String content) throws IOException {
    Files.write(Paths.get(path), content.getBytes(StandardCharsets.UTF_8));
  }
}
