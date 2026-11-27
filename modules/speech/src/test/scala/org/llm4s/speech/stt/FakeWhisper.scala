package org.llm4s.speech.stt

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }

/**
 * A stand-in for the openai-whisper program, as a single-file Java source run by this JVM's `java`, so
 * it behaves the same on every platform. Like openai-whisper it writes `<input stem>.<format>` into
 * `--output_dir` and prints progress to stdout; unlike it, it fails (exit 2) when `--output_dir` or
 * `--verbose False` is missing, or when `--language` is not a bare language code, as argparse's
 * `choices` would.
 *
 * `command(mode, record)` puts `--fake-mode=<mode>` and `--fake-record=<file>` before the engine's own
 * arguments: `ok`, `fail` (exit 3 with a traceback on stderr) or `no-output` (exit 0, nothing written);
 * the record file receives the arguments, one per line.
 */
object FakeWhisper {

  private val Source: String =
    """import java.nio.charset.StandardCharsets;
      |import java.nio.file.*;
      |import java.util.*;
      |
      |public class FakeWhisper {
      |  public static void main(String[] args) throws Exception {
      |    String mode = "ok"; String record = null; String input = null;
      |    Map<String, String> flags = new HashMap<>();
      |    for (int i = 0; i < args.length; i++) {
      |      String a = args[i];
      |      if (a.startsWith("--fake-mode=")) mode = a.substring(12);
      |      else if (a.startsWith("--fake-record=")) record = a.substring(14);
      |      else if (a.startsWith("--") && i + 1 < args.length) { flags.put(a, args[i + 1]); i++; }
      |      else if (input == null) input = a;
      |    }
      |    if (record != null) Files.write(Paths.get(record), Arrays.asList(args), StandardCharsets.UTF_8);
      |    System.out.println("Detecting language using up to the first 30 seconds. Use `--language` to specify the language");
      |    System.out.println("Detected language: English");
      |    System.out.println("[00:00.000 --> 00:02.000]  Hello from the fake");
      |    if (mode.equals("fail")) {
      |      System.err.println("Traceback (most recent call last):");
      |      System.err.println("RuntimeError: the fake model exploded");
      |      System.exit(3);
      |    }
      |    String outDir = flags.get("--output_dir");
      |    if (outDir == null) { System.err.println("whisper: error: the fake needs --output_dir"); System.exit(2); }
      |    if (!"False".equals(flags.get("--verbose"))) { System.err.println("whisper: error: the fake needs --verbose False"); System.exit(2); }
      |    String lang = flags.get("--language");
      |    if (lang != null && !lang.matches("[a-z]{2,3}")) {
      |      System.err.println("whisper: error: argument --language: invalid choice: '" + lang + "'");
      |      System.exit(2);
      |    }
      |    if (mode.equals("no-output")) System.exit(0);
      |    String name = Paths.get(input).getFileName().toString();
      |    String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
      |    String format = flags.getOrDefault("--output_format", "all");
      |    String body = format.equals("json")
      |      ? "{\"text\": \" Hello world\", \"language\": \"en\", \"segments\": [{\"words\": [" +
      |        "{\"word\": \" Hello\", \"start\": 0.0, \"end\": 0.5, \"probability\": 0.9}," +
      |        "{\"word\": \" world\", \"start\": 0.5, \"end\": 1.0, \"probability\": 0.8}]}]}"
      |      : "Hello from the fake\n";
      |    Files.write(Paths.get(outDir).resolve(stem + "." + format), body.getBytes(StandardCharsets.UTF_8));
      |  }
      |}
      |""".stripMargin

  private lazy val sourceFile: Path = {
    val dir  = Files.createTempDirectory("llm4s-fake-whisper-")
    val file = dir.resolve("FakeWhisper.java")
    Files.write(file, Source.getBytes(StandardCharsets.UTF_8))
    dir.toFile.deleteOnExit()
    file.toFile.deleteOnExit()
    file
  }

  private def javaBinary: String =
    ProcessHandle
      .current()
      .info()
      .command()
      .orElse(Paths.get(System.getProperty("java.home"), "bin", "java").toString)

  def command(mode: String = "ok", record: Option[Path] = None): Seq[String] =
    Seq(javaBinary, sourceFile.toString, s"--fake-mode=$mode") ++ record.map(r => s"--fake-record=$r").toSeq
}
