package org.llm4s.configpolicy

import com.typesafe.config.{ Config, ConfigObject }
import org.llm4s.config.{ Llm4sConfig, SharedCredentials }
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.*
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation }
import org.llm4s.llmconnect.spi.{ ProviderDescriptor, ProviderRegistry }
import org.llm4s.types.ProviderModelTypes.*
import org.llm4s.types.Result
import pureconfig.ConfigSource

import java.util.Locale
import java.util.concurrent.{ Executors, ThreadFactory, TimeoutException }
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{ Failure, Success, Try, Using }

/**
 * What a doctor run needs from the outside world, so tests can replace each piece.
 *
 * @param javaFeature the running JDK's feature version, e.g. `21`
 * @param listModels  asks a provider's own model lister which models a local server has
 * @param live        makes one small request with the given provider config and returns the model that answered
 */
final case class DoctorPorts private (
  javaFeature: () => Int,
  listModels: (ProviderDescriptor, NamedProviderConfig) => Result[List[String]],
  live: ProviderConfig => Result[String]
) {
  def withJavaFeature(javaFeature: () => Int): DoctorPorts = copy(javaFeature = javaFeature)
  def withListModels(listModels: (ProviderDescriptor, NamedProviderConfig) => Result[List[String]]): DoctorPorts =
    copy(listModels = listModels)
  def withLive(live: ProviderConfig => Result[String]): DoctorPorts = copy(live = live)
}

object DoctorPorts {

  def apply(
    javaFeature: () => Int,
    listModels: (ProviderDescriptor, NamedProviderConfig) => Result[List[String]],
    live: ProviderConfig => Result[String]
  ): DoctorPorts = new DoctorPorts(javaFeature, listModels, live)

  /** The real thing: the running JVM, the provider's own lister over HTTP, and a one-token request. */
  val system: DoctorPorts = new DoctorPorts(
    () => Runtime.version().feature(),
    systemListModels,
    systemLive
  )

  private def systemListModels(descriptor: ProviderDescriptor, section: NamedProviderConfig): Result[List[String]] =
    descriptor.modelLister match {
      case None => Right(Nil)
      case Some(lister) =>
        Using.resource(Llm4sHttpClient.create())(client =>
          lister.listModels(section, client).map(_.map(_.name.asString))
        )
    }

  private def systemLive(config: ProviderConfig): Result[String] =
    for {
      service      <- Llm4sConfig.modelRegistryService()
      client       <- LLMConnect.getClient(config)(using service, ProviderRegistry.default)
      conversation <- Conversation.userOnly("Reply with the single word: ok")
      completion   <- client.complete(conversation, CompletionOptions().withMaxTokens(8))
    } yield completion.model
}

/**
 * Checks a whole LLM4S setup in one run and says what to change when a step fails.
 *
 * The steps, in order: the JDK, the configuration, the default provider, its module on the classpath, its
 * API key, its config, a local model server, and (only with [[DoctorOptions.live]]) one small request.
 * A step that cannot run because an earlier one failed is reported as skipped, not as failed.
 *
 * By default nothing is sent to a hosted provider. A server on the loopback interface (an Ollama on the
 * same machine) is asked which models it has, because that is the usual first-run problem; turn that off
 * with [[DoctorOptions.offline]]. The API key's value is never read into the report: only the config path
 * it came from is shown.
 */
object Doctor {

  /** The JDK this version of LLM4S needs: core uses virtual threads. */
  val MinimumJava: Int = 21

  private val ConfigStep = "Configuration"
  private val Default    = "Default provider"
  private val Module     = "Provider module"
  private val Key        = "API key"
  private val Section    = "Provider config"
  private val LocalModel = "Local server"
  private val Live       = "Live call"

  /**
   * Runs every check against `source`.
   *
   * @param source  where the configuration comes from (see `CheckPolicies.sourceFor`)
   * @param options what the run may do
   * @param ports   the outside world, replaceable in tests
   * @param registry the providers on the classpath
   */
  def run(
    source: ConfigSource,
    options: DoctorOptions = DoctorOptions(),
    ports: DoctorPorts = DoctorPorts.system
  )(using registry: ProviderRegistry): DoctorReport = {
    val secrets = knownSecrets(source)
    DoctorReport((javaCheck(ports.javaFeature()) :: configChecks(source, options, ports)).map(_.scrubbed(secrets)))
  }

