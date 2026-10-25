package org.llm4s.javaapi;

import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

/**
 * The proxy and TLS settings a client builder has been told, each {@code null} until set: the builder's form of
 * {@code llm4s.http}. Immutable. Package-private: {@code ClientBuilds} (Scala) checks the values and turns them into the
 * config's {@code HttpNetworkSettings} at {@code build()}, so a setter never throws.
 */
final class ClientNetwork {

  static final ClientNetwork EMPTY = new ClientNetwork(null, null, null, null, null, null, null, null);

  final String proxyUrl;
  final String proxyUsername;
  final String proxyPassword;
  /** Unmodifiable; {@code null} keeps the default, the loopback addresses. */
  final List<String> nonProxyHosts;
  final Path trustStore;
  final String trustStorePassword;
  final Path keyStore;
  final String keyStorePassword;

  private ClientNetwork(
      String proxyUrl,
      String proxyUsername,
      String proxyPassword,
      List<String> nonProxyHosts,
      Path trustStore,
      String trustStorePassword,
      Path keyStore,
      String keyStorePassword) {
    this.proxyUrl = proxyUrl;
    this.proxyUsername = proxyUsername;
    this.proxyPassword = proxyPassword;
    this.nonProxyHosts = nonProxyHosts;
    this.trustStore = trustStore;
    this.trustStorePassword = trustStorePassword;
    this.keyStore = keyStore;
    this.keyStorePassword = keyStorePassword;
  }

  ClientNetwork proxy(String url) {
    return new ClientNetwork(url, proxyUsername, proxyPassword, nonProxyHosts, trustStore, trustStorePassword,
        keyStore, keyStorePassword);
  }

  ClientNetwork proxyCredentials(String username, String password) {
    return new ClientNetwork(proxyUrl, username, password, nonProxyHosts, trustStore, trustStorePassword, keyStore,
        keyStorePassword);
  }

  ClientNetwork nonProxyHosts(String... hosts) {
    List<String> list = hosts == null ? null : List.of(hosts);
    return new ClientNetwork(proxyUrl, proxyUsername, proxyPassword, list, trustStore, trustStorePassword, keyStore,
        keyStorePassword);
  }

  ClientNetwork trustStore(Path path, String password) {
    return new ClientNetwork(proxyUrl, proxyUsername, proxyPassword, nonProxyHosts, path, password, keyStore,
        keyStorePassword);
  }

  ClientNetwork keyStore(Path path, String password) {
    return new ClientNetwork(proxyUrl, proxyUsername, proxyPassword, nonProxyHosts, trustStore, trustStorePassword,
        path, password);
  }

  /** The fields that are set, for the builder's {@code toString}: the proxy URL redacted, the passwords as {@code ***}. */
  void describe(StringJoiner fields) {
    if (proxyUrl != null) fields.add("proxy=" + org.llm4s.util.Redaction.url(proxyUrl));
    if (proxyUsername != null) fields.add("proxyUsername=" + proxyUsername);
    if (proxyPassword != null) fields.add("proxyPassword=***");
    if (nonProxyHosts != null) fields.add("nonProxyHosts=" + nonProxyHosts);
    if (trustStore != null) fields.add("trustStore=" + trustStore);
    if (trustStorePassword != null) fields.add("trustStorePassword=***");
    if (keyStore != null) fields.add("keyStore=" + keyStore);
    if (keyStorePassword != null) fields.add("keyStorePassword=***");
  }
}
