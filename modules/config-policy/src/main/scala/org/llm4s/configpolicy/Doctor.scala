package org.llm4s.configpolicy

import org.llm4s.config.{ Llm4sConfig, SharedCredentials }
import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderName, RawNamedProviderSection }
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
import scala.util.{ Failure, Success, Try, Using }

/**
 * What a doctor run needs from the outside world, so tests can replace each piece.
 *
 * @param javaFeature the running JDK's feature version, e.g. `21`
 * @param listModels  asks a provider's own model lister which models a local server has
 * @param live        makes one small request with the given provider config, through the run's registry,
 *                    and returns the model that answered
 */
final case class DoctorPorts private (
  javaFeature: () => Int,
  listModels: (ProviderDescriptor, NamedProviderConfig) => Result[List[String]],
  live: (ProviderConfig, ProviderRegistry) => Result[String]
) {
  def withJavaFeature(javaFeature: () => Int): DoctorPorts = copy(javaFeature = javaFeature)
  def withListModels(listModels: (ProviderDescriptor, NamedProviderConfig) => Result[List[String]]): DoctorPorts =
    copy(listModels = listModels)
  def withLive(live: (ProviderConfig, ProviderRegistry) => Result[String]): DoctorPorts = copy(live = live)
}

object DoctorPorts {

  def apply(
    javaFeature: () => Int,
    listModels: (ProviderDescriptor, NamedProviderConfig) => Result[List[String]],
    live: (ProviderConfig, ProviderRegistry) => Result[String]
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

  // The run's registry, not `ProviderRegistry.default`: a provider registered explicitly (or found on
  // `--classpath`) must be the one the request goes through. The client is closed after its one request.
  private def systemLive(config: ProviderConfig, registry: ProviderRegistry): Result[String] =
    for {
      service      <- Llm4sConfig.modelRegistryService()
      conversation <- Conversation.userOnly("Reply with the single word: ok")
      client       <- LLMConnect.getClient(config)(using service, registry)
      completion   <- Using.resource(client)(_.complete(conversation, CompletionOptions().withMaxTokens(8)))
    } yield completion.model
}

/**
 * Checks a whole LLM4S setup in one run and says what to change when a step fails.
 *
 * The steps, in order: the JDK, the configuration, the default provider, its module on the classpath, its
 * API key, its config, a local model server (only with [[DoctorOptions.local]]), one small request (only with
 * [[DoctorOptions.live]]), and the configuration's other provider sections.
 * A step that cannot run because an earlier one failed is reported as skipped, not as failed.
 *
 * By default nothing leaves the machine and no server is contacted, not even a local one: the network is
 * used only when a run asks for it. The configuration is read through `Llm4sConfig`, so its sections, the
 * default and the shared `llm4s.credentials` mean exactly what they mean to an application. The API key's
 * value is never put in the report: only the config path it came from is shown, and every key, header value
 * and base-URL credential the configuration holds is hidden by value in every message as well. A URL is
 * printed without its user info, query or fragment.
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
  private val Others     = "Other sections"

  private type Sections = Map[ProviderName, Result[RawNamedProviderSection]]

  /**
   * Runs every check against `source`.
   *
   * @param source   where the configuration comes from (see `CheckPolicies.sourceFor`)
   * @param options  what the run may do
   * @param ports    the outside world, replaceable in tests
   * @param registry the providers to check the configuration against: the application's, when the CLI is
   *                 given `--classpath`
   */
  def run(
    source: ConfigSource,
    options: DoctorOptions = DoctorOptions(),
    ports: DoctorPorts = DoctorPorts.system
  )(using registry: ProviderRegistry): DoctorReport = {
    val block   = Llm4sConfig.providerBlock(source)
    val secrets = knownSecrets(source)
    DoctorReport(
      (javaCheck(ports.javaFeature()) :: configChecks(source, block, options, ports)).map(_.scrubbed(secrets))
    )
  }

  // Shorter values are left alone: hiding a three-letter "key" would mangle ordinary words in the report.
  private val MinSecretLength = 8

  /**
   * Every secret the configuration holds, so the report can hide them by value as well as by shape:
   * the shared credentials and, from every section under `llm4s.providers` (including one that cannot be
   * read), its API key, its header values and the user info, query and fragment of its base URL.
   * Redaction recognises the shapes of known vendors' keys; an error message that echoes a key of
   * another shape (a gateway token, a test key) would otherwise reach the output.
   */
  private def knownSecrets(source: ConfigSource): Set[String] = {
    val shared = SharedCredentials.read(source).entries.values.toList.flatMap(_.toOption)
    (shared ::: sectionSecrets(source)).map(_.trim).filter(_.length >= MinSecretLength).toSet
  }

  /** A header value, and each word of it, so `Bearer <token>` hides the token when it is echoed alone. */
  private def headerSecrets(value: String): List[String] = value :: value.split("\\s+").toList

