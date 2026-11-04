package org.llm4s.javaapi.rag;

import java.util.List;

import org.llm4s.javaapi.JEmbeddingClient;
import org.llm4s.javaapi.JEmbeddings;
import org.llm4s.javaapi.JLlmClient;

/**
 * The RAG and embedding blocks of {@code docs/guide/java-migration.md}, compiled with javac against this module's
 * public API and run by {@code JavaMigrationRagSpec} against word-count embeddings and an echoing model.
 * {@code JavaMigrationGuideSpec} in {@code llm4s-java-api} checks that each block of the guide is word for word in
 * this file or in the other files that hold the guide's Java.
 */
final class MigrationRagSnippets {

    private MigrationRagSnippets() {}

    /** "Embeddings". */
    static double embeddings(JEmbeddingClient embedder) {
        List<float[]> vectors = embedder.embed(List.of("The cat sat on the mat.", "A cat lay on the mat.")).get().vectors();
        double similarity = JEmbeddings.cosineSimilarity(vectors.get(0), vectors.get(1));

        return similarity;
    }

    /** "RAG": ingest, then ask. */
    static String rag(JEmbeddingClient embedder, JLlmClient client) {
        try (JRag rag = JRag.builder().embeddings(embedder).llm(client).build().get()) {
            rag.ingestText("Refunds are paid within 14 days.", "refund-policy").get();
            JRagAnswer answer = rag.ask("How long do refunds take?").get();
            return answer.answer() + " [" + answer.sources().get(0).documentId() + "]";
        }
    }
}
