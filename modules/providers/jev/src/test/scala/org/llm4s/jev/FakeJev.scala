package org.llm4s.jev

import com.sun.net.httpserver.HttpExchange
import org.llm4s.testkit.LocalProviderTestServer

import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** What the fake Jev server saw of one request. Header names are lower-cased. */
final case class Seen(method: String, path: String, headers: Map[String, String], body: String)

/** What the fake Jev server answers. */
final case class Reply(status: Int, body: String, headers: Map[String, String] = Map.empty)

/**
 * A local stand-in for TypeSafe's API: records every request and answers the n-th with the n-th reply (the last
 * reply repeats), so a test sees exactly what the client sent and controls what comes back. No network, no key.
 */
object FakeJev {

  /** The contract's own example responses (https://docs.typesafe.ai/api.md#answer-types), verbatim. */
  object Examples {
    val Noul: String =
      """{"model":"jev-1.13.0","answers":{"is_urgent":{"type":"noul","noul":0.95}},"usage":{"input_tokens":296,"output_tokens":20}}"""
    val Choice: String =
      """{"model":"jev-1.13.0","answers":{"department":{"type":"choice","choice":"billing","probabilities":{"billing":0.88,"technical":0.12,"sales":0.0},"confidence":0.81}},"usage":{"input_tokens":318,"output_tokens":34}}"""
    val Score: String =
      """{"model":"jev-1.13.0","answers":{"frustration":{"type":"score","score":1.05,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},"probabilities":{"0":0.0,"1":0.95,"2":0.05},"confidence":0.92}},"usage":{"input_tokens":304,"output_tokens":18}}"""
  }

  def ok(body: String = Examples.Noul, headers: Map[String, String] = Map.empty): Reply = Reply(200, body, headers)

  /** Runs `test` with the base URL of a fake server that answers with `replies`, and the requests it has seen. */
  def serve[A](replies: Reply*)(test: (String, () => Seq[Seen]) => A): Unit = {
    val seen    = new CopyOnWriteArrayList[Seen]()
    val counter = new AtomicInteger(0)
    LocalProviderTestServer.withServer("/") { exchange =>
      val body    = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val headers = exchange.getRequestHeaders.asScala.map { case (k, v) => k.toLowerCase -> v.get(0) }.toMap
      seen.add(Seen(exchange.getRequestMethod, exchange.getRequestURI.getPath, headers, body)): Unit
      val reply = replies(math.min(counter.getAndIncrement(), replies.size - 1))
      send(exchange, reply)
    }(baseUrl => test(baseUrl, () => seen.asScala.toSeq): Unit)
  }

  def send(exchange: HttpExchange, reply: Reply): Unit = {
    val bytes = reply.body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    reply.headers.foreach { case (k, v) => exchange.getResponseHeaders.add(k, v) }
    exchange.sendResponseHeaders(reply.status, bytes.length.toLong)
    val out = exchange.getResponseBody
    out.write(bytes)
    out.close()
  }
}
