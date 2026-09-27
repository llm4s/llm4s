package org.llm4s.samples.dashboard.providersetup

import org.llm4s.config.{ DiscoveredModel, Llm4sConfig }
import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ProviderName
import termflow.tui.TuiRuntime

/**
 * First-run provider onboarding sample for llm4s + termflow.
 *
 * Sections are loaded with `Llm4sConfig.providerConfigs()`, which loads each named section on
 * its own: a section that fails - an unset `${?VAR}` key, a provider whose module is not on the
 * classpath - is reported with its error on stderr and in the Status tab, and the others are
 * still usable. Only the default section has to load.
 *
 * Run with:
 * `sbt "samples/runMain org.llm4s.samples.dashboard.providersetup.ProviderSetupDemoMain"`
 */
@main
def ProviderSetupDemoMain(): Unit =
  (
    for
      demoCfg         <- ProviderSetupDemoConfig.load()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      loaded <- Llm4sConfig.providerConfigs()
      (errors, configs) = loaded
      _ = errors.toSeq
        .sortBy(_._1.asName)
        .foreach((name, err) => System.err.println(s"Provider section '${name.asName}' did not load: ${err.formatted}"))
      defaultName <- Llm4sConfig.defaultProviderName()
      defaultProvider <- configs
        .get(defaultName)
        .toRight(
          errors.getOrElse(
            defaultName,
            ConfigurationError(s"Default provider ${defaultName.asName} not found")
          )
        )

      discoveredModels: Map[ProviderName, List[DiscoveredModel]] = configs.keys.toList.map { name =>
        Llm4sConfig.listModels(name.asName) match
          case Right(list) =>
            (name, list)
          case _ =>
            (name, List.empty[DiscoveredModel])
      }.toMap
      exchangeLogging <- Llm4sConfig.exchangeLogging()
      _ = TuiRuntime.run(
        ProviderSetupDemoApp.App(
          demoCfg,
          defaultName,
          configs,
          errors,
          defaultProvider,
          discoveredModels,
          exchangeLogging
        )
      )
    yield ()
  ).fold(
    error =>
      System.err.println(error.formatted)
      sys.exit(1)
    ,
    identity
  )
