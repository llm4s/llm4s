package org.llm4s.examples.http

import cats.effect.{ IO, Resource }
import org.llm4s.effect.cats.LLMClientIO
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.UserMessage
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

/**
 * A reference app's command line: `[--host H] [--port N] [--fake]`.
 *
 * The model is not chosen here: it is the named provider section `llm4s.providers.provider` names in
 * `application.conf`, loaded through `Llm4sConfig` when the app starts (`LLMClientIO.resource`). `--fake` serves
 * [[ServerSettings.fakeClient]] instead, so the app runs with no model server and no API key.
 */
final case class ServerSettings(host: String = "127.0.0.1", port: Int = 8080, fake: Boolean = false) {

  /** The client the app serves: the configured provider, or the fake one. */
  def client: Resource[IO, LLMClientIO[IO]] =
    if (fake) Resource.pure(LLMClientIO[IO](ServerSettings.fakeClient)) else LLMClientIO.resource[IO]
}

object ServerSettings {

  val Usage: String = "usage: [--host <address>] [--port <0-65535>] [--fake]"

  /** The settings `args` give, or a message naming the argument that is wrong. */
  def parse(args: List[String]): Either[String, ServerSettings] = {
    @scala.annotation.tailrec
    def loop(rest: List[String], settings: ServerSettings): Either[String, ServerSettings] = rest match {
      case Nil                      => Right(settings)
      case "--fake" :: tail         => loop(tail, settings.copy(fake = true))
      case "--host" :: host :: tail => loop(tail, settings.copy(host = host))
      case "--port" :: port :: tail =>
        port.toIntOption.filter(p => p >= 0 && p <= 65535) match {
          case Some(p) => loop(tail, settings.copy(port = p))
          case None    => Left(s"--port must be a number from 0 to 65535, not '$port'. $Usage")
        }
      case other :: _ => Left(s"unknown or incomplete argument '$other'. $Usage")
    }
    loop(args, ServerSettings())
  }

  /** A client that answers every message with "You said: <message>", streamed a few characters at a time. */
  def fakeClient: LLMClient =
    ScriptedLLMClient
      .respondingTo { case conversation =>
        val said = conversation.messages.reverseIterator.collectFirst { case m: UserMessage => m.content }
        Reply.text(s"You said: ${said.getOrElse("")}")
      }
      .withChunkSize(4)
}
