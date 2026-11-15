package org.llm4s.quarkus.deployment;

import io.quarkus.builder.item.SimpleBuildItem;
import java.util.List;
import java.util.Optional;

/** The clients the application's {@code llm4s.*} properties configure, once they are checked. */
public final class Llm4sClientsBuildItem extends SimpleBuildItem {

    private final List<String> sections;
    private final Optional<String> defaultSection;
    private final boolean embeddings;

    Llm4sClientsBuildItem(List<String> sections, Optional<String> defaultSection, boolean embeddings) {
        this.sections = List.copyOf(sections);
        this.defaultSection = defaultSection;
        this.embeddings = embeddings;
    }

    /** The sections of {@code llm4s.providers}: each is a {@code @Named} client bean. */
    public List<String> sections() {
        return sections;
    }

    /** The section whose client is also the {@code @Default} bean. */
    public Optional<String> defaultSection() {
        return defaultSection;
    }

    /** Whether {@code llm4s.embeddings.model} is set, so there is an embedding client bean. */
    public boolean embeddings() {
        return embeddings;
    }
}
