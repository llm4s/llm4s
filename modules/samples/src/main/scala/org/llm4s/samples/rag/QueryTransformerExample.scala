package org.llm4s.samples.rag

import org.llm4s.config.Llm4sConfig
import org.llm4s.error.{ ProcessingError, ValidationError }
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.transform.{ LLMQueryRewriter, QueryTransformer }
import org.llm4s.rag.{ RAG, RAGSearchResult }
import org.llm4s.samples.cookbook.{ BagOfWordsEmbeddings, ScriptedClient }
import org.llm4s.types.Result

import java.util.concurrent.atomic.AtomicReference
import scala.util.Using

final case class QueryTransformerRanking(label: String, transformedQuery: String, topHit: RAGSearchResult)

final case class QueryTransformerExampleResult(
  rankings: Vector[QueryTransformerRanking],
  chained: QueryTransformerRanking,
  propagatedError: String
)

/**
 * Shows how RAG query transformers change retrieval with abbreviation expansion, a scripted LLM rewriter, chaining,
 * and error propagation.
 *
 * Run offline with: `sbt "samples/runMain org.llm4s.samples.rag.QueryTransformerExample"`
 */
object QueryTransformerExample {
  val Query: String = "k8s pod OOM"

  private val RewrittenQuery = "Kubernetes pods out of memory OOMKilled"
  private val Documents = Vector(
    "kubernetes"     -> "Kubernetes pods that exceed their memory limit are terminated with OOMKilled.",
    "alert-glossary" -> "The shorthand k8s pod OOM appears in a monitoring alert.",
    "postgres"       -> "PostgreSQL indexes speed up database queries.",
    "scala"          -> "Scala applications run on the JVM.",
    "networking"     -> "A load balancer distributes network traffic across services."
  )

  final class AbbreviationExpander extends QueryTransformer {
    val name: String = "abbreviation-expander"

    def transform(query: String): Result[String] = Right(
      query
        .split("\\s+")
        .map {
          case token if token.equalsIgnoreCase("k8s") => "Kubernetes"
          case token if token.equalsIgnoreCase("OOM") => "out of memory OOMKilled"
          case token                                  => token
        }
        .mkString(" ")
    )
  }

  final private class AddFailureContext extends QueryTransformer {
    val name: String                             = "add-failure-context"
    def transform(query: String): Result[String] = Right(s"$query memory limit terminated")
  }

  final private class FailingTransformer extends QueryTransformer {
    val name: String                             = "failing-transformer"
    def transform(query: String): Result[String] = Left(ProcessingError(name, "intentional sample failure"))
  }

  final private class RecordingTransformer(delegate: QueryTransformer) extends QueryTransformer {
    private val recorded = new AtomicReference(Option.empty[String])
    val name: String     = s"recording-${delegate.name}"

    def transform(query: String): Result[String] = delegate.transform(query).map { transformed =>
      recorded.set(Some(transformed))
      transformed
    }

    def transformedQuery: Result[String] = recorded
      .get()
      .toRight(ValidationError("query transformer example", s"$name did not run"))
  }

  def run(): Result[QueryTransformerExampleResult] = {
    given ProviderRegistry = ProviderRegistry.default.withEmbeddingProvider(BagOfWordsEmbeddings)

    val expanded  = new RecordingTransformer(new AbbreviationExpander)
    val llmClient = new ScriptedClient((_, _) => Right(AssistantMessage(RewrittenQuery)))
    val rewritten = new RecordingTransformer(LLMQueryRewriter(llmClient))
    val chained   = new RecordingTransformer(new AddFailureContext)

    for {
      models <- Llm4sConfig.modelRegistryService()
      result <- {
        given ModelRegistryService = models
        for {
          plain <- queryOnce("no transformer", Seq.empty, () => Right(Query))
          abbreviation <- queryOnce(
            "abbreviation expander",
            Seq(expanded),
            () => expanded.transformedQuery
          )
          llm <- queryOnce("scripted LLM rewriter", Seq(rewritten), () => rewritten.transformedQuery)
          chain <- queryOnce(
            "two-transformer chain",
            Seq(new AbbreviationExpander, chained),
            () => chained.transformedQuery
          )
          failure <- queryFailure(Seq(new FailingTransformer))
        } yield QueryTransformerExampleResult(Vector(plain, abbreviation, llm), chain, failure)
      }
    } yield result
  }

  private def config(transformers: Seq[QueryTransformer]) = RAG
    .builder()
    .withEmbeddings(BagOfWordsEmbeddings.id.asString, BagOfWordsEmbeddings.Model, BagOfWordsEmbeddings.Dimensions)
    .withTopK(1)
    .withQueryTransformers(transformers)

  private def queryOnce(
    label: String,
    transformers: Seq[QueryTransformer],
    transformedQuery: () => Result[String]
  )(using ModelRegistryService, ProviderRegistry): Result[QueryTransformerRanking] =
    withRag(transformers) { rag =>
      for {
        results <- rag.query(Query)
        top     <- results.headOption.toRight(ValidationError("query transformer example", "query returned no hits"))
        transformed <- transformedQuery()
      } yield QueryTransformerRanking(label, transformed, top)
    }

  private def queryFailure(
    transformers: Seq[QueryTransformer]
  )(using ModelRegistryService, ProviderRegistry): Result[String] =
    withRag(transformers) { rag =>
      rag.query(Query) match {
        case Left(error) => Right(error.formatted)
        case Right(_)    => Left(ValidationError("query transformer example", "failing transformer did not fail"))
      }
    }

  private def withRag[A](
    transformers: Seq[QueryTransformer]
  )(run: RAG => Result[A])(using ModelRegistryService, ProviderRegistry): Result[A] =
    RAG
      .build(config(transformers), _ => Right(BagOfWordsEmbeddings.config))
      .flatMap(rag => Using.resource(rag)(scoped => ingestAll(scoped).flatMap(_ => run(scoped))))

  private def ingestAll(rag: RAG): Result[Unit] =
    Documents.foldLeft[Result[Unit]](Right(())) { case (done, (id, content)) =>
      done.flatMap(_ => rag.ingestText(content, id).map(_ => ()))
    }

  def main(args: Array[String]): Unit =
    run() match {
      case Right(result) =>
        (result.rankings :+ result.chained).foreach { ranking =>
          println(s"${ranking.label}: '${ranking.transformedQuery}' -> ${ranking.topHit.id}: ${ranking.topHit.content}")
        }
        println(s"failing transformer: ${result.propagatedError}")
      case Left(error) =>
        System.err.println(s"Query transformer example failed: ${error.formatted}")
        sys.exit(1)
    }
}
