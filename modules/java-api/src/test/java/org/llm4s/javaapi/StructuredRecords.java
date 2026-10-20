package org.llm4s.javaapi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Java records and enums for {@code JStructuredOutputSpec}: what {@code complete(prompt, Record.class)} reads (#1486). */
public final class StructuredRecords {

    private StructuredRecords() {}

    public enum Tier { FREE, PRO }

    public record Address(String city, String country) {}

    public record Customer(
        String name,
        int age,
        long id,
        double score,
        boolean active,
        Tier tier,
        Address address,
        List<String> tags,
        List<Address> previous,
        Optional<String> nickname
    ) {}

    public record WithMap(Map<String, String> values) {}

    public record WithArray(String[] values) {}

    public record Node(String name, List<Node> children) {}

    public record Boxed(Integer count, Double ratio, Boolean flag, short small, float f) {}
}
