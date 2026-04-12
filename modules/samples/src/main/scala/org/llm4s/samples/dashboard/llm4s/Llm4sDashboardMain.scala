package org.llm4s.samples.dashboard.llm4s

import org.llm4s.config.Llm4sConfig
import termflow.tui.TuiRuntime

/**
 * Small dashboard proving that llm4s can consume the published TermFlow artifact.
 *
 * Run with:
 * `sbt "samples/runMain org.llm4s.samples.dashboard.llm4s.Llm4sDashboardMain"`
 */

@main
def Llm4sDashboardMain(): Unit =
  (
    for
      providerCfg <- Llm4sConfig.defaultProvider()
      _ = TuiRuntime.run(Llm4sDashboardApp.App(providerCfg))
    yield ()
  ).fold(
    error =>
      System.err.println(error.formatted)
      sys.exit(1)
    ,
    identity
  )
