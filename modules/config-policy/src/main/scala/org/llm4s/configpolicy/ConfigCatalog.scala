package org.llm4s.configpolicy

enum CatalogEnvironment:
  case Dev, Staging, Prod

object CatalogEnvironment {
  def fromString(value: String): CatalogEnvironment =
    value.toLowerCase match {
      case "dev"     => CatalogEnvironment.Dev
      case "staging" => CatalogEnvironment.Staging
      case _         => CatalogEnvironment.Prod
    }
}

final case class PromptId(value: String) extends AnyVal
final case class ModelId(value: String)  extends AnyVal

final case class CatalogEntry(
  promptId: PromptId,
  version: String,
  modelId: ModelId,
  environment: CatalogEnvironment,
  rolloutStatus: String
)

final class InMemoryCatalog {
  private var entries: List[CatalogEntry] = Nil

  def register(entry: CatalogEntry): Unit =
    entries = entry :: entries

  def active(environment: CatalogEnvironment): List[CatalogEntry] =
    entries.filter(e => e.environment == environment && e.rolloutStatus == "active")
}

