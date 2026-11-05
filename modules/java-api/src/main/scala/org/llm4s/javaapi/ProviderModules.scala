package org.llm4s.javaapi

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import scala.util.Try

/**
 * Not part of the API: the provider modules `llm4s-java-api` and `llm4s-spring-boot-starter` compile against but do
 * not bring (#1496). They are optional dependencies, so a user declares the ones they call; a builder (or a starter
 * provider) whose module is absent fails with a [[org.llm4s.error.ConfigurationError ConfigurationError]] naming the
 * artifact to add, before any of the module's classes is touched, instead of a `NoClassDefFoundError`.
 */
private[llm4s] object ProviderModules {

  /** A provider module: its artifact name, and a class only it contains, whose presence shows it is on the classpath. */
  final case class Module(artifact: String, marker: String)

  val OpenAI: Module    = Module("llm4s-openai", "org.llm4s.llmconnect.provider.Llm4sOpenAIModule")
  val Anthropic: Module = Module("llm4s-anthropic", "org.llm4s.llmconnect.provider.Llm4sAnthropicModule")
  val Gemini: Module    = Module("llm4s-gemini", "org.llm4s.llmconnect.provider.Llm4sGeminiModule")
  val Ollama: Module    = Module("llm4s-ollama", "org.llm4s.llmconnect.provider.Llm4sOllamaModule")
  val OpenAICompatible: Module =
    Module("llm4s-openai-compatible", "org.llm4s.llmconnect.provider.Llm4sOpenAICompatibleModule")

  /** The loader that loaded llm4s, which sees the provider modules declared beside it. */
  private def llm4sLoader: ClassLoader = getClass.getClassLoader

  /**
   * `Right(())` when `module` is on the classpath `loader` sees (its marker is looked up, not initialised), else a
   * `ConfigurationError` that says what `what` needs and the dependency to declare for it.
   */
  def require(module: Module, what: String, loader: ClassLoader = llm4sLoader): Result[Unit] =
    Either.cond(
      Try(Class.forName(module.marker, false, loader)).isSuccess,
      (),
      ConfigurationError(
        s"$what needs ${module.artifact}, which is not on the classpath: add the dependency " +
          s"org.llm4s:${module.artifact}_3 at the same version as llm4s-java-api"
      )
    )

  /** `body` when `module` is on the classpath, else the failure [[require]] describes; `body` is not evaluated then. */
  def guard[A](module: Module, what: String, loader: ClassLoader = llm4sLoader)(body: => LlmResult[A]): LlmResult[A] =
    require(module, what, loader).fold(LlmResult.failure, _ => body)
}
