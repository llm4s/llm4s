package org.llm4s.llmconnect.spi

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * The set of providers this build can resolve.
 *
 * A registry is an immutable value, not a mutable global: [[withProvider]] and
 * [[withModule]] return a new registry, and an application that wants a
 * different set constructs one with [[ProviderRegistry.of]] and passes it in.
 * Core code resolves a registry through a `using` clause, so the default is
 * `ProviderRegistry.default` unless the caller supplies another.
 *
 * Lookup is by `ProviderId` and never throws: an id nothing handles produces
 * a `Left` naming the ids that are registered, which is the difference between
 * a usable error and "provider openai not registered".
 */
final class ProviderRegistry private (val descriptors: Vector[ProviderDescriptor]):

  private val byId: Map[String, ProviderDescriptor] =
    descriptors.map(descriptor => descriptor.id.asString -> descriptor).toMap

  private val byAlias: Map[String, ProviderDescriptor] =
    descriptors.flatMap { descriptor =>
      descriptor.aliases.map(alias => ProviderId(alias).asString -> descriptor)
    }.toMap

  /** The registered descriptor for `id`, if any. Does not consider aliases. */
  def find(id: ProviderId): Option[ProviderDescriptor] = byId.get(id.asString)

  /**
   * The registered descriptor for `id`, or an error naming what is registered.
   *
   * @param configPath where the id came from, e.g. `"llm4s.providers.my-bedrock.provider"`.
   *                   Included in the error so the user knows which entry to fix.
   */
  def resolve(id: ProviderId, configPath: Option[String] = None): Result[ProviderDescriptor] =
    find(id).toRight {
      val origin = configPath.fold("")(path => s" (from $path)")
      ConfigurationError(
        s"Provider '${id.asString}'$origin is not registered. " +
          s"Registered providers: ${ids.mkString(", ")}. " +
          s"If you expected '${id.asString}', add the dependency that supplies it, " +
          s"or register it explicitly with ProviderRegistry.of(...)."
      )
    }

  /** The registered descriptor for `id`, or an error naming what is registered. */
  def get(id: ProviderId): Result[ProviderDescriptor] = resolve(id, None)

  /** Registered provider ids in canonical spelling, sorted. */
  def ids: Seq[String] = byId.keys.toSeq.sorted

  /**
   * Folds an alias onto the id that owns it, leaving unknown strings alone.
   *
   * `"google"` becomes `"gemini"` because the Gemini descriptor declares that
   * alias. An id no provider claims is returned canonicalised but unchanged —
   * parsing config must not depend on what happens to be on the classpath; only
   * resolution does.
   */
  def canonicalId(raw: String): ProviderId =
    val id = ProviderId(raw)
    byAlias.get(id.asString).fold(id)(_.id)

  /** This registry plus `descriptor`; a later registration of the same id wins. */
  def withProvider(descriptor: ProviderDescriptor): ProviderRegistry =
    ProviderRegistry.fromDescriptors(descriptors :+ descriptor)

  /** This registry plus every provider `module` supplies. */
  def withModule(module: Llm4sProviderModule): ProviderRegistry =
    ProviderRegistry.fromDescriptors(descriptors ++ module.chatProviders)

  override def toString: String = s"ProviderRegistry(${ids.mkString(", ")})"

object ProviderRegistry:

  /** A registry holding exactly `descriptors`; a later duplicate id wins. */
  def of(descriptors: ProviderDescriptor*): ProviderRegistry =
    fromDescriptors(descriptors.toVector)

  /** A registry holding everything `modules` supply. */
  def ofModules(modules: Llm4sProviderModule*): ProviderRegistry =
    fromDescriptors(modules.toVector.flatMap(_.chatProviders))

  private def fromDescriptors(descriptors: Vector[ProviderDescriptor]): ProviderRegistry =
    // Deduplicate by id keeping the *last* registration, so `withProvider` overrides rather
    // than silently losing to what is already there - a user-supplied descriptor must be able
    // to replace a built-in one of the same name.
    val deduplicated =
      descriptors.reverse.distinctBy(_.id.asString).reverse
    new ProviderRegistry(deduplicated)

  /** The providers built into `llm4s-core`. */
  lazy val builtin: ProviderRegistry =
    ofModules(org.llm4s.llmconnect.provider.BuiltinProviders)

  /**
   * The registry used when a caller supplies none.
   *
   * Classpath discovery replaces this in PR 3 of
   * [[https://github.com/llm4s/llm4s/issues/1131 #1131]]; today it is exactly
   * the built-in set.
   */
  lazy val default: ProviderRegistry = builtin

  /** Resolves to `default` wherever a `using ProviderRegistry` is needed. */
  given ProviderRegistry = default