  // Shorter values are left alone: hiding a three-letter "key" would mangle ordinary words in the report.
  private val MinSecretLength = 8

  /**
   * Every API key the configuration holds, so the report can hide them by value as well as by shape.
   * Redaction recognises the shapes of known vendors' keys; an error message that echoes a key of
   * another shape (a gateway token, a test key) would otherwise reach the output.
   */
  private def knownSecrets(source: ConfigSource): Set[String] = {
    val shared = SharedCredentials.read(source).entries.values.toList.flatMap(_.toOption)
    val own = readConfig(source).toOption.toList.flatMap { config =>
      providerSections(config).values.toList.flatMap(section => value(section, "apiKey"))
    }
    (shared ::: own).filter(_.length >= MinSecretLength).toSet
  }

  private def javaCheck(feature: Int): DoctorCheck =
    if feature >= MinimumJava then DoctorCheck.pass("JDK", s"Java $feature (LLM4S needs $MinimumJava or newer)")
    else
      DoctorCheck
        .fail("JDK", s"Java $feature is too old: LLM4S needs $MinimumJava or newer")
        .withFix(s"run with a JDK $MinimumJava or newer (set JAVA_HOME, or choose it in your IDE and build tool)")

  private val AfterConfig = List(Default, Module, Key, Section, LocalModel, Live)

  private def skipped(reason: String, names: List[String]): List[DoctorCheck] =
    names.map(DoctorCheck.skipped(_, reason))

  private def configChecks(source: ConfigSource, options: DoctorOptions, ports: DoctorPorts)(using
    registry: ProviderRegistry
  ): List[DoctorCheck] =
    readConfig(source) match {
      case Left(problem) =>
        DoctorCheck
          .fail(ConfigStep, s"the configuration could not be read: $problem")
          .withFix("fix the syntax of application.conf (HOCON), or point --config at a file that exists") ::
          skipped("needs a readable configuration", AfterConfig)
      case Right(config) =>
        val sections = providerSections(config)
        val names    = sections.keys.toList.sorted
        if (names.isEmpty) {
          DoctorCheck
            .fail(ConfigStep, "readable, but there is no section under llm4s.providers")
            .withFix(
              """add one to application.conf: llm4s.providers { provider = "main", main { provider = "ollama", model = "llama3" } }"""
            ) :: skipped("needs a provider section", AfterConfig)
        } else {
          val readable = DoctorCheck.pass(
            ConfigStep,
            s"readable; llm4s.providers has ${names.size} section(s): ${names.mkString(", ")}"
          )
          readable :: defaultChecks(source, config, sections, names, options, ports)
        }
    }

  private def defaultChecks(
    source: ConfigSource,
    config: Config,
    sections: Map[String, ConfigObject],
    names: List[String],
    options: DoctorOptions,
    ports: DoctorPorts
  )(using registry: ProviderRegistry): List[DoctorCheck] = {
    val selected = text(config, "llm4s.providers.provider")
    selected match {
      case None =>
        DoctorCheck
          .fail(Default, s"llm4s.providers.provider is not set (sections: ${names.mkString(", ")})")
          .withFix(
            s"""set llm4s.providers.provider = "${names.head}" in application.conf, or name another section"""
          ) ::
          skipped("needs a default provider", AfterConfig.drop(1))
      case Some(name) if !sections.contains(name) =>
        DoctorCheck
          .fail(Default, s"llm4s.providers.provider names '$name', but there is no such section")
          .withFix(
            s"""set llm4s.providers.provider to one of: ${names.mkString(", ")} (or add a section called '$name')"""
          ) :: skipped("needs a default provider", AfterConfig.drop(1))
      case Some(name) =>
        DoctorCheck.pass(Default, s"'$name'") ::
          moduleChecks(source, name, sections(name), options, ports)
    }
  }