  // Read straight from the HOCON rather than through the typed reader, so a section that reader rejects
  // (a mistyped key beside a real token) still has its secrets hidden.
  private def sectionSecrets(source: ConfigSource): List[String] = {
    import com.typesafe.config.{ ConfigObject, ConfigValue, ConfigValueType }
    import scala.jdk.CollectionConverters.*
    def scalar(value: ConfigValue): Option[String] =
      Option(value)
        .filter(v => v.valueType != ConfigValueType.OBJECT && v.valueType != ConfigValueType.LIST)
        .flatMap(v => Option(v.unwrapped))
        .map(_.toString)
    def objects(value: ConfigValue): List[ConfigObject] = value match {
      case obj: ConfigObject => obj.asScala.values.toList.collect { case child: ConfigObject => child }
      case _                 => Nil
    }
    source.at("llm4s.providers").value().toOption.toList.flatMap(objects).flatMap { section =>
      val headers = Option(section.get("headers")).toList.collect { case h: ConfigObject => h }
      Option(section.get("apiKey")).flatMap(scalar).toList :::
        headers.flatMap(_.asScala.values.toList.flatMap(scalar)).flatMap(headerSecrets) :::
        Option(section.get("baseUrl")).flatMap(scalar).toList.flatMap(DoctorUrls.secretsIn)
    }
  }

  private def javaCheck(feature: Int): DoctorCheck =
    if feature >= MinimumJava then DoctorCheck.pass("JDK", s"Java $feature (LLM4S needs $MinimumJava or newer)")
    else
      DoctorCheck
        .fail("JDK", s"Java $feature is too old: LLM4S needs $MinimumJava or newer")
        .withFix(s"run with a JDK $MinimumJava or newer (set JAVA_HOME, or choose it in your IDE and build tool)")

  private val AfterConfig  = List(Default, Module, Key, Section, LocalModel, Live, Others)
  private val AfterDefault = List(Module, Key, Section, LocalModel, Live)

  private def skipped(reason: String, names: List[String]): List[DoctorCheck] =
    names.map(DoctorCheck.skipped(_, reason))

  private def configChecks(
    source: ConfigSource,
    block: Result[(Option[ProviderName], Sections)],
    options: DoctorOptions,
    ports: DoctorPorts
  )(using registry: ProviderRegistry): List[DoctorCheck] =
    block match {
      case Left(problem) =>
        DoctorCheck
          .fail(ConfigStep, s"the configuration could not be read: ${problem.message}")
          .withFix("fix the syntax of application.conf (HOCON), or point --config at a file that exists") ::
          skipped("needs a readable configuration", AfterConfig)
      case Right((_, sections)) if sections.isEmpty =>
        DoctorCheck
          .fail(ConfigStep, "readable, but there is no section under llm4s.providers")
          .withFix(
            """add one to application.conf: llm4s.providers { provider = "main", main { provider = "ollama", model = "llama3" } }"""
          ) :: skipped("needs a provider section", AfterConfig)
      case Right((selected, sections)) =>
        val names = sections.keys.map(_.asName).toList.sorted
        val readable = DoctorCheck.pass(
          ConfigStep,
          s"readable; llm4s.providers has ${names.size} section(s): ${names.mkString(", ")}"
        )
        (readable :: defaultChecks(source, selected, sections, names, options, ports)) :+
          othersCheck(source, selected, names)
    }

  private def defaultChecks(
    source: ConfigSource,
    selected: Option[ProviderName],
    sections: Sections,
    names: List[String],
    options: DoctorOptions,
    ports: DoctorPorts
  )(using registry: ProviderRegistry): List[DoctorCheck] =
    selected match {
      case None =>
        DoctorCheck
          .fail(Default, s"llm4s.providers.provider is not set (sections: ${names.mkString(", ")})")
          .withFix(
            s"""set llm4s.providers.provider = "${names.head}" in application.conf, or name another section"""
          ) ::
          skipped("needs a default provider", AfterDefault)
      case Some(name) =>
        sections.get(name) match {
          case None =>
            DoctorCheck
              .fail(Default, s"llm4s.providers.provider names '${name.asName}', but there is no such section")
              .withFix(
                s"""set llm4s.providers.provider to one of: ${names.mkString(
                    ", "
                  )} (or add a section called '${name.asName}')"""
              ) :: skipped("needs a default provider", AfterDefault)
          case Some(Left(error)) =>
            DoctorCheck
              .fail(Default, s"'${name.asName}' is the default, but its section could not be read: ${error.message}")
              .withFix(s"correct llm4s.providers.${name.asName}: each key must have the type the provider expects") ::
              skipped("needs a readable default section", AfterDefault)
          case Some(Right(section)) =>
            DoctorCheck.pass(Default, s"'${name.asName}'") ::
              moduleChecks(source, name.asName, section, options, ports)
        }
    }

