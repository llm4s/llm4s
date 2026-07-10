package org.llm4s.configpolicy

/** Deployment / policy tier for catalog and governance rules. */
sealed trait CatalogEnvironment
object CatalogEnvironment {
  case object Dev extends CatalogEnvironment
  case object Staging extends CatalogEnvironment
  case object Prod extends CatalogEnvironment

  def fromString(value: String): CatalogEnvironment =
    value.toLowerCase match {
      case "dev"     => CatalogEnvironment.Dev
      case "staging" => CatalogEnvironment.Staging
      case _         => CatalogEnvironment.Prod
    }
}
