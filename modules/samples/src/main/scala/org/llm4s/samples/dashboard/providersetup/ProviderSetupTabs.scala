package org.llm4s.samples.dashboard.providersetup

import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import termflow.tui.KeyDecoder
import termflow.tui.PromptHistory

import scala.annotation.unused

object ProviderSetupTabs:

  final case class SetupTabSpec(
    id: SetupTabId,
    doc: ProviderDoc,
    bodyPanels: Vector[PanelFocus]
  ):
    def defaultPanel: PanelFocus =
      bodyPanels.headOption.getOrElse(PanelFocus.Main)

  val setupTabSpecs: Vector[SetupTabSpec] = Vector(
    SetupTabSpec(SetupTabId.Overview, ProviderSetupContent.docs(0), Vector(PanelFocus.Main, PanelFocus.Status)),
    SetupTabSpec(
      SetupTabId.DefaultNamedProvider,
      ProviderSetupContent.docs(1),
      Vector(PanelFocus.Main, PanelFocus.Models, PanelFocus.Status)
    ),
    SetupTabSpec(
      SetupTabId.Providers,
      ProviderSetupContent.docs(2),
      Vector(PanelFocus.Main, PanelFocus.Models, PanelFocus.Status)
    ),
    SetupTabSpec(
      SetupTabId.Compare,
      ProviderSetupContent.docs(3),
      Vector(PanelFocus.Main, PanelFocus.Models, PanelFocus.Status)
    ),
    SetupTabSpec(SetupTabId.ConfigPaths, ProviderSetupContent.docs(4), Vector(PanelFocus.Main, PanelFocus.Status)),
    SetupTabSpec(SetupTabId.Status, ProviderSetupContent.docs(5), Vector(PanelFocus.Main, PanelFocus.Status))
  )

  val setupTabsById: Map[SetupTabId, SetupTabSpec] =
    setupTabSpecs.map(spec => spec.id -> spec).toMap

  val setupTabs: Vector[SetupTabId] =
    setupTabSpecs.map(_.id)

  def activeSetupTab(model: Model): SetupTabId =
    model.activeTab

  def activeSetupDoc(model: Model): ProviderDoc =
    setupTabsById(activeSetupTab(model)).doc

  def setupDoc(tabId: SetupTabId): ProviderDoc =
    setupTabsById(tabId).doc

  def setupTabSpec(tabId: SetupTabId): SetupTabSpec =
    setupTabsById(tabId)

  def setupTabIndex(tabId: SetupTabId): Int =
    setupTabs.indexOf(tabId)

  def setupTabAt(index: Int): SetupTabId =
    setupTabs(index)

  def setupTabCount: Int =
    setupTabs.length

  def tabTitle(model: Model, tabId: SetupTabId): String =
    tabId match
      case SetupTabId.DefaultNamedProvider =>
        ProviderSetupProviderSelection
          .defaultConfiguredProvider(model.configStatus)
          .map(_.name)
          .getOrElse(setupDoc(tabId).title)
      case _ =>
        setupDoc(tabId).title

  def selectTabTarget(model: Model, rawTarget: String): Model =
    val target = rawTarget.trim.toLowerCase
    ProviderSetupContent.docs.indexWhere(doc => doc.id.value == target || doc.title.toLowerCase == target) match
      case idx if idx >= 0 =>
        selectTab(model, setupTabAt(idx))
      case _ =>
        ProviderSetupContent.providerDocs.indexWhere(doc =>
          doc.id.value == target || doc.title.toLowerCase == target
        ) match
          case -1 =>
            model.copy(statusLine = s"Unknown tab: $rawTarget")
          case providerIdx =>
            selectTab(model, SetupTabId.Providers)
              .updateSetup(_.copy(selectedProviderIndex = providerIdx))
              .copy(statusLine = s"Selected ${ProviderSetupContent.providerDocs(providerIdx).title} in Providers.")

  def selectTab(model: Model, tabId: SetupTabId): Model =
    val nextIndex = setupTabIndex(tabId)
    ProviderSetupSetupPolicy
      .resetProvidersPanel(model)
      .updateShell(
        _.copy(
          activeTab = tabId,
          focusTarget = FocusTarget.TabBar,
          panelFocus = defaultPanelForTab(nextIndex)
        )
      )
      .copy(statusLine = s"Selected ${tabTitle(model, tabId)}.")

  def enterActiveTabBody(model: Model): Model =
    model
      .updateShell(
        _.copy(
          focusTarget = FocusTarget.Body,
          panelFocus = defaultPanelForTab(setupTabIndex(model.activeTab))
        )
      )
      .copy(statusLine = s"Entered ${tabTitle(model, model.activeTab)} body. Press Esc to return to tabs.")

  def shouldHandleNavKeys(prompt: PromptHistory.State, key: KeyDecoder.InputKey): Boolean =
    prompt.prompt.buffer.isEmpty &&
      (key == KeyDecoder.InputKey.ArrowLeft ||
        key == KeyDecoder.InputKey.ArrowRight ||
        key == KeyDecoder.InputKey.ArrowUp ||
        key == KeyDecoder.InputKey.ArrowDown ||
        key == KeyDecoder.InputKey.Escape ||
        key == KeyDecoder.InputKey.Home ||
        key == KeyDecoder.InputKey.End ||
        (key match
          case KeyDecoder.InputKey.CharKey(digit) if digit.isDigit => true
          case _                                                   => false
        ))

  def handleNavKey(model: Model, key: KeyDecoder.InputKey, @unused docs: Vector[ProviderDoc]): Model =
    model.focusTarget match
      case FocusTarget.TabBar =>
        key match
          case KeyDecoder.InputKey.ArrowLeft =>
            val currentIndex = setupTabIndex(model.activeTab)
            val nextIndex    = if currentIndex == 0 then setupTabCount - 1 else currentIndex - 1
            selectTab(model, setupTabAt(nextIndex))

          case KeyDecoder.InputKey.ArrowRight =>
            val currentIndex = setupTabIndex(model.activeTab)
            val nextIndex    = (currentIndex + 1) % setupTabCount
            selectTab(model, setupTabAt(nextIndex))

          case KeyDecoder.InputKey.ArrowDown =>
            enterActiveTabBody(model)

          case KeyDecoder.InputKey.Home =>
            selectTab(model, SetupTabId.Overview)

          case KeyDecoder.InputKey.End =>
            selectTab(model, setupTabAt(setupTabCount - 1))

          case KeyDecoder.InputKey.CharKey(digit) if digit.isDigit =>
            digit.asDigit - 1 match
              case idx if idx >= 0 && idx < setupTabCount =>
                selectTab(model, setupTabAt(idx))
              case _ =>
                model

          case _ =>
            model

      case FocusTarget.Body =>
        key match
          case KeyDecoder.InputKey.Escape =>
            ProviderSetupSetupPolicy.exitBodyFocus(model)

          case KeyDecoder.InputKey.ArrowLeft =>
            cycleBodyPanel(model, -1)

          case KeyDecoder.InputKey.ArrowRight =>
            cycleBodyPanel(model, 1)

          case KeyDecoder.InputKey.ArrowUp =>
            ProviderSetupSetupPolicy.moveBodySelection(model, -1)

          case KeyDecoder.InputKey.ArrowDown =>
            ProviderSetupSetupPolicy.moveBodySelection(model, 1)

          case _ =>
            model

  def defaultPanelForTab(tabIndex: Int): PanelFocus =
    setupTabSpec(setupTabAt(tabIndex)).defaultPanel

  def focusLabel(focus: PanelFocus): String = focus match
    case PanelFocus.Main   => "Help"
    case PanelFocus.Models => "Models"
    case PanelFocus.Status => "Status"

  def cycleBodyPanel(model: Model, direction: Int): Model =
    val orderedPanels = setupTabSpec(activeSetupTab(model)).bodyPanels

    val currentIndex = orderedPanels.indexOf(model.panelFocus) match
      case -1  => 0
      case idx => idx
    val nextIndex = Math.floorMod(currentIndex + direction, orderedPanels.length)
    val nextFocus = orderedPanels(nextIndex)
    model
      .updateShell(_.copy(panelFocus = nextFocus))
      .copy(statusLine = s"Focus: ${focusLabel(nextFocus)} panel. Press Esc to return to tabs.")

  def focusPanel(model: Model, panel: PanelFocus): Model =
    val allowedPanels = setupTabSpec(activeSetupTab(model)).bodyPanels
    if allowedPanels.contains(panel) then
      model
        .updateShell(_.copy(focusTarget = FocusTarget.Body, panelFocus = panel))
        .copy(statusLine = s"Focus: ${focusLabel(panel)} panel.")
    else
      model.copy(
        statusLine = s"${tabTitle(model, model.activeTab)} does not use the ${focusLabel(panel).toLowerCase} panel."
      )
