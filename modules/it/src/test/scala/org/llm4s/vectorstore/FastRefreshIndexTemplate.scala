package org.llm4s.vectorstore

import org.scalatest.Assertions

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.time.Duration

/**
 * An index template that gives a suite's test indices a short `refresh_interval`, on Elasticsearch and OpenSearch
 * alike (#3216).
 *
 * The stores write with `refresh=wait_for`, which waits for the index's next scheduled refresh: a second, by default,
 * on every write. The template changes only the indices whose names match `pattern`, so the stores create their indices
 * exactly as in production and the cluster supplies the interval. A suite installs it in `beforeAll` and removes it in
 * `afterAll`; two templates on one cluster must not share a pattern.
 */
object FastRefreshIndexTemplate {

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  def install(baseUrl: String, name: String, pattern: String, headers: Map[String, String]): Unit = {
    val body = ujson
      .Obj(
        "index_patterns" -> ujson.Arr(pattern),
        "template"       -> ujson.Obj("settings" -> ujson.Obj("index" -> ujson.Obj("refresh_interval" -> "100ms")))
      )
      .render()
    send(baseUrl, name, headers, "PUT", HttpRequest.BodyPublishers.ofString(body))
  }

  def remove(baseUrl: String, name: String, headers: Map[String, String]): Unit =
    send(baseUrl, name, headers, "DELETE", HttpRequest.BodyPublishers.noBody())

  private def send(
    baseUrl: String,
    name: String,
    headers: Map[String, String],
    method: String,
    body: HttpRequest.BodyPublisher
  ): Unit = {
    val builder = HttpRequest
      .newBuilder(URI.create(s"${baseUrl.stripSuffix("/")}/_index_template/$name"))
      .timeout(Duration.ofSeconds(30))
      .header("Content-Type", "application/json")
      .method(method, body)
    headers.foreach((k, v) => builder.header(k, v))
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() / 100 != 2 && !(method == "DELETE" && response.statusCode() == 404))
      Assertions.fail(s"$method index template $name: HTTP ${response.statusCode()} ${response.body()}")
  }
}
