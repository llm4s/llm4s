package org.llm4s.quarkus.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/** The application's {@code llm4s.*} properties, with their {@code ${...}} expressions expanded. */
public final class Llm4sProperties {

    private Llm4sProperties() {
    }

    /** Every {@code llm4s.*} property of the current configuration. */
    public static Map<String, String> current() {
        return of(ConfigProvider.getConfig());
    }

    /** Every {@code llm4s.*} property of {@code config}. */
    public static Map<String, String> of(Config config) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (String name : config.getPropertyNames()) {
            if (name.startsWith("llm4s.")) {
                config.getOptionalValue(name, String.class).ifPresent(value -> properties.put(name, value));
            }
        }
        return properties;
    }
}
