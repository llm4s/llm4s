package org.llm4s.samples.dashboard.providersetup

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.Executors
import scala.util.Try

private[providersetup] object ProviderSetupDebugLog:

  private val writer = Executors.newSingleThreadExecutor { runnable =>
    val thread = new Thread(runnable, "provider-setup-debug-log-writer")
    thread.setDaemon(true)
    thread
  }

  def append(config: ProviderSetupDebugLogConfig, section: String, lines: List[String]): Try[Unit] =
    if config.enabled then submitWrite(config.path, renderPayload(section, lines))
    else Try(())

  private def submitWrite(path: String, payload: String): Try[Unit] =
    Try:
      writer.submit(
        new Runnable:
          override def run(): Unit =
            appendNow(Path.of(path), payload).toOption
      ): Unit

  private def appendNow(path: Path, payload: String): Try[Unit] =
    for
      _ <- ensureParentDirectories(path)
      _ <- Try:
        Files.write(
          path,
          payload.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
        ): Unit
    yield ()

  private def ensureParentDirectories(path: Path): Try[Unit] =
    Try:
      Option(path.getParent).foreach(Files.createDirectories(_))

  private def renderPayload(section: String, lines: List[String]): String =
    (List(
      s"===== ${Instant.now()} | $section ====="
    ) ++ lines ++ List("")).mkString(System.lineSeparator())
