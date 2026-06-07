package org.llm4s.samples.dashboard.providersetup

import org.llm4s.error.ValidationError
import org.llm4s.samples.dashboard.providersetup.ProviderSetupMessages.*
import org.llm4s.samples.dashboard.providersetup.ProviderSetupModel.*
import org.llm4s.types.ProviderModelTypes.{ ProviderKind, ProviderName }
import org.llm4s.types.Result
import termflow.tui.Tui
import termflow.tui.Tui.*

object ProviderSetupProviderSelection:

  def defaultProviderModels(configStatus: ConfigStatus): Vector[String] =
    defaultConfiguredProvider(configStatus)
      .map(_.discoveredModels)
      .filter(_.nonEmpty)
      .getOrElse(Vector.empty)

  def selectedConfiguredProviderModels(model: Model): Vector[String] =
    selectedConfiguredProvider(model).map(_.discoveredModels).getOrElse(Vector.empty)

  def selectedConfiguredProviderChosenModel(model: Model): Option[String] =
    selectedConfiguredProvider(model).flatMap { provider =>
      model.sessionInputs
        .get(SessionOverrideTarget.NamedProvider(ProviderName(provider.name)))
        .flatMap(_.model)
        .orElse(Option.when(provider.discoveredModels.contains(provider.modelName))(provider.modelName))
    }

  def confirmHighlightedModel(model: Model): Model =
    model.highlightedModel match
      case Some(selected) =>
        model
          .updateSetup(_.copy(chosenModel = Some(selected)))
          .copy(statusLine = s"Chosen model: $selected. Type use to open chat.")
      case None =>
        model.copy(statusLine = "No highlighted model to choose yet.")

  def openSelectedConfiguredProviderModels(model: Model): Model =
    selectedConfiguredProvider(model) match
      case None =>
        model.copy(statusLine = "No configured provider is selected.")
      case Some(provider) if provider.discoveredModels.isEmpty =>
        model
          .updateSetup(_.copy(providersPanelMode = ProvidersPanelMode.Models, highlightedModel = None))
          .copy(statusLine = provider.discoveryDetail)
      case Some(provider) =>
        val initial =
          selectedConfiguredProviderChosenModel(model)
            .filter(provider.discoveredModels.contains)
            .orElse(provider.discoveredModels.headOption)
        model
          .updateSetup(_.copy(providersPanelMode = ProvidersPanelMode.Models, highlightedModel = initial))
          .copy(statusLine = s"Browsing models for ${provider.name}. Press Enter to choose or Esc to go back.")

  def confirmHighlightedConfiguredModel(model: Model): Model =
    selectedConfiguredProvider(model) match
      case None =>
        model.copy(statusLine = "No configured provider is selected.")
      case Some(provider) =>
        model.highlightedModel match
          case Some(selected) =>
            val target  = SessionOverrideTarget.NamedProvider(ProviderName(provider.name))
            val current = model.sessionInputs.getOrElse(target, ProviderSessionInput())
            model.copy(
              sessionInputs = model.sessionInputs.updated(target, current.copy(model = Some(selected))),
              statusLine = s"Chosen model for ${provider.name}: $selected. Type use to open chat."
            )
          case None =>
            model.copy(statusLine = s"No highlighted model for ${provider.name} to choose yet.")

  def chooseModel(
    existing: Option[String],
    configStatus: ConfigStatus
  ): Option[String] =
    val discoveredModels = defaultProviderModels(configStatus)

    val configured =
      defaultConfiguredProvider(configStatus)
        .map(_.modelName)
        .orElse:
          configStatus.providerId match
            case Some("ollama") => configStatus.modelName
            case _              => None

    configured
      .filter(discoveredModels.contains)
      .orElse(existing.filter(discoveredModels.contains))
      .orElse(discoveredModels.headOption)

  def moveHighlightedModelValue(model: Model, direction: Int): Model =
    val models        = defaultProviderModels(model.configStatus)
    val providerLabel = defaultConfiguredProvider(model.configStatus).map(_.name).getOrElse("default provider")
    if models.isEmpty then
      model.copy(statusLine = s"No models available for $providerLabel. Use reload after the provider is reachable.")
    else
      val currentIndex = model.highlightedModel
        .flatMap(selected =>
          models.indexOf(selected) match
            case -1  => None
            case idx => Some(idx)
        )
        .getOrElse(0)
      val nextIndex = Math.floorMod(currentIndex + direction, models.length)
      val nextModel = models(nextIndex)
      model
        .updateSetup(_.copy(highlightedModel = Some(nextModel)))
        .copy(statusLine = s"Highlighted model for $providerLabel: $nextModel. Press Enter to choose.")

  def moveHighlightedConfiguredModelValue(model: Model, direction: Int): Model =
    selectedConfiguredProvider(model) match
      case None =>
        model.copy(statusLine = "No configured provider is selected.")
      case Some(provider) =>
        val models = provider.discoveredModels
        if models.isEmpty then model.copy(statusLine = provider.discoveryDetail)
        else
          val currentIndex = model.highlightedModel
            .flatMap(selected =>
              models.indexOf(selected) match
                case -1  => None
                case idx => Some(idx)
            )
            .getOrElse(0)
          val nextIndex = Math.floorMod(currentIndex + direction, models.length)
          val nextModel = models(nextIndex)
          model
            .updateSetup(_.copy(highlightedModel = Some(nextModel)))
            .copy(statusLine = s"Highlighted model for ${provider.name}: $nextModel. Press Enter to choose.")

  def selectHighlightedModel(model: Model, requested: String): Tui[Model, Msg] =
    val normalized    = requested.trim
    val models        = defaultProviderModels(model.configStatus)
    val providerLabel = defaultConfiguredProvider(model.configStatus).map(_.name).getOrElse("default provider")
    if models.contains(normalized) then
      model
        .updateSetup(_.copy(highlightedModel = Some(normalized)))
        .copy(statusLine = s"Highlighted model for $providerLabel: $normalized. Press Enter to choose.")
        .tui
    else model.copy(statusLine = s"Model not found for $providerLabel: $requested").tui

  def selectedProviderDoc(model: Model): ProviderDoc =
    selectedConfiguredProvider(model)
      .flatMap(cfg => ProviderSetupContent.providerDocs.find(doc => doc.id.value == cfg.providerId))
      .orElse(ProviderSetupContent.providerDocs.lift(model.selectedProviderIndex))
      .getOrElse(ProviderSetupContent.providerDocs.head)

  def selectedConfiguredProvider(model: Model): Option[ConfiguredProvider] =
    model.configStatus.namedProviders.lift(model.selectedProviderIndex)

  def defaultConfiguredProvider(configStatus: ConfigStatus): Option[ConfiguredProvider] =
    configStatus.namedProviders.find(_.isDefault).orElse(configStatus.namedProviders.headOption)

  def selectedSessionOverrideTarget(model: Model): SessionOverrideTarget =
    val activeDocId = ProviderSetupTabs.activeSetupDoc(model).id
    if activeDocId.is(SetupTabDocIds.Providers) then
      selectedConfiguredProvider(model)
        .map(cfg => SessionOverrideTarget.NamedProvider(ProviderName(cfg.name)))
        .getOrElse(SessionOverrideTarget.ProviderKind(selectedProviderDoc(model).id))
    else if ProviderSetupSetupPolicy.isDefaultProviderTab(model) then
      defaultConfiguredProvider(model.configStatus)
        .map(cfg => SessionOverrideTarget.NamedProvider(ProviderName(cfg.name)))
        .getOrElse(SessionOverrideTarget.ProviderKind(activeDocId))
    else SessionOverrideTarget.ProviderKind(activeDocId)

  def selectedSetupProviderKind(model: Model): Result[ProviderKind] =
    val activeDocId = ProviderSetupTabs.activeSetupDoc(model).id
    if activeDocId.is(SetupTabDocIds.Providers) then
      selectedConfiguredProvider(model)
        .map(provider => providerKindFromString(provider.providerId))
        .getOrElse(providerKindFromString(selectedProviderDoc(model).id.value))
    else providerKindFromString(activeDocId.value)

  def currentSetupSessionRequest(model: Model): Result[ProviderSetupSetupPolicy.SetupSessionRequest] =
    val activeDoc    = ProviderSetupTabs.activeSetupDoc(model)
    val isDefaultTab = ProviderSetupSetupPolicy.isDefaultProviderTab(model)
    val selectedConfigured =
      Option
        .when(ProviderSetupSetupPolicy.isProvidersTab(model))(selectedConfiguredProvider(model))
        .orElse(Option.when(isDefaultTab)(defaultConfiguredProvider(model.configStatus)))
        .flatten
    val sessionTarget = selectedSessionOverrideTarget(model)
    val selectedProviderKindResult =
      if ProviderSetupSetupPolicy.isProvidersTab(model) then selectedSetupProviderKind(model).map(Some(_))
      else Right(None)

    selectedProviderKindResult.map { selectedProviderKind =>
      ProviderSetupSetupPolicy.SetupSessionRequest(
        isDefaultProviderTab = isDefaultTab,
        activeTab = model.activeTab,
        activeDocId = activeDoc.id,
        selectedProviderKind = selectedProviderKind,
        selectedConfiguredProvider = selectedConfigured,
        sessionTarget = sessionTarget
      )
    }

  private def providerKindFromString(value: String): Result[ProviderKind] =
    ProviderKind
      .fromString(value)
      .toRight(ValidationError("providerKind", s"Expected provider kind but got: $value"))

  def chooseSelectedProviderIndex(existing: Int, configStatus: ConfigStatus): Int =
    if configStatus.namedProviders.isEmpty then existing
    else
      val clamped = Math.floorMod(existing, configStatus.namedProviders.length)
      configStatus.namedProviders.indexWhere(_.isDefault) match
        case -1  => clamped
        case idx => idx

  def moveHighlightedProvider(model: Model, direction: Int): Model =
    val providers =
      if model.configStatus.namedProviders.nonEmpty then model.configStatus.namedProviders.map(_.name)
      else ProviderSetupContent.providerDocs.map(_.title)
    if providers.isEmpty then model.copy(statusLine = "No provider docs are available.")
    else
      val nextIndex = Math.floorMod(model.selectedProviderIndex + direction, providers.length)
      val nextDoc   = providers(nextIndex)
      model
        .updateSetup(
          _.copy(
            selectedProviderIndex = nextIndex,
            highlightedModel = None,
            providersPanelMode = ProvidersPanelMode.Providers
          )
        )
        .copy(statusLine = s"Highlighted provider: ${nextDoc}. Type use after configuring it in llm4s.")
