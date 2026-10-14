package org.llm4s.mcp

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }
import java.util.Base64

/**
 * Commands that launch [[StdioTestServer]] (a JDK-only main in the test sources) with the JVM running the tests, so a
 * stdio spec needs no POSIX shell and runs on Windows too. The classpath is the server class's own directory, not the
 * test classpath: it holds nothing else, so it cannot grow past Windows' command-line limit, and [[ProcessBuilder]]
 * quotes it if it contains spaces.
 */
object StdioTestServers {

  /** `java.home`'s launcher: `java.exe` where that exists (Windows), `java` otherwise. */
  private val java: String = {
    val bin = Paths.get(System.getProperty("java.home"), "bin")
    Seq("java.exe", "java").map(bin.resolve).find(Files.exists(_)).getOrElse(bin.resolve("java")).toString
  }

  private val classpath: String =
    Paths.get(classOf[StdioTestServer].getProtectionDomain.getCodeSource.getLocation.toURI).toString

  /** The command for the server with `args` (see [[StdioTestServer]]). */
  def command(args: String*): Seq[String] =
    Seq(java, "-XX:TieredStopAtLevel=1", "-cp", classpath, classOf[StdioTestServer].getName) ++ args

  /** `method:reply` as the server takes it: base64, so no quote reaches a Windows command line. */
  def reply(method: String, reply: String): String =
    method + ":" + Base64.getUrlEncoder.withoutPadding.encodeToString(reply.getBytes(StandardCharsets.UTF_8))

  /** A server that answers every request with `reply`, `$id` standing for the request's id. */
  def echo(reply: String, options: String*): Seq[String] = command(options :+ this.reply("any", reply): _*)
}
