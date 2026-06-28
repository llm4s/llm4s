package org.llm4s.samples.dashboard.providersetup

import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*

object ProviderSetupCompare:

  def availableCompareProviders(model: Model): Vector[ConfiguredProvider] =
    val selected = model.compareSelections.map(_.providerName).toSet
    model.configStatus.namedProviders.filterNot(provider => selected.contains(provider.name))

  def selectedCompareProvider(model: Model): Option[ConfiguredProvider] =
    val available = availableCompareProviders(model)
    if available.isEmpty then None
    else available.lift(Math.floorMod(model.selectedProviderIndex, available.length))

  def selectedCompareProviderModels(model: Model): Vector[String] =
    selectedCompareProvider(model).map(_.discoveredModels).getOrElse(Vector.empty)

  def selectedCompareProviderChosenModel(model: Model): Option[String] =
    selectedCompareProvider(model).flatMap { provider =>
      model.highlightedModel
        .filter(provider.discoveredModels.contains)
        .orElse(Option.when(provider.discoveredModels.contains(provider.modelName))(provider.modelName))
        .orElse(provider.discoveredModels.headOption)
    }

  def selectedCompareEntry(model: Model): Option[CompareSelection] =
    if model.compareSelections.isEmpty then None
    else
      model.compareSelections.lift(
        Math.floorMod(model.highlightedCompareSelectionIndex, model.compareSelections.length)
      )

  def moveHighlightedCompareProvider(model: Model, direction: Int): Model =
    val providers = availableCompareProviders(model)
    if providers.isEmpty then model.copy(statusLine = "No more configured providers are available for comparison.")
    else
      val nextIndex = Math.floorMod(model.selectedProviderIndex + direction, providers.length)
      val next      = providers(nextIndex)
      val initialModel =
        Option
          .when(next.discoveredModels.contains(next.modelName))(next.modelName)
          .orElse(next.discoveredModels.headOption)
      model
        .updateSetup(_.copy(selectedProviderIndex = nextIndex, highlightedModel = initialModel))
        .copy(statusLine = s"Highlighted compare provider: ${next.name}. Move right to choose a model.")

  def moveHighlightedCompareModelValue(model: Model, direction: Int): Model =
    selectedCompareProvider(model) match
      case None =>
        model.copy(statusLine = "No configured provider is available for comparison.")
      case Some(provider) =>
        val models = provider.discoveredModels
        if models.isEmpty then model.copy(statusLine = provider.discoveryDetail)
        else
          val currentIndex = selectedCompareProviderChosenModel(model)
            .flatMap(models.indexOf(_) match
              case -1  => None
              case idx => Some(idx)
            )
            .getOrElse(0)
          val nextIndex = Math.floorMod(currentIndex + direction, models.length)
          val nextModel = models(nextIndex)
          model
            .updateSetup(_.copy(highlightedModel = Some(nextModel)))
            .copy(statusLine = s"Highlighted compare model for ${provider.name}: $nextModel. Press Enter to add it.")

  def moveHighlightedCompareSelection(model: Model, direction: Int): Model =
    if model.compareSelections.isEmpty then model.copy(statusLine = "Compare set is empty.")
    else
      val nextIndex = Math.floorMod(model.highlightedCompareSelectionIndex + direction, model.compareSelections.length)
      val next      = model.compareSelections(nextIndex)
      model
        .updateCompare(_.copy(highlightedSelectionIndex = nextIndex))
        .copy(
          statusLine =
            s"Highlighted compare entry: ${next.providerName} / ${next.selectedModel}. Press Enter to remove it."
        )

  def addSelectedCompareEntry(model: Model): Model =
    selectedCompareProvider(model) match
      case None =>
        model.copy(statusLine = "No configured provider is available for comparison.")
      case Some(_) if model.compareSelections.size >= MaxCompareProviders =>
        model.copy(statusLine = s"Compare set is full. Keep it to $MaxCompareProviders providers for this demo.")
      case Some(provider) =>
        selectedCompareProviderChosenModel(model) match
          case None =>
            model.copy(statusLine = provider.discoveryDetail)
          case Some(selectedModel) =>
            val nextSelections = model.compareSelections :+ CompareSelection(
              providerName = provider.name,
              providerId = provider.providerId,
              configuredModel = provider.modelName,
              selectedModel = selectedModel
            )
            val remaining =
              model.configStatus.namedProviders.filterNot(cfg => nextSelections.exists(_.providerName == cfg.name))
            val nextAvailableIndex =
              if remaining.isEmpty then 0 else Math.floorMod(model.selectedProviderIndex, remaining.length)
            val nextHighlight =
              remaining.lift(nextAvailableIndex).flatMap { cfg =>
                Option
                  .when(cfg.discoveredModels.contains(cfg.modelName))(cfg.modelName)
                  .orElse(cfg.discoveredModels.headOption)
              }
            model
              .updateShell(_.copy(panelFocus = PanelFocus.Main))
              .updateSetup(
                _.copy(
                  selectedProviderIndex = nextAvailableIndex,
                  highlightedModel = nextHighlight
                )
              )
              .updateCompare(
                _.copy(
                  selections = nextSelections,
                  highlightedSelectionIndex = nextSelections.length - 1
                )
              )
              .copy(
                statusLine = s"Added ${provider.name} / $selectedModel to the compare set."
              )

  def removeHighlightedCompareEntry(model: Model): Model =
    selectedCompareEntry(model) match
      case None =>
        model.copy(statusLine = "No compare entry is highlighted.")
      case Some(entry) =>
        val nextSelections = model.compareSelections.filterNot(_.providerName == entry.providerName)
        val nextIndex =
          if nextSelections.isEmpty then 0
          else Math.min(model.highlightedCompareSelectionIndex, nextSelections.length - 1)
        model
          .updateCompare(
            _.copy(
              selections = nextSelections,
              highlightedSelectionIndex = nextIndex
            )
          )
          .copy(statusLine = s"Removed ${entry.providerName} from the compare set.")

  def moveActiveCompareTab(model: Model, direction: Int): Model =
    if model.compareResults.isEmpty then model
    else
      val nextIndex = Math.floorMod(model.activeCompareTab + direction, model.compareResults.length)
      model
        .updateCompare(_.copy(activeTab = nextIndex))
        .copy(statusLine = s"Viewing ${model.compareResults(nextIndex).selection.providerName}.")
