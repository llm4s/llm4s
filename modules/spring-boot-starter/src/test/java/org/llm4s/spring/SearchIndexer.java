// The embeddings service of docs/guide/spring-boot.md, compiled and run by SpringBootGuideSpec. From the first import to
// the closing brace it is the guide's snippet word for word (the spec checks that), so change the guide and this file
// together.
package org.llm4s.spring;

import java.util.List;
import org.llm4s.javaapi.JEmbeddingClient;
import org.springframework.stereotype.Service;

@Service
public class SearchIndexer {

    private final JEmbeddingClient embeddings;

    public SearchIndexer(JEmbeddingClient embeddings) {
        this.embeddings = embeddings;
    }

    public List<float[]> vectors(List<String> passages) {
        return embeddings.embed(passages).get().vectors();
    }
}
