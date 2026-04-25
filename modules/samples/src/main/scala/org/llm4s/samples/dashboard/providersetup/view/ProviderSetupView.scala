package org.llm4s.samples.dashboard.providersetup.view

import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import termflow.tui.*

private[providersetup] object ProviderSetupView:
  import ProviderSetupRenderSupport.*

  def render(model: Model): RootNode =
    model.screenMode match
      case ScreenMode.Setup   => ProviderSetupSetupView.render(model)
      case ScreenMode.Demo    => ProviderSetupDemoView.render(model)
      case ScreenMode.Compare => ProviderSetupCompareView.render(model)

  private[view] def compareRenderedRowCount(model: Model, width: Int): Int =
    renderCompareResultRows(model, width).length

  def compareRenderedMaxScrollOffset(model: Model): Int =
    math.max(0, compareRenderedRowCount(model, compareResultWidth(model)) - comparePanelCapacity(model))
