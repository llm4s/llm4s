package org.llm4s.javaapi;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import org.llm4s.metrics.MetricsCollector;
import org.llm4s.util.Redaction;

/**
 * What a provider's client builder has been told so far: every field any of them takes, each {@code null} until set.
 * Immutable - each {@code with} returns a copy - so a builder made from it is too. Package-private: callers see the
 * builders, and {@code ClientBuilds} (Scala) reads the fields to make the provider's config.
 */
final class ClientSettings {

  static final ClientSettings EMPTY =
      new ClientSettings(null, null, null, null, null, null, null, null, null, null, Map.of(), null);

  final String apiKey;
  final String model;
  final String baseUrl;
  final String organization;
  final String endpoint;
  final String apiVersion;
  final Integer contextWindow;
  final Integer reserveCompletion;
  final Duration requestTimeout;
  final Duration streamTimeout;
  /** In the order they were set; unmodifiable. */
  final Map<String, String> headers;
  /** Where the client reports every call; {@code null} records nothing. */
  final MetricsCollector metrics;

  private ClientSettings(
      String apiKey,
      String model,
      String baseUrl,
      String organization,
      String endpoint,
      String apiVersion,
      Integer contextWindow,
      Integer reserveCompletion,
      Duration requestTimeout,
      Duration streamTimeout,
      Map<String, String> headers,
      MetricsCollector metrics) {
    this.apiKey = apiKey;
    this.model = model;
    this.baseUrl = baseUrl;
    this.organization = organization;
    this.endpoint = endpoint;
    this.apiVersion = apiVersion;
    this.contextWindow = contextWindow;
    this.reserveCompletion = reserveCompletion;
    this.requestTimeout = requestTimeout;
    this.streamTimeout = streamTimeout;
    this.headers = headers;
    this.metrics = metrics;
  }

  ClientSettings apiKey(String value) {
    return new ClientSettings(value, model, baseUrl, organization, endpoint, apiVersion, contextWindow,
        reserveCompletion, requestTimeout, streamTimeout, headers, metrics);
  }

  ClientSettings model(String value) {
    return new ClientSettings(apiKey, value, baseUrl, organization, endpoint, apiVersion, contextWindow,
        reserveCompletion, requestTimeout, streamTimeout, headers, metrics);
  }

  ClientSettings baseUrl(String value) {
    return new ClientSettings(apiKey, model, value, organization, endpoint, apiVersion, contextWindow,
        reserveCompletion, requestTimeout, streamTimeout, headers, metrics);
  }

  ClientSettings organization(String value) {
    return new ClientSettings(apiKey, model, baseUrl, value, endpoint, apiVersion, contextWindow,
        reserveCompletion, requestTimeout, streamTimeout, headers, metrics);
  }

  ClientSettings endpoint(String value) {
    return new ClientSettings(apiKey, model, baseUrl, organization, value, apiVersion, contextWindow,
        reserveCompletion, requestTimeout, streamTimeout, headers, metrics);
  }

  ClientSettings apiVersion(String value) {
    return new ClientSettings(apiKey, model, baseUrl, organization, endpoint, value, contextWindow,
        reserveCompletion, requestTimeout, streamTimeout, headers, metrics);
  }

  ClientSettings contextWindow(int value) {
    return new ClientSettings(apiKey, model, baseUrl, organization, endpoint, apiVersion, value,
        reserveCompletion, requestTimeout, streamTimeout, headers, metrics);
  }

  ClientSettings reserveCompletion(int value) {
    return new ClientSettings(apiKey, model, baseUrl, organization, endpoint, apiVersion, contextWindow,
        value, requestTimeout, streamTimeout, headers, metrics);
  }

  ClientSettings requestTimeout(Duration value) {
    return new ClientSettings(apiKey, model, baseUrl, organization, endpoint, apiVersion, contextWindow,
        reserveCompletion, value, streamTimeout, headers, metrics);
  }

  ClientSettings streamTimeout(Duration value) {
    return new ClientSettings(apiKey, model, baseUrl, organization, endpoint, apiVersion, contextWindow,
        reserveCompletion, requestTimeout, value, headers, metrics);
  }

  ClientSettings metrics(MetricsCollector value) {
    return new ClientSettings(apiKey, model, baseUrl, organization, endpoint, apiVersion, contextWindow,
        reserveCompletion, requestTimeout, streamTimeout, headers, value);
  }

  /** {@code name} set to {@code value}, or removed when {@code value} is {@code null}. */
  ClientSettings header(String name, String value) {
    Map<String, String> next = new LinkedHashMap<>(headers);
    if (value == null) {
      next.remove(name);
    } else {
      next.put(name, value);
    }
    return new ClientSettings(apiKey, model, baseUrl, organization, endpoint, apiVersion, contextWindow,
        reserveCompletion, requestTimeout, streamTimeout, Collections.unmodifiableMap(next), metrics);
  }

  /**
   * The builder's {@code toString}: the fields that are set, the API key as {@code ***}, the headers by name only,
   * since a header such as {@code Authorization} carries a credential too, and the base URL and endpoint with any
   * userinfo ({@code user:password@}), query and fragment as {@code ***}, since a URL can carry a credential as well.
   */
  String describe(String builder) {
    StringJoiner fields = new StringJoiner(", ", builder + "(", ")");
    if (model != null) fields.add("model=" + model);
    if (apiKey != null) fields.add("apiKey=***");
    if (baseUrl != null) fields.add("baseUrl=" + Redaction.url(baseUrl));
    if (endpoint != null) fields.add("endpoint=" + Redaction.url(endpoint));
    if (apiVersion != null) fields.add("apiVersion=" + apiVersion);
    if (organization != null) fields.add("organization=" + organization);
    if (contextWindow != null) fields.add("contextWindow=" + contextWindow);
    if (reserveCompletion != null) fields.add("reserveCompletion=" + reserveCompletion);
    if (requestTimeout != null) fields.add("requestTimeout=" + requestTimeout);
    if (streamTimeout != null) fields.add("streamTimeout=" + streamTimeout);
    if (!headers.isEmpty()) fields.add("headers=" + headers.keySet());
    if (metrics != null) fields.add("metrics=" + metrics.getClass().getName());
    return fields.toString();
  }
}
