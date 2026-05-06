package org.llm4s.samples.dashboard.providersetup

import org.llm4s.config.{ DiscoveredModel, Llm4sConfig }
import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ProviderName
import termflow.tui.TuiRuntime

/**
 * First-run provider onboarding sample for llm4s + termflow.
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
      providersCfg    <- Llm4sConfig.providers()
      defaultProvider <- providersCfg.defaultProviderName
      _ <- providersCfg.namedProviders
        .get(defaultProvider)
        .toRight(
          ConfigurationError(s"Default provider ${defaultProvider.asName} not found")
        )
      (errors, configs) = Llm4sConfig.providerConfigs(providersCfg.namedProviders)
      pc <-
        if (errors.nonEmpty)
          Left(
            ConfigurationError(
              s"no provider config found for ${errors.map((k, v) => s"${k.asName}:${v.formatted}}").mkString(System.lineSeparator())}"
            )
          )
        else Right(configs)

      discoveredModels: Map[ProviderName, List[DiscoveredModel]] = providersCfg.namedProviders.toList.map {
        case (name, _) =>
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
          providersCfg,
          pc,
          configs(defaultProvider),
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
