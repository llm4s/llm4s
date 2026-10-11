package org.llm4s.kotlin

import org.llm4s.error.LLMError
import org.llm4s.javaapi.JEmbeddingClient
import org.llm4s.javaapi.JEmbeddingPurpose
import org.llm4s.javaapi.JEmbeddings
import org.llm4s.javaapi.LlmErrorKind
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.EmbeddingError
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.llmconnect.model.EmbeddingResponse
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelMetadata
import scala.Option
import scala.jdk.javaapi.CollectionConverters.asJava
import scala.jdk.javaapi.CollectionConverters.asScala
import scala.util.Either
import scala.util.Left
import scala.util.Right
import java.util.OptionalInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** An embedding provider that records each request and answers [reply]; Scala types appear only in building it. */
internal class RecordingEmbedder(
    private val reply: (EmbeddingRequest) -> Either<LLMError, EmbeddingResponse>,
) : EmbeddingProvider {
    val sent = mutableListOf<EmbeddingRequest>()

    override fun embed(request: EmbeddingRequest): Either<LLMError, EmbeddingResponse> {
        sent += request
        return reply(request)
    }
}

/** One vector per text: the text's length, then its index. */
internal fun perText(request: EmbeddingRequest): Either<LLMError, EmbeddingResponse> {
    val texts = asJava(request.input())
    val vectors = texts.mapIndexed { i, text -> asScala(listOf<Any>(text.length.toDouble(), i.toDouble())).toSeq() }
    return Right(
        EmbeddingResponse.apply(
            asScala(vectors).toSeq(),
            scala.collection.immutable.`Map$`.`MODULE$`.empty<String, String>(),
            Option.empty(),
            Option.empty(),
            Option.empty(),
        ),
    )
}

internal fun embedderOver(provider: EmbeddingProvider): JEmbeddingClient {
    val registry = org.llm4s.model.`ModelRegistryService$`.`MODULE$`.fromModels(asScala(listOf<ModelMetadata>()))
    return JEmbeddingClient(EmbeddingClient(provider, Option.empty(), "embedding", registry), EmbeddingModelConfig("embed-model", 1536))
}

/**
 * Kotlin embeds texts and compares their vectors through `llm4s-java-api`'s [JEmbeddingClient] (#1490): a batch in one
 * request, the purpose reaching it, a provider's error as a failed result with its kind, and the cosine helper.
 */
class EmbeddingTest {

    @Test
    fun `embed sends the whole batch once and returns one vector per text, in order`() {
        val provider = RecordingEmbedder(::perText)
        val embeddings: JEmbeddings = embedderOver(provider).embed(listOf("a", "bb", "ccc")).get()

        assertEquals(listOf(listOf("a", "bb", "ccc")), provider.sent.map { asJava(it.input()) })
        assertEquals(listOf(listOf(1f, 0f), listOf(2f, 1f), listOf(3f, 2f)), embeddings.vectors().map { it.toList() })
        assertEquals(2, embeddings.dimensions())
        assertEquals("embed-model", embeddings.model())
    }

    @Test
    fun `the purpose reaches the request, and documents are the default`() {
        val provider = RecordingEmbedder(::perText)
        val client = embedderOver(provider)
        client.embed(listOf("doc"))
        client.embed(listOf("question"), JEmbeddingPurpose.QUERY)

        // core's InputPurpose is a Scala 3 enum, read here by its case names
        assertEquals(listOf("Document", "Query"), provider.sent.map { it.purpose().toString() })
    }

    @Test
    fun `a provider's error is a failed result whose kind its status gives`() {
        val failing = embedderOver(RecordingEmbedder { Left(EmbeddingError.apply(Option.apply("401"), "bad key", "voyage")) })
        val error = failing.embed(listOf("a")).error

        assertEquals(LlmErrorKind.AUTHENTICATION, error.kind)
        assertEquals(OptionalInt.of(401), error.statusCode)
        assertEquals("bad key", error.message)
    }

    @Test
    fun `cosine similarity compares two vectors, a zero vector giving 0`() {
        assertEquals(0.96, JEmbeddings.cosineSimilarity(floatArrayOf(3f, 4f), floatArrayOf(4f, 3f)))
        assertEquals(0.0, JEmbeddings.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)))
        assertFailsWith<IllegalArgumentException> { JEmbeddings.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f)) }
        assertTrue(JEmbeddings.cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)) > 0.999)
    }
}