  private def moduleChecks(
    source: ConfigSource,
    name: String,
    section: ConfigObject,
    options: DoctorOptions,
    ports: DoctorPorts
  )(using registry: ProviderRegistry): List[DoctorCheck] = {
    val path = s"llm4s.providers.$name"
    value(section, "provider") match {
      case None =>
        DoctorCheck
          .fail(Module, s"section '$name' has no provider key")
          .withFix(s"""add provider = "<id>" under $path (registered: ${registry.ids.mkString(", ")})""") ::
          skipped("needs a provider", AfterConfig.drop(2))
      case Some(raw) =>
        val id = registry.canonicalId(raw)
        registry.find(id) match {
          case None =>
            val message =
              registry.resolve(id, Some(s"$path.provider")).fold(_.message, _ => s"'$raw' is not registered")
            DoctorCheck
              .fail(Module, message)
              .withFix(
                s"add the LLM4S module that supplies '${id.asString}' to your build, or use a registered provider: " +
                  registry.ids.mkString(", ")
              ) :: skipped("needs the provider module", AfterConfig.drop(2))
          case Some(descriptor) =>
            val found = DoctorCheck.pass(Module, s"'${id.asString}' is registered")
            found :: keyAndConfigChecks(source, name, section, descriptor, options, ports)
        }
    }
  }

  private def keyAndConfigChecks(
    source: ConfigSource,
    name: String,
    section: ConfigObject,
    descriptor: ProviderDescriptor,
    options: DoctorOptions,
    ports: DoctorPorts
  )(using registry: ProviderRegistry): List[DoctorCheck] = {
    val key = keyCheck(source, name, section, descriptor)
    if (key.status == CheckStatus.Fail) {
      key :: skipped("needs the API key", List(Section, LocalModel, Live))
    } else {
      val built = Llm4sConfig.providerSection(source, name).flatMap(s => Llm4sConfig.providerFrom(source).map(s -> _))
      val sectionC = sectionCheck(name, built)
      val rest = built match {
        case Left(_) => skipped("needs a valid provider config", List(LocalModel, Live))
        case Right((validated, providerConfig)) =>
          List(localCheck(descriptor, validated, options, ports), liveCheck(name, providerConfig, options, ports))
      }
      key :: sectionC :: rest
    }
  }

  private def keyCheck(
    source: ConfigSource,
    name: String,
    section: ConfigObject,
    descriptor: ProviderDescriptor
  ): DoctorCheck =
    if (!descriptor.configSpec.requiresApiKey) {
      DoctorCheck.pass(Key, s"'${descriptor.id.asString}' needs no API key")
    } else {
      val ownPath = s"llm4s.providers.$name.apiKey"
      SharedCredentials.read(source).resolve(value(section, "apiKey"), ownPath, descriptor.id) match {
        case Right(Some(resolved)) => DoctorCheck.pass(Key, s"found at ${resolved.path} (the value is not shown)")
        case Right(None) =>
          DoctorCheck
            .fail(Key, s"no API key for section '$name'")
            .withFix(
              SharedCredentials.missingKeyHint(descriptor.configSpec.apiKeyEnv, descriptor.id, s"llm4s.providers.$name")
            )
        case Left(error) =>
          DoctorCheck
            .fail(Key, s"the shared credentials entry could not be read: ${error.message}")
            .withFix(
              s"make ${SharedCredentials.apiKeyPath(descriptor.id)} a string, or set apiKey under llm4s.providers.$name"
            )
      }
    }

  private def sectionCheck(name: String, built: Result[(NamedProviderConfig, ProviderConfig)]): DoctorCheck =
    built match {
      case Right((validated, _)) =>
        DoctorCheck.pass(Section, s"section '$name' is valid (model ${validated.model.asString})")
      case Left(error) =>
        DoctorCheck
          .fail(Section, error.message)
          .withFix(
            s"correct llm4s.providers.$name; the keys each provider accepts are in docs/getting-started/configuration"
          )
    }

  private val Loopback = Set("localhost", "127.0.0.1", "::1", "[::1]")

