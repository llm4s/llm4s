package org.llm4s.quarkus.deployment;

import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.AdditionalIndexedClassesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageSystemPropertyBuildItem;
import io.quarkus.deployment.pkg.steps.NativeOrNativeSourcesBuild;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.smallrye.health.deployment.spi.HealthBuildItem;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.jandex.DotName;
import org.llm4s.javaapi.FrameworkConfig;
import org.llm4s.javaapi.JEmbeddingClient;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.quarkus.runtime.JLlmClientDestroyer;
import org.llm4s.quarkus.runtime.Llm4sHealthConfig;
import org.llm4s.quarkus.runtime.Llm4sProperties;
import org.llm4s.quarkus.runtime.Llm4sReadinessCheck;
import org.llm4s.quarkus.runtime.Llm4sRecorder;

class Llm4sProcessor {

    private static final String FEATURE = "llm4s";

    private static final DotName NAMED = DotName.createSimple(Named.class);

    // A bean whose only qualifier is @Named is also @Default (CDI 2.3.1), so each section's client also carries
    // @Identifier("<section>"): then only the default section's client is @Default.
    private static final DotName IDENTIFIER = DotName.createSimple(Identifier.class);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Reads the application's {@code llm4s.*} properties and fails the build when their structure is wrong: a section
     * with no provider or model, a provider that is not on the classpath, a default that names no section. Each message
     * names the property to fix.
     */
    @BuildStep
    Llm4sClientsBuildItem checkConfiguration() {
        Map<String, String> properties = Llm4sProperties.of(ConfigProvider.getConfig());
        List<String> problems = FrameworkConfig.problems(properties);
        if (!problems.isEmpty()) {
            throw new ConfigurationException(
                "The llm4s configuration is not valid:\n  - " + String.join("\n  - ", problems));
        }
        return new Llm4sClientsBuildItem(
            FrameworkConfig.sections(properties),
            FrameworkConfig.defaultSection(properties),
            FrameworkConfig.embeddingModel(properties).isPresent());
    }

    /**
     * A {@code @Singleton} {@link JLlmClient} bean for each section, qualified {@code @Named("<section>")} and
     * {@code @Identifier("<section>")}, the default section's also {@code @Default}, and a {@link JEmbeddingClient}
     * when an embedding model is set. Each is built when the application starts, so a client that cannot be built (a
     * missing key) fails the start with llm4s's message, and is a default bean: an application's own producer of the
     * same type and qualifiers replaces it. The clients are final classes, which a normal scope could not proxy.
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void clients(Llm4sClientsBuildItem clients, Llm4sRecorder recorder, BuildProducer<SyntheticBeanBuildItem> beans) {
        for (String section : clients.sections()) {
            var bean = SyntheticBeanBuildItem.configure(JLlmClient.class)
                .scope(Singleton.class)
                .addQualifier().annotation(NAMED).addValue("value", section).done()
                .addQualifier().annotation(IDENTIFIER).addValue("value", section).done()
                .defaultBean()
                .unremovable()
                .startup()
                .setRuntimeInit()
                .destroyer(JLlmClientDestroyer.class)
                .createWith(recorder.client(section));
            if (clients.defaultSection().equals(Optional.of(section))) {
                bean.addQualifier(Default.class);
            }
            beans.produce(bean.done());
        }
        if (clients.embeddings()) {
            beans.produce(SyntheticBeanBuildItem.configure(JEmbeddingClient.class)
                .scope(Singleton.class)
                .defaultBean()
                .unremovable()
                .startup()
                .setRuntimeInit()
                .createWith(recorder.embeddingClient())
                .done());
        }
    }

    /**
     * {@link Llm4sHealthConfig}, the extension's own {@code llm4s.health.*} settings, as an application's mapping
     * rather than an extension's config root: a root named {@code llm4s} would claim the whole namespace, and Quarkus
     * would warn that every provider property it does not map is unrecognized. A provider module declares its own
     * keys, so no mapping can list them all; {@link #checkConfiguration} checks them instead.
     */
    @BuildStep
    AdditionalIndexedClassesBuildItem configMapping() {
        return new AdditionalIndexedClassesBuildItem(Llm4sHealthConfig.class.getName());
    }

    /**
     * The default client's readiness check, when the application has quarkus-smallrye-health and
     * {@code llm4s.health.enabled} is not false when it is built.
     */
    @BuildStep
    HealthBuildItem readiness(Llm4sClientsBuildItem clients) {
        boolean enabled = ConfigProvider.getConfig().getOptionalValue("llm4s.health.enabled", Boolean.class).orElse(true);
        return new HealthBuildItem(Llm4sReadinessCheck.class.getName(), enabled && clients.defaultSection().isPresent());
    }

    /**
     * llm4s has no GraalVM reachability metadata yet (#1463), so a native build would fail late, or produce an image
     * that fails at run time. This fails it at once instead.
     */
    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    void refuseNativeImage(BuildProducer<NativeImageSystemPropertyBuildItem> nativeProperties) {
        throw new IllegalStateException(
            "quarkus-llm4s does not support native images yet: llm4s has no GraalVM reachability metadata "
                + "(https://github.com/llm4s/llm4s/issues/1463). Build in JVM mode (without -Dnative / "
                + "quarkus.native.enabled=true).");
    }
}
