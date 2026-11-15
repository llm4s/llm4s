package org.llm4s.samples.helidon;

import io.helidon.config.Config;
import io.helidon.health.HealthCheck;
import io.helidon.health.HealthCheckResponse;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.observe.ObserveFeature;
import io.helidon.webserver.observe.health.HealthObserver;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmResult;

/** Starts the server on the port of {@code application.yaml}; {@code gradle run}. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Config config = Config.create();
        Llm4sSettings settings = Llm4sSettings.from(config.get("llm4s"));
        LlmResult<JLlmClient> client = settings.build();
        if (client.isFailure()) {
            throw new IllegalStateException(
                "Cannot build the llm4s client: " + client.getError().getMessage(), client.getError());
        }
        WebServerConfig.Builder builder = WebServer.builder().config(config.get("server"));
        setUp(builder, client.get(), settings);
        WebServer server = builder.build().start();
        System.out.println("Listening on http://localhost:" + server.port() + "/chat");
    }

    /** The application's health check and routes, shared with the tests. */
    static void setUp(WebServerConfig.Builder server, JLlmClient client, Llm4sSettings settings) {
        server.addFeature(ObserveFeature.just(HealthObserver.builder()
                .details(true)
                .addCheck(llm4sCheck(settings))
                .build()))
            .routing(routing -> routing.register("/chat", new ChatService(client)));
    }

    // UP once the client is built, with the provider and model it calls. It does not call the model, so a probe costs
    // no tokens and does not fail when the provider is slow.
    private static HealthCheck llm4sCheck(Llm4sSettings settings) {
        return new HealthCheck() {
            @Override
            public String name() {
                return "llm4s";
            }

            @Override
            public HealthCheckResponse call() {
                return HealthCheckResponse.builder()
                    .status(HealthCheckResponse.Status.UP)
                    .detail("provider", settings.provider())
                    .detail("model", settings.model())
                    .build();
            }
        };
    }
}