  private def isLoopback(url: String): Boolean =
    Try(new java.net.URI(url)).toOption
      .flatMap(uri => Option(uri.getHost))
      .exists(host => Loopback.contains(host.toLowerCase(Locale.ROOT)))

  private def sameModel(wanted: String, available: String): Boolean = {
    def norm(model: String) = model.trim.toLowerCase(Locale.ROOT).stripSuffix(":latest")
    norm(wanted) == norm(available)
  }

  private def localCheck(
    descriptor: ProviderDescriptor,
    section: NamedProviderConfig,
    options: DoctorOptions,
    ports: DoctorPorts
  ): DoctorCheck = {
    val base = section.baseUrl.map(_.asUrl).orElse(descriptor.configSpec.defaultBaseUrl)
    base match {
      case Some(url) if isLoopback(url) =>
        if (options.offline) DoctorCheck.skipped(LocalModel, s"$url is local, but --offline was given")
        else if (descriptor.modelLister.isEmpty)
          DoctorCheck.skipped(
            LocalModel,
            s"'${descriptor.id.asString}' cannot list its models, so $url was not contacted"
          )
        else {
          val wanted = section.model.asString
          ports.listModels(descriptor, section) match {
            case Left(error) =>
              DoctorCheck
                .fail(LocalModel, s"nothing usable answered at $url: ${error.message}")
                .withFix(
                  if (descriptor.id.asString == "ollama")
                    "start it with `ollama serve`, and check llm4s.providers baseUrl / OLLAMA_BASE_URL"
                  else s"start the model server at $url, or correct the baseUrl"
                )
            case Right(models) if models.exists(sameModel(wanted, _)) =>
              DoctorCheck.pass(LocalModel, s"$url answered, and has '$wanted'")
            case Right(models) =>
              val have = if (models.isEmpty) "no models" else models.mkString(", ")
              DoctorCheck
                .fail(LocalModel, s"$url answered, but it does not have '$wanted' (it has: $have)")
                .withFix(
                  if (descriptor.id.asString == "ollama") s"run `ollama pull $wanted`, or set the model to one it has"
                  else s"load '$wanted' on the server, or set the model to one it has"
                )
          }
        }
      case Some(url) =>
        DoctorCheck.skipped(
          LocalModel,
          s"$url is not a local server, so it was not contacted (use --live for one request)"
        )
      case None =>
        DoctorCheck.skipped(LocalModel, "the provider has no base URL to contact")
    }
  }

  private def liveCheck(
    name: String,
    providerConfig: ProviderConfig,
    options: DoctorOptions,
    ports: DoctorPorts
  ): DoctorCheck =
    if (!options.live) {
      DoctorCheck.skipped(Live, "not requested: --live sends one small request, which a hosted provider bills")
    } else {
      val future = Future(ports.live(providerConfig))(LiveContext)
      Try(Await.result(future, options.liveTimeout)) match {
        case Success(Right(model)) => DoctorCheck.pass(Live, s"the provider answered one small request (model $model)")
        case Success(Left(error))  => liveFailure(name, error)
        case Failure(_: TimeoutException) =>
          DoctorCheck
            .fail(Live, s"no answer within ${options.liveTimeout.toMillis} ms")
            .withFix("check the base URL, your network or proxy, and the provider's status page")
        case Failure(other) =>
          DoctorCheck.fail(Live, s"the request threw ${other.getClass.getSimpleName}")
      }
    }

