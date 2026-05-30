package org.llm4s.samples.chat.tui

import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import termflow.tui.TuiRuntime

import scala.concurrent.ExecutionContext

/**
 * Streaming TermFlow chat client. Loads provider config from the
 * environment via `Llm4sConfig.defaultProvider()`, registers a single
 * `read_file` tool, and hands off to `TuiRuntime`.
 *
 * Run with:
 * `sbt "samples/runMain org.llm4s.samples.chat.tui.ChatTuiMain"`
 * or `sbt chatTuiDemo`.
 */
@main
def ChatTuiMain(): Unit =
  given ExecutionContext = ExecutionContext.global

  val outcome =
    for {
      config   <- ChatTuiConfig.load()
      readFile <- ChatTuiTool.tool(config.workspaceRoot)
      registry = new ToolRegistry(Seq(readFile))
      client <- LLMConnect.getClient(config.providerConfig)(using config.modelRegistry)
    } yield {
      val app = new ChatTuiApp(config, client, registry)
      TuiRuntime.run(app)
    }

  outcome.fold(
    err => {
      System.err.println(err.formatted)
      sys.exit(1)
    },
    identity
  )
