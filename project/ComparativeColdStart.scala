import java.io.{ BufferedReader, File, InputStreamReader }
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import sbt.util.Logger

/**
 * The cold-start and footprint rows of the comparative benchmarks (#2288, modules/benchmarks-comparative): for each
 * library, `runs` fresh JVMs that each build a client and make one chat call against the mock server, timed from
 * process launch to the reply line; and the jar count and size of the library's own classpath. Writes
 * `coldstart.json`, which `org.llm4s.benchcmp.Report` turns into the results table.
 */
object ComparativeColdStart {

  /** One library: its cold-start main, the classpath it runs on, and the jars counted as its footprint. */
  final case class Library(name: String, version: String, mainClass: String, classpath: Seq[File], footprint: Seq[File])

  def run(
    java: File,
    harnessClasspath: Seq[File],
    libraries: Seq[Library],
    runs: Int,
    out: File,
    log: Logger
  ): File = {
    val server =
      new ProcessBuilder(java.getPath, "-cp", cp(harnessClasspath), "org.llm4s.benchcmp.harness.MockOpenAiServer")
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    val port =
      Option(new BufferedReader(new InputStreamReader(server.getInputStream, StandardCharsets.UTF_8)).readLine())
        .map(_.trim.toInt)
        .getOrElse(sys.error("the mock server exited before printing its port"))
    val baseUrl = s"http://127.0.0.1:$port/t1/v1"
    val results =
      try
        libraries.map { lib =>
          // One untimed launch first, so the OS file cache holds the jars for every timed one.
          launch(java, lib, baseUrl)
          val samples = (1 to runs).map(_ => launch(java, lib, baseUrl))
          log.info(s"${lib.name}: cold start ${samples.sorted.apply(samples.size / 2)} ms (median of $runs)")
          lib -> samples
        }
      finally {
        server.destroy()
        server.waitFor(5, TimeUnit.SECONDS)
      }

    def str(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val libsJson = results.map { case (lib, samples) =>
      val jars = lib.footprint.filter(_.getName.endsWith(".jar"))
      s"""    ${str(lib.name)}: {
         |      "mainClass": ${str(lib.mainClass)},
         |      "coldStartMs": [${samples.mkString(", ")}],
         |      "jars": ${jars.size},
         |      "jarBytes": ${jars.map(_.length).sum},
         |      "jarNames": [${jars.map(j => str(j.getName)).sorted.mkString(", ")}]
         |    }""".stripMargin
    }
    val json =
      s"""{
         |  "runs": $runs,
         |  "java": ${str(java.getPath)},
         |  "javaVersion": ${str(javaVersion(java))},
         |  "os": ${str(s"${sys.props("os.name")} ${sys.props("os.version")} ${sys.props("os.arch")}")},
         |  "cpus": ${Runtime.getRuntime.availableProcessors},
         |  "cpu": ${str(cpuModel)},
         |  "versions": {${libraries.map(l => s"${str(l.name)}: ${str(l.version)}").mkString(", ")}},
         |  "libraries": {
         |${libsJson.mkString(",\n")}
         |  }
         |}
         |""".stripMargin
    Files.createDirectories(out.toPath.getParent)
    Files.write(out.toPath, json.getBytes(StandardCharsets.UTF_8))
    log.info(s"Wrote $out")
    out
  }

  private def cp(files: Seq[File]): String = files.map(_.getAbsolutePath).mkString(File.pathSeparator)

  /** Milliseconds from launching a fresh JVM to reading its `DONE` line. */
  private def launch(java: File, lib: Library, baseUrl: String): Long = {
    val start = System.nanoTime()
    val process = new ProcessBuilder(java.getPath, "-cp", cp(lib.classpath), lib.mainClass, baseUrl)
      .redirectErrorStream(true)
      .start()
    val reader = new BufferedReader(new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
    val lines  = Iterator.continually(reader.readLine()).takeWhile(_ != null)
    val output = new StringBuilder
    val done = lines.exists { line =>
      output.append(line).append('\n')
      line == "DONE"
    }
    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    process.waitFor(30, TimeUnit.SECONDS)
    process.destroyForcibly()
    if (!done) sys.error(s"${lib.name}: the cold-start process did not print DONE:\n$output")
    elapsed
  }

  /** The CPU's model name, from /proc/cpuinfo on Linux or sysctl on macOS; empty when neither answers. */
  private def cpuModel: String = {
    val cpuinfo = new File("/proc/cpuinfo")
    if (cpuinfo.isFile)
      scala.io.Source
        .fromFile(cpuinfo)
        .getLines()
        .collectFirst { case l if l.startsWith("model name") => l.split(":", 2)(1).trim }
        .getOrElse("")
    else
      scala.util
        .Try {
          val p = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start()
          val s = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
          p.waitFor()
          s
        }
        .getOrElse("")
  }

  private def javaVersion(java: File): String = {
    val p    = new ProcessBuilder(java.getPath, "-version").redirectErrorStream(true).start()
    val text = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    p.waitFor()
    text.linesIterator.mkString(" / ")
  }
}