  private def liveFailure(name: String, error: LLMError): DoctorCheck = {
    val path = s"llm4s.providers.$name"
    error match {
      case _: AuthenticationError =>
        DoctorCheck
          .fail(Live, s"the provider rejected the credentials: ${error.message}")
          .withFix(s"check that the key for $path is valid and has access to the model")
      case _: RateLimitError =>
        DoctorCheck
          .warn(Live, s"the provider is rate limiting this key: ${error.message}")
          .withFix("the setup is fine; wait and retry, or check the plan's limits")
      case _: NotFoundError =>
        DoctorCheck
          .fail(Live, s"the model or endpoint was not found: ${error.message}")
          .withFix(s"check that $path.model is a model this provider serves, and the baseUrl")
      case _: TimeoutError =>
        DoctorCheck
          .fail(Live, s"the request timed out: ${error.message}")
          .withFix("check your network or proxy and the provider's status page")
      case _: NetworkError =>
        DoctorCheck
          .fail(Live, s"the provider could not be reached: ${error.message}")
          .withFix(s"check your network or proxy, and the baseUrl in $path")
      case _: ServiceError =>
        DoctorCheck
          .fail(Live, s"the provider answered with an error: ${error.message}")
          .withFix("see the provider's status page; if it persists, check the model name")
      case _ =>
        DoctorCheck.fail(Live, error.message)
    }
  }

  private object DaemonThreads extends ThreadFactory {
    override def newThread(task: Runnable): Thread = {
      val thread = new Thread(task, "llm4s-doctor-live")
      thread.setDaemon(true)
      thread
    }
  }

  // Daemon threads: a request that outlives its timeout must not keep the JVM alive.
  private lazy val LiveContext: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool(DaemonThreads))

  private def readConfig(source: ConfigSource): Either[String, Config] =
    source
      .value()
      .left
      .map(_.prettyPrint().linesIterator.map(_.trim).filter(_.nonEmpty).mkString(" "))
      .flatMap {
        case root: ConfigObject => Right(root.toConfig)
        case _                  => Left("the top level of the configuration is not an object")
      }

  private def providerSections(config: Config): Map[String, ConfigObject] =
    Try(config.getObject("llm4s.providers")).toOption
      .map(_.asScala.toList.collect { case (name, section: ConfigObject) => name -> section }.toMap)
      .getOrElse(Map.empty)

  private def text(config: Config, path: String): Option[String] =
    Try(config.getString(path)).toOption.map(_.trim).filter(_.nonEmpty)

  private def value(section: ConfigObject, key: String): Option[String] =
    Option(section.get(key))
      .flatMap(v => Try(v.unwrapped()).toOption)
      .collect { case s: String => s.trim }
      .filter(_.nonEmpty)
}

/**
 * `llm4s doctor` as a command line: `sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli [options]"`.
 *
 * Options: `--config <file>` to check a file layered over every module's `reference.conf` (as
 * `CheckPolicies` does) instead of the application's own configuration, `--live` to make one small
 * request, `--offline` to leave a local server alone, `--timeout <seconds>` for that request, and `--json`.
 * Exit code: `0` everything passed, `1` warnings only, `2` something failed.
 */
object DoctorCli {

  /** The command line, parsed. */
  final private[configpolicy] case class Invocation(options: DoctorOptions, json: Boolean, config: Option[String])

  private[configpolicy] def parse(args: Array[String]): Invocation = {
    val timeout = argValue(args, "--timeout").flatMap(_.toIntOption).filter(_ > 0).map(_.seconds)
    val options = timeout.foldLeft(
      DoctorOptions().withLive(args.contains("--live")).withOffline(args.contains("--offline"))
    )(_.withLiveTimeout(_))
    Invocation(options, args.contains("--json"), argValue(args, "--config"))
  }

  /** Runs the doctor for `args` and returns what to print and the exit code. */
  private[configpolicy] def execute(args: Array[String], ports: DoctorPorts = DoctorPorts.system)(using
    ProviderRegistry
  ): (String, Int) = {
    val invocation = parse(args)
    val report     = Doctor.run(CheckPolicies.sourceFor(invocation.config), invocation.options, ports)
    (if (invocation.json) report.renderJson else report.renderText, report.exitCode)
  }

  def main(args: Array[String]): Unit = {
    val (output, exitCode) = execute(args)
    println(output)
    sys.exit(exitCode)
  }

  private def argValue(args: Array[String], name: String): Option[String] = {
    val idx = args.indexOf(name)
    if (idx >= 0 && idx + 1 < args.length) Some(args(idx + 1))
    else args.find(_.startsWith(name + "=")).map(_.stripPrefix(name + "="))
  }
}
