package org.llm4s.samples.dashboard.providersetup

import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*

object ProviderSetupModeTransitions:

  def enterSetupMode(model: Model, statusLine: String): Model =
    model
      .updateShell(
        _.copy(
          screenMode = ScreenMode.Setup,
          focusTarget = FocusTarget.TabBar,
          panelFocus = PanelFocus.Main
        )
      )
      .updateSetup(_.copy(providersPanelMode = ProvidersPanelMode.Providers, highlightedModel = None))
      .updateDemo(_.copy(scrollOffset = 0, pending = false))
      .copy(
        statusLine = statusLine
      )

  def enterCompareMode(
    model: Model,
    initialResults: Vector[CompareResult],
    statusLine: String
  ): Model =
    model
      .updateShell(_.copy(screenMode = ScreenMode.Compare))
      .updateCompare(
        _.copy(
          activeTab = 0,
          results = initialResults,
          prompt = None,
          scrollOffsets = Vector.fill(initialResults.size)(0)
        )
      )
      .copy(
        statusLine = statusLine
      )

  def enterDemoMode(
    model: Model,
    session: ActiveSession,
    statusLine: String
  ): Model =
    model
      .updateShell(_.copy(screenMode = ScreenMode.Demo))
      .updateDemo(
        _.copy(
          activeSession = Some(session),
          entries = Vector.empty,
          scrollOffset = 0,
          pending = false
        )
      )
      .copy(
        statusLine = statusLine
      )
