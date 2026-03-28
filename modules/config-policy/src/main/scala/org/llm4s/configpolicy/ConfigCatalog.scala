package org.llm4s.configpolicy

/** Deployment / policy tier for catalog and governance rules. */
enum CatalogEnvironment:
  case Dev, Staging, Prod

/**
 * Scala 3 `enum` syntax is intentional: if this module ever needs Scala 2.13
 * cross-compilation, replace with a sealed trait + case objects.
 */
object CatalogEnvironment {
  def fromString(value: String): CatalogEnvironment =
    value.toLowerCase match {
      case "dev"     => CatalogEnvironment.Dev
      case "staging" => CatalogEnvironment.Staging
      case _         => CatalogEnvironment.Prod
    }
}
