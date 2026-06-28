package org.llm4s.samples.dashboard.repro

import termflow.tui.TuiRuntime

/**
 * Run with:
 * `sbt "samples/runMain org.llm4s.samples.dashboard.repro.ProviderChatRenderReproMain"`
 */

@main
def ProviderChatRenderReproMain(): Unit =
  TuiRuntime.run(ProviderChatRenderReproApp.App)
