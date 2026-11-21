package org.llm4s.benchcmp.llm4s

import org.llm4s.llmconnect.model.{ Conversation, UserMessage }

/**
 * The cold-start scenario: a fresh JVM builds a client and makes one chat call. Uses only llm4s (`Llm4sLibrary.client`
 * is the same three calls), never the harness's server, so the process loads what a minimal chat app would. Prints
 * `DONE` once the reply is in, then exits.
 */
object Llm4sColdStart:
  def main(args: Array[String]): Unit =
    val reply = for
      client     <- Llm4sLibrary.client(args(0))
      completion <- client.complete(Conversation(Seq(UserMessage("Hello"))))
    yield completion.content
    println(if reply.exists(_.nonEmpty) then "DONE" else s"FAILED $reply")
    System.out.flush()
    System.exit(0)