  private def moduleChecks(
    source: ConfigSource,
    name: String,
    section: RawNamedProviderSection,
    options: DoctorOptions,
    ports: DoctorPorts
  )(using registry: ProviderRegistry): List[DoctorCheck] = {
    val path = s"llm4s.providers.$name"
    section.provider.map(_.trim).filter(_.nonEmpty) match {
      case None =>
        DoctorCheck
          .fail(Module, s"section '$name' has no provider key")
          .withFix(s"""add provider = "<id>" under $path (registered: ${registry.ids.mkString(", ")})""") ::
          skipped("needs a provider", AfterDefault.drop(1))
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
              ) :: skipped("needs the provider module", AfterDefault.drop(1))
          case Some(descriptor) =>
            moduleFound(id, options) :: keyAndConfigChecks(source, name, section, descriptor, options, ports)
        }
    }
  }

  /** The module that supplied `id` and where it was loaded from, when the registry was discovered. */
  private def moduleFound(id: ProviderId, options: DoctorOptions)(using registry: ProviderRegistry): DoctorCheck = {
    val supplier = registry.report.modules.reverse.find(_.providerIds.contains(id.asString)).map { module =>
      val where = module.source.map { location =>
        val trimmed = location.stripSuffix("/")
        if (trimmed.endsWith(".jar")) trimmed.substring(trimmed.lastIndexOf('/') + 1) else location
      }
      s" by ${module.moduleClass}" + where.fold("")(w => s" ($w)")
    }
    val scope =
      if (options.applicationClasspath) " on the given --classpath"
      else
        "; this is the doctor's own classpath, which has every built-in provider module, " +
          "so pass --classpath to check your application's"
    DoctorCheck.pass(Module, s"'${id.asString}' is registered${supplier.getOrElse("")}$scope")
  }

  private def keyAndConfigChecks(
    source: ConfigSource,
    name: String,
    section: RawNamedProviderSection,
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
    section: RawNamedProviderSection,
    descriptor: ProviderDescriptor
  ): DoctorCheck =
    if (!descriptor.configSpec.requiresApiKey) {
      DoctorCheck.pass(Key, s"'${descriptor.id.asString}' needs no API key")
    } else {
      val ownPath = s"llm4s.providers.$name.apiKey"
      SharedCredentials.read(source).resolve(section.apiKey, ownPath, descriptor.id) match {
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
      case Some(raw) if isLoopback(raw) =>
        val url = DoctorUrls.display(raw)
        if (!options.local)
          DoctorCheck.skipped(
            LocalModel,
            s"$url is local; pass --local to ask it whether it is running and has the model"
          )
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
      case Some(raw) =>
        DoctorCheck.skipped(
          LocalModel,
          s"${DoctorUrls.display(raw)} is not a local server, so it was not contacted (use --live for one request)"
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
  )(using registry: ProviderRegistry): DoctorCheck =
    if (!options.live) {
      DoctorCheck.skipped(Live, "not requested: --live sends one small request, which a hosted provider bills")
    } else {
      val future = Future(ports.live(providerConfig, registry))(LiveContext)
      Try(Await.result(future, options.liveTimeout)) match {
        case Success(Right(model)) => DoctorCheck.pass(Live, s"the provider answered one small request (model $model)")
        case Success(Left(error))  => liveFailure(name, error)
        case Failure(_: TimeoutException) =>
          DoctorCheck
            .fail(Live, s"no answer within ${options.liveTimeout.toMillis} ms")
            .withFix("check the base URL, your network or proxy, and the provider's status page")
        case Failure(other) =>
          DoctorCheck
            .fail(Live, s"the request threw ${other.getClass.getSimpleName}")
            .withFix("run again with logging at DEBUG to see the cause, and report it if it persists")
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

  /**
   * Every section other than the default, validated as an application loading it by name would. A
   * problem there is a warning: the default works without it, but `Llm4sConfig.provider(name)` would fail.
   */
  private def othersCheck(source: ConfigSource, selected: Option[ProviderName], names: List[String])(using
    registry: ProviderRegistry
  ): DoctorCheck = {
    val others = names.filterNot(name => selected.exists(_.asName == name))
    if (others.isEmpty) DoctorCheck.skipped(Others, "there is no section besides the default")
    else {
      val problems = others.flatMap(name => Llm4sConfig.provider(source, name).left.toOption.map(name -> _.message))
      if (problems.isEmpty) DoctorCheck.pass(Others, s"${others.size} more section(s) load: ${others.mkString(", ")}")
      else
        DoctorCheck
          .warn(
            Others,
            s"${problems.size} of ${others.size} other section(s) would fail to load: " +
              problems.map { case (name, message) => s"'$name': $message" }.mkString("; ")
          )
          .withFix(
            "these are not the default, so they matter only to code that loads them by name; " +
              s"correct them, or remove them: ${problems.map(p => s"llm4s.providers.${p._1}").mkString(", ")}"
          )
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
}

/**
 * `llm4s doctor` as a command line: `sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli [options]"`.
 *
 * Options: `--config <file>` to check a file layered over every module's `reference.conf` (as
 * `CheckPolicies` does) instead of the application's own configuration, `--classpath <path>` to check the
 * provider modules, `reference.conf` files and (without `--config`) `application.conf` on an application's
 * classpath instead of the doctor's own, `--local` to ask a model
 * server on this machine which models it has, `--live` to make one small request, `--timeout <seconds>` for
 * that request, and `--json`. Without `--local` or `--live` nothing is contacted.
 * Exit code: `0` everything passed, `1` warnings only, `2` something failed.
 */
object DoctorCli {

  /** The command line, parsed. */
  final private[configpolicy] case class Invocation(
    options: DoctorOptions,
    json: Boolean,
    config: Option[String],
    classpath: Option[String]
  )

  private[configpolicy] def parse(args: Array[String]): Invocation = {
    val timeout   = argValue(args, "--timeout").flatMap(_.toIntOption).filter(_ > 0).map(_.seconds)
    val classpath = argValue(args, "--classpath").map(_.trim).filter(_.nonEmpty)
    val options = timeout.foldLeft(
      DoctorOptions()
        .withLive(args.contains("--live"))
        .withLocal(args.contains("--local"))
        .withApplicationClasspath(classpath.isDefined)
    )(_.withLiveTimeout(_))
    Invocation(options, args.contains("--json"), argValue(args, "--config"), classpath)
  }

  /**
   * A class loader for `classpath` (entries separated by the platform's path separator, as
   * `sbt "export Runtime/fullClasspath"` prints them) that finds resources on that classpath only: its
   * `META-INF/services` entries, `reference.conf` and `application.conf` files are the application's, not the
   * doctor's, which has every built-in provider module. Classes are loaded from the doctor's own classpath
   * first, so a built-in module is the doctor's copy of it, and from `classpath` otherwise, so a third-party
   * module is found too. The caller closes it.
   */
  private[configpolicy] def classLoaderFor(classpath: String): Either[String, java.net.URLClassLoader] = {
    val entries = classpath.split(java.io.File.pathSeparator).toList.map(_.trim).filter(_.nonEmpty)
    val urls    = entries.map(entry => Try(java.nio.file.Paths.get(entry).toUri.toURL).toEither.left.map(_ => entry))
    urls.collect { case Left(bad) => bad } match {
      case Nil =>
        Right(
          new java.net.URLClassLoader(
            urls.collect { case Right(url) => url }.toArray,
            DoctorCli.getClass.getClassLoader
          ) {
            // Only this classpath's own resources: the parent's would list every built-in provider.
            override def getResources(name: String): java.util.Enumeration[java.net.URL] = findResources(name)
          }
        )
      case bad => Left(s"--classpath has entries that are not paths: ${bad.mkString(", ")}")
    }
  }

  /**
   * The configuration as an application with `loader`'s classpath would load it: `-D` system properties
   * over the `--config` file (or, without one, the `application.conf` on that classpath) over the
   * `reference.conf` files on that classpath, which is where a third-party provider module binds its
   * vendor's variable to `llm4s.credentials.<id>.apiKey`. The doctor's own `reference.conf` files come last,
   * only to supply what the given classpath leaves out.
   */
  private[configpolicy] def sourceOn(loader: ClassLoader, config: Option[String]): ConfigSource = {
    val application = config.fold(ConfigSource.resources("application.conf", loader).optional)(ConfigSource.file(_))
    ConfigSource.defaultOverrides
      .withFallback(application)
      .withFallback(ConfigSource.resources("reference.conf", loader).optional)
      .withFallback(ConfigSource.defaultReference)
  }

  /** Runs the doctor for `args` and returns what to print and the exit code. */
  private[configpolicy] def execute(args: Array[String], ports: DoctorPorts = DoctorPorts.system)(using
    default: ProviderRegistry
  ): (String, Int) = {
    val invocation = parse(args)
    def render(source: ConfigSource, registry: ProviderRegistry): (String, Int) = {
      val report = Doctor.run(source, invocation.options, ports)(using registry)
      (if (invocation.json) report.renderJson else report.renderText, report.exitCode)
    }
    invocation.classpath match {
      case None => render(CheckPolicies.sourceFor(invocation.config), default)
      case Some(classpath) =>
        classLoaderFor(classpath) match {
          case Left(problem) => (s"llm4s doctor: $problem", 2)
          case Right(loader) =>
            Using.resource(loader)(l => render(sourceOn(l, invocation.config), ProviderRegistry.discover(l)))
        }
    }
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
