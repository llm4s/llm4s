package org.llm4s.samples.dashboard.providersetup.view

import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.SetupTabDocIds
import org.llm4s.samples.dashboard.providersetup.{
  ProviderSetupCompare,
  ProviderSetupContent,
  ProviderSetupProviderSelection,
  ProviderSetupSetupPolicy
}
import org.llm4s.samples.dashboard.providersetup.ProviderSetupTabs

import scala.annotation.unused

private[providersetup] object ProviderSetupSetupViewSupport:
  import ProviderSetupRenderSupport.*

  def renderPrimaryPanel(
    tabDoc: ProviderDoc,
    doc: ProviderDoc,
    width: Int,
    status: ConfigStatus,
    chosenModel: Option[String]
  ): List[String] =
    if isDefaultProviderDoc(tabDoc) || tabDoc.id.is(SetupTabDocIds.Providers) then
      renderDoc(doc, width, status, chosenModel)
    else renderTabOverview(tabDoc, width)

  def renderModelPanel(model: Model, width: Int): List[String] =
    val configured = ProviderSetupProviderSelection.defaultConfiguredProvider(model.configStatus)
    val models     = ProviderSetupProviderSelection.defaultProviderModels(model.configStatus)
    if models.isEmpty then
      wrap(
        configured
          .map(_.discoveryDetail)
          .getOrElse("No models detected yet. Use reload after the default provider is reachable."),
        width
      )
    else
      List(
        s"Configured: ${configured.map(_.modelName).orElse(model.configStatus.modelName).getOrElse("none")}",
        s"Highlighted: ${model.highlightedModel.getOrElse("none")}",
        s"Chosen: ${model.chosenModel.getOrElse("none")}",
        "",
        "Use Up/Down to browse.",
        "Press Enter to choose.",
        ""
      ) ++ models.map { item =>
        val highlighted = model.highlightedModel.contains(item)
        val chosen      = model.chosenModel.contains(item)
        val marker =
          if highlighted && chosen then ">*"
          else if highlighted then " >"
          else if chosen then " *"
          else "  "
        s"$marker $item"
      }

  def renderProvidersTabPanel(model: Model, width: Int): List[String] =
    if model.focusTarget == FocusTarget.TabBar || model.providersPanelMode == ProvidersPanelMode.Providers then
      renderProviderPickerPanel(model, width)
    else renderSelectedProviderModelPanel(model, width)

  def renderCompareProviderPanel(model: Model, width: Int): List[String] =
    val available = ProviderSetupCompare.availableCompareProviders(model)
    if model.configStatus.namedProviders.isEmpty then
      wrap("No named providers are configured yet. Add some in application.local.conf and reload first.", width)
    else if available.isEmpty then
      List(
        s"Compare set is full or complete (${model.compareSelections.size}/$MaxCompareProviders).",
        "",
        "All configured providers are already in the compare set.",
        "Move right to review the selected compare entries.",
        "Press Enter there to remove one if you want to swap providers."
      ).flatMap(line => wrap(line, width))
    else
      val highlightedIndex = Math.floorMod(model.selectedProviderIndex, available.length)
      List(
        "",
        "Available compare providers",
        "Use Up/Down to browse.",
        "Move right to choose a model.",
        "",
        ""
      ) ++ available.zipWithIndex.map { case (provider, idx) =>
        val highlighted = idx == highlightedIndex
        val marker      = if highlighted then " >" else "  "
        s"$marker ${provider.name} (${provider.providerId})"
      }

  def renderCompareModelPanel(model: Model, width: Int): List[String] =
    ProviderSetupCompare.selectedCompareProvider(model) match
      case None =>
        wrap("No configured provider is currently available for compare selection.", width)
      case Some(provider) if provider.discoveredModels.isEmpty =>
        wrap(provider.discoveryDetail, width)
      case Some(provider) =>
        List(
          s"Provider: ${provider.name}",
          s"Configured: ${provider.modelName}",
          s"Highlighted: ${ProviderSetupCompare.selectedCompareProviderChosenModel(model).getOrElse("none")}",
          "",
          "Use Up/Down to browse.",
          "Press Enter to add this provider/model to the compare set.",
          "",
          ""
        ) ++ provider.discoveredModels.map { item =>
          val highlighted = model.highlightedModel.contains(item)
          val configured  = provider.modelName == item
          val marker =
            if highlighted && configured then ">*"
            else if highlighted then " >"
            else if configured then " *"
            else "  "
          s"$marker $item"
        }

  def renderGenericMiddlePanel(doc: ProviderDoc, width: Int): List[String] =
    List(
      s"Tab: ${doc.title}",
      "",
      "This middle panel is reserved",
      "for interactive pickers.",
      "",
      "The default provider tab uses it for live model",
      "selection with arrow keys."
    ).flatMap(line => wrap(line, width))

  def renderCompareSetupSidebar(model: Model, width: Int): List[String] =
    val selectedConfigured = ProviderSetupCompare.selectedCompareProvider(model)
    val highlightedEntry   = ProviderSetupCompare.selectedCompareEntry(model)
    val compareEntries =
      if model.compareSelections.isEmpty then List("No providers selected yet.")
      else
        model.compareSelections.zipWithIndex.flatMap { case (selection, idx) =>
          val highlighted = idx == model.highlightedCompareSelectionIndex
          val marker =
            if highlighted then " >"
            else "  "
          List(s"$marker ${selection.providerName}/${selection.selectedModel}")
        }.toList

    (List(
      "Selected compare set:",
      s"${model.compareSelections.size}/$MaxCompareProviders selected",
      highlightedEntry
        .map(entry => s"Remove target: ${entry.providerName} / ${entry.selectedModel}")
        .getOrElse("Remove target: none"),
      selectedConfigured.map(cfg => s"Available now: ${cfg.name} / ${cfg.modelName}").getOrElse("Available now: none"),
      "",
      "Entries:"
    ) ++ compareEntries ++ List(
      "",
      "Next move:",
      if model.compareSelections.lengthCompare(2) >= 0 then "Type use to enter compare mode."
      else "Add at least two provider/model pairs.",
      "",
      "Quick help:",
      "Left panel: browse providers",
      "Middle panel: Enter adds",
      "Right panel: Enter removes",
      "",
      "Focus:",
      focusSummary(model)
    )).flatMap(line => wrap(line, width))

  def renderStatusSidebar(
    tabDoc: ProviderDoc,
    doc: ProviderDoc,
    model: Model,
    sessionInput: Option[ProviderSessionInput],
    width: Int
  ): List[String] =
    val activeTabTitle = ProviderSetupTabs.tabTitle(model, model.activeTab)
    val shared = List(
      s"Active tab: $activeTabTitle",
      "",
      "Detected state:",
      model.configStatus.headline,
      "",
      "Demo mode:",
      if model.demoStreamingEnabled then "streaming enabled" else "streaming disabled",
      "",
      "Focus:",
      focusSummary(model),
      "",
      "Commands:",
      "next / prev",
      "tab <name>",
      "reload",
      "use",
      "set <field> <value>",
      "clear session",
      "quit or :q",
      "",
      "Navigation:",
      if model.focusTarget == FocusTarget.TabBar then "Left/right change tabs" else "Esc returns to tabs",
      if model.focusTarget == FocusTarget.TabBar then "Down enters selected tab" else bodyNavigationHint(model),
      if model.focusTarget == FocusTarget.Body && ProviderSetupSetupPolicy.isDefaultProviderTab(
          model
        ) && model.panelFocus == PanelFocus.Models
      then "Enter chooses highlighted"
      else if model.focusTarget == FocusTarget.Body && tabDoc.id.is(
          SetupTabDocIds.Providers
        ) && model.panelFocus == PanelFocus.Models
      then
        if model.providersPanelMode == ProvidersPanelMode.Providers then "Enter opens models"
        else "Enter chooses highlighted"
      else ""
    )

    val tabSpecific =
      if ProviderSetupSetupPolicy.isDefaultProviderTab(model) then
        val configured = ProviderSetupProviderSelection.defaultConfiguredProvider(model.configStatus)
        List(
          "",
          "Default provider state:",
          s"provider: ${configured.map(_.name).getOrElse(model.configStatus.providerName.getOrElse("none"))}",
          s"kind: ${configured.map(_.providerId).getOrElse(model.configStatus.providerId.getOrElse("unknown"))}",
          s"loaded config: ${configured.map(_.modelName).getOrElse(model.configStatus.modelName.getOrElse("none"))}",
          s"highlighted: ${model.highlightedModel.getOrElse("none")}",
          s"chosen: ${model.chosenModel.getOrElse("none")}",
          s"discovered: ${configured.map(_.discoveredModels.size).getOrElse(0)}",
          configured.map(_.discoveryDetail).getOrElse("No configured default provider."),
          "press Enter on a model"
        ) ++ renderSessionOverrideSummary(
          sessionInput,
          configured.map(_.providerId).getOrElse(model.configStatus.providerId.getOrElse("default provider"))
        )
      else if tabDoc.id.is(SetupTabDocIds.Providers) then
        val selectedConfigured = ProviderSetupProviderSelection.selectedConfiguredProvider(model)
        val chosenModel        = ProviderSetupProviderSelection.selectedConfiguredProviderChosenModel(model)
        List(
          "",
          "Selected provider:",
          selectedConfigured.map(_.name).getOrElse(doc.title),
          selectedConfigured.map(cfg => s"Kind: ${cfg.providerId}").getOrElse(s"Kind: ${doc.id}"),
          selectedConfigured.map(cfg => s"Configured model: ${cfg.modelName}").getOrElse("Configured model: none"),
          selectedConfigured
            .map(cfg => s"Discovered models: ${cfg.discoveredModels.size}")
            .getOrElse("Discovered models: n/a"),
          s"Chosen model: ${chosenModel.getOrElse("none")}",
          s"Required: ${doc.requiredVars.headOption.getOrElse("provider-specific")}",
          s"Configured now: ${
              if selectedConfigured.nonEmpty || model.configStatus.providerId.contains(doc.id.value) then "yes"
              else "no"
            }",
          "",
          "Next move:",
          if model.providersPanelMode == ProvidersPanelMode.Providers && selectedConfigured.nonEmpty then
            "Press Enter to browse models for the selected configured provider."
          else if selectedConfigured.nonEmpty then
            "Press Enter to choose a model, or type use to enter demo with the selected configured provider."
          else if model.configStatus.providerId.contains(doc.id.value) then
            "Type use to enter demo with the configured provider."
          else s"Configure ${doc.title}, run reload, then type use."
        ) ++ renderSessionOverrideSummary(sessionInput, selectedConfigured.map(_.name).getOrElse(doc.id.value))
      else if tabDoc.id.is(SetupTabDocIds.Compare) then
        val selectedConfigured = ProviderSetupCompare.selectedCompareProvider(model)
        val highlightedEntry   = ProviderSetupCompare.selectedCompareEntry(model)
        val compareEntries =
          if model.compareSelections.isEmpty then List("No providers selected yet.")
          else
            model.compareSelections.zipWithIndex.flatMap { case (selection, idx) =>
              val highlighted = idx == model.highlightedCompareSelectionIndex
              val marker =
                if highlighted then " >"
                else "  "
              List(
                s"$marker ${selection.providerName}",
                s"   model: ${selection.selectedModel}"
              )
            }

        List(
          "",
          "Selected compare set:",
          s"${model.compareSelections.size}/$MaxCompareProviders selected",
          highlightedEntry
            .map(entry => s"Remove target: ${entry.providerName} / ${entry.selectedModel}")
            .getOrElse("Remove target: none"),
          selectedConfigured
            .map(cfg => s"Available now: ${cfg.name} / ${cfg.modelName}")
            .getOrElse("Available now: none"),
          ""
        ) ++ compareEntries ++ List(
          "",
          "Next move:",
          if model.compareSelections.lengthCompare(2) >= 0 then "Type use to enter compare mode."
          else "Add at least two provider/model pairs.",
          "",
          "Quick help:",
          "Left panel: browse providers",
          "Middle panel: Enter adds",
          "Right panel: Enter removes"
        )
      else
        List(
          "",
          "For this tab:",
          s"Required: ${doc.requiredVars.headOption.getOrElse("depends on provider")}",
          s"Optional: ${doc.optionalVars.headOption.getOrElse("none")}",
          "",
          "Suggested models:"
        ) ++
          doc.recommendedModels.take(2).map(modelName => s"- $modelName") ++
          List(
            "",
            "Next move:",
            nextStep(doc, model)
          ) ++ renderSessionOverrideSummary(sessionInput, doc.id.value)

    (shared ++ tabSpecific).flatMap(line => wrap(line, width))

  def footerLine(model: Model): String =
    val activeDocId = ProviderSetupTabs.activeSetupDoc(model).id
    val guidance =
      model.focusTarget match
        case FocusTarget.TabBar =>
          "Tab bar: Left/Right change tabs, Down enters body"
        case FocusTarget.Body
            if ProviderSetupSetupPolicy.isDefaultProviderTab(model) && model.panelFocus == PanelFocus.Models =>
          "Body: Esc to tabs, Left/Right move panels, Up/Down browse, Enter chooses"
        case FocusTarget.Body if activeDocId.is(SetupTabDocIds.Providers) && model.panelFocus == PanelFocus.Models =>
          "Body: Esc to tabs, Left/Right move panels, Up/Down browse providers"
        case FocusTarget.Body =>
          "Body: Esc to tabs, Left/Right move panels"

    val loaded = s"Loaded llm4s config: ${model.configStatus.headline}"
    model.chosenModel match
      case Some(chosen) if !model.configStatus.modelName.contains(chosen) =>
        s"$guidance | $loaded | Pending choice: $chosen"
      case _ =>
        s"$guidance | $loaded"

  def focusSummary(model: Model): String =
    model.focusTarget match
      case FocusTarget.TabBar => "Tab bar"
      case FocusTarget.Body   => focusLabel(model.panelFocus)

  def bodyNavigationHint(model: Model): String =
    if ProviderSetupSetupPolicy.isDefaultProviderTab(model) && model.panelFocus == PanelFocus.Models then
      "Up/down browse models"
    else if ProviderSetupTabs
        .activeSetupDoc(model)
        .id
        .is(SetupTabDocIds.Providers) && model.panelFocus == PanelFocus.Models
    then
      if model.providersPanelMode == ProvidersPanelMode.Providers then "Up/down browse providers"
      else "Up/down browse models"
    else if ProviderSetupTabs.activeSetupDoc(model).id.is(SetupTabDocIds.Compare) then
      model.panelFocus match
        case PanelFocus.Main   => "Up/down browse providers"
        case PanelFocus.Models => "Up/down browse models"
        case PanelFocus.Status => "Up/down browse compare set"
    else "Left/right move panels"

  def defaultProviderDoc(model: Model): Option[ProviderDoc] =
    ProviderSetupProviderSelection
      .defaultConfiguredProvider(model.configStatus)
      .flatMap(cfg => ProviderSetupContent.providerDocs.find(_.id.value == cfg.providerId))

  private def isDefaultProviderDoc(doc: ProviderDoc): Boolean =
    doc.id.is(SetupTabDocIds.Default)

  private def renderDoc(
    doc: ProviderDoc,
    width: Int,
    status: ConfigStatus,
    chosenModel: Option[String]
  ): List[String] =
    List(
      s"Summary: ${doc.summary}",
      "",
      "Highlights:"
    ) ++
      doc.highlights.flatMap(line => wrap(s"- $line", width)) ++
      List("", "Required:") ++
      doc.requiredVars.flatMap(line => wrap(s"- $line", width)) ++
      List("", "Optional:") ++
      doc.optionalVars.flatMap(line => wrap(s"- $line", width)) ++
      List("", "Recommended models:") ++
      doc.recommendedModels.flatMap(line => wrap(s"- $line", width)) ++
      List("", "Setup steps:") ++
      doc.setupSteps.zipWithIndex.flatMap { case (step, idx) =>
        wrap(s"${idx + 1}. $step", width)
      } ++
      (if isDefaultProviderDoc(doc) then
         List(
           s"Configured provider: ${status.providerName.getOrElse("none")}",
           s"Provider kind: ${status.providerId.getOrElse("unknown")}",
           "",
           s"Configured model: ${status.modelName.getOrElse("none")}",
           s"Chosen model: ${chosenModel.getOrElse("none")}",
           "",
           "Use the middle panel for live model browsing."
         )
       else if status.providerName.nonEmpty && status.providerId.contains(doc.id.value) then
         List(
           "",
           s"Configured provider name: ${status.providerName.getOrElse("none")}",
           s"Provider kind: ${status.providerId.getOrElse("unknown")}",
           s"Configured model: ${status.modelName.getOrElse("none")}",
           s"Chosen model: ${chosenModel.getOrElse("none")}",
           "",
           "Use the middle panel for discovered model browsing."
         )
       else if doc.id.is(SetupTabDocIds.Status) then List("", s"Current detail: ${status.detail}")
       else Nil) .flatMap(line => wrap(line, width))

  private def renderTabOverview(doc: ProviderDoc, width: Int): List[String] =
    List(
      s"Summary: ${doc.summary}",
      "",
      "Why this tab matters:"
    ) ++
      doc.highlights.flatMap(line => wrap(s"- $line", width)) ++
      List("", "What to do here:") ++
      doc.setupSteps.take(3).zipWithIndex.flatMap { case (step, idx) =>
        wrap(s"${idx + 1}. $step", width)
      }

  private def renderProviderPickerPanel(model: Model, @unused width: Int): List[String] =
    if model.configStatus.namedProviders.nonEmpty then
      List(
        "Configured named providers",
        "Use Up/Down to browse.",
        "Press Enter to browse the selected provider's models.",
        ""
      ) ++ model.configStatus.namedProviders.zipWithIndex.map { case (provider, idx) =>
        val highlighted = idx == model.selectedProviderIndex
        val marker =
          if highlighted && provider.isDefault then ">*"
          else if highlighted then " >"
          else if provider.isDefault then " *"
          else "  "
        s"$marker ${provider.name} (${provider.providerId})"
      }
    else
      List(
        "Use Up/Down to browse.",
        "Type use after matching llm4s config.",
        ""
      ) ++ ProviderSetupContent.providerDocs.zipWithIndex.map { case (doc, idx) =>
        val highlighted = idx == model.selectedProviderIndex
        val configured  = model.configStatus.providerId.contains(doc.id)
        val marker =
          if highlighted && configured then ">*"
          else if highlighted then " >"
          else if configured then " *"
          else "  "
        s"$marker ${doc.title}"
      }

  private def renderSelectedProviderModelPanel(model: Model, width: Int): List[String] =
    ProviderSetupProviderSelection.selectedConfiguredProvider(model) match
      case None =>
        wrap("No configured provider is selected.", width)
      case Some(provider) =>
        val chosen = ProviderSetupProviderSelection.selectedConfiguredProviderChosenModel(model)
        if provider.discoveredModels.isEmpty then wrap(provider.discoveryDetail, width)
        else
          List(
            s"Provider: ${provider.name}",
            s"Configured: ${provider.modelName}",
            s"Highlighted: ${model.highlightedModel.getOrElse("none")}",
            s"Chosen: ${chosen.getOrElse("none")}",
            "",
            "Use Up/Down to browse.",
            "Press Enter to choose.",
            "Press Esc to return to providers.",
            ""
          ) ++ provider.discoveredModels.map { item =>
            val highlighted = model.highlightedModel.contains(item)
            val isChosen    = chosen.contains(item)
            val marker =
              if highlighted && isChosen then ">*"
              else if highlighted then " >"
              else if isChosen then " *"
              else "  "
            s"$marker $item"
          }

  private def renderSessionOverrideSummary(
    sessionInput: Option[ProviderSessionInput],
    providerId: String
  ): List[String] =
    sessionInput match
      case Some(input) if input.hasAnyValue =>
        List(
          "",
          "Session override:",
          s"model: ${input.model.getOrElse("none")}",
          s"api-key: ${maskValue(input.apiKey)}",
          s"base-url: ${input.baseUrl.getOrElse("none")}",
          s"org-id: ${input.organization.getOrElse("none")}",
          s"endpoint: ${input.endpoint.getOrElse("none")}",
          s"api-version: ${input.apiVersion.getOrElse("none")}",
          "",
          sessionOverrideHint(providerId)
        )
      case _ =>
        List(
          "",
          "Session override:",
          s"none for $providerId",
          sessionOverrideHint(providerId)
        )

  private def sessionOverrideHint(providerId: String): String =
    providerId match
      case configured if modelProviderKindId(configured).contains("azure") =>
        "Use set model/api-key/endpoint/api-version for a session-only Azure override."
      case "azure" =>
        "Use set model/api-key/endpoint/api-version for a session-only Azure override."
      case _ =>
        "Use set model/api-key/base-url for a session-only override."

  private def maskValue(value: Option[String]): String =
    value match
      case Some(secret) if secret.nonEmpty =>
        val visible = secret.takeRight(4)
        s"...$visible"
      case _ =>
        "none"

  private def nextStep(doc: ProviderDoc, model: Model): String =
    doc.id match
      case id if id.is(SetupTabDocIds.Overview) =>
        if model.configStatus.providerId.nonEmpty then
          "Press Right for details or open Providers to browse the wider catalog."
        else "Open the default provider tab or Providers and follow the required config."
      case id if id.is(SetupTabDocIds.Providers) =>
        if model.configStatus.namedProviders.nonEmpty then
          "Use the middle panel to browse configured named providers, then type use."
        else "Use the middle panel to browse providers, then configure one outside the app and reload."
      case id if id.is(SetupTabDocIds.Config) =>
        "Keep secrets in env vars first, then consider local config later."
      case id if id.is(SetupTabDocIds.Status) =>
        "Change config outside the app, then run reload to re-check llm4s."
      case _ =>
        "Use the active tab to continue setup."

  private def focusLabel(focus: PanelFocus): String = focus match
    case PanelFocus.Main   => "Help"
    case PanelFocus.Models => "Models"
    case PanelFocus.Status => "Status"
