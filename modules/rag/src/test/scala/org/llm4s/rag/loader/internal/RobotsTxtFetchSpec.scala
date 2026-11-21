package org.llm4s.rag.loader.internal

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ InetAddress, InetSocketAddress }
import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Runs the fetching side of [[RobotsTxtParser]] (`isAllowed`, `getRules`, the cache) against a
 * real HTTP server on a loopback port. No external network.
 */
class RobotsTxtFetchSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  override def afterEach(): Unit =
    RobotsTxtParser.clearCache()

  private val agent   = "LLM4S-Crawler/1.0"
  private val timeout = 5.seconds

  /** One request as it arrived at [[RobotsServer]]: the path and the `User-Agent` header. */
  final private case class SeenRequest(path: String, userAgent: Option[String])

  /** Answers every request with `status` and `body`, and records what it was asked for. */
  final private class RobotsServer(status: Int, body: String) extends AutoCloseable {
    private val seen   = new CopyOnWriteArrayList[SeenRequest]()
    private val server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)

    server.createContext(
      "/",
      (ex: HttpExchange) => {
        seen.add(SeenRequest(ex.getRequestURI.getPath, Option(ex.getRequestHeaders.getFirst("User-Agent"))))
        val bytes = body.getBytes("UTF-8")
        ex.sendResponseHeaders(status, if (bytes.isEmpty) -1L else bytes.length.toLong)
        if (bytes.nonEmpty) ex.getResponseBody.write(bytes)
        ex.close()
      }
    )
    server.start()

    val baseUrl: String            = s"http://127.0.0.1:${server.getAddress.getPort}"
    def requests: Seq[SeenRequest] = seen.asScala.toSeq
    override def close(): Unit     = server.stop(0)
  }

  private def withServer[A](status: Int, body: String)(f: RobotsServer => A): A = {
    val server = new RobotsServer(status, body)
    try f(server)
    finally server.close()
  }

  private val disallowPrivate =
    """User-agent: *
      |Disallow: /private/
      |""".stripMargin

  "RobotsTxtParser.isAllowed" should "apply the Disallow rules served at /robots.txt" in {
    withServer(200, disallowPrivate) { server =>
      RobotsTxtParser.isAllowed(s"${server.baseUrl}/private/x", agent, timeout) shouldBe false
      RobotsTxtParser.isAllowed(s"${server.baseUrl}/public", agent, timeout) shouldBe true
      server.requests.map(_.path) shouldBe Seq("/robots.txt")
    }
  }

  it should "send the given user agent and prefer that agent's group over *" in {
    val content =
      """User-agent: *
        |Disallow: /
        |
        |User-agent: LLM4S-Crawler
        |Disallow: /private/
        |""".stripMargin

    withServer(200, content) { server =>
      RobotsTxtParser.isAllowed(s"${server.baseUrl}/public", agent, timeout) shouldBe true
      RobotsTxtParser.isAllowed(s"${server.baseUrl}/private/x", agent, timeout) shouldBe false
      server.requests.map(_.userAgent) shouldBe Seq(Some(agent))
    }
  }

  it should "allow every path when /robots.txt answers 404" in {
    withServer(404, "User-agent: *\nDisallow: /\n") { server =>
      RobotsTxtParser.isAllowed(s"${server.baseUrl}/private/x", agent, timeout) shouldBe true
      RobotsTxtParser.isAllowed(s"${server.baseUrl}/", agent, timeout) shouldBe true
      server.requests.map(_.path) shouldBe Seq("/robots.txt")
    }
  }

  it should "allow every path when /robots.txt answers 500" in {
    withServer(500, "User-agent: *\nDisallow: /\n") { server =>
      RobotsTxtParser.isAllowed(s"${server.baseUrl}/private/x", agent, timeout) shouldBe true
      server.requests.map(_.path) shouldBe Seq("/robots.txt")
    }
  }

  it should "fetch /robots.txt once per host and serve later checks from the cache" in {
    withServer(200, disallowPrivate) { server =>
      RobotsTxtParser.isAllowed(s"${server.baseUrl}/private/x", agent, timeout) shouldBe false
      RobotsTxtParser.isAllowed(s"${server.baseUrl}/public", agent, timeout) shouldBe true
      RobotsTxtParser.getRules(s"${server.baseUrl}/other", agent, timeout).disallowRules shouldBe Seq("/private/")
      server.requests should have size 1
    }
  }

  it should "fetch again after the cache is cleared" in {
    withServer(200, disallowPrivate) { server =>
      RobotsTxtParser.isAllowed(s"${server.baseUrl}/public", agent, timeout) shouldBe true
      RobotsTxtParser.clearCache()
      RobotsTxtParser.isAllowed(s"${server.baseUrl}/public", agent, timeout) shouldBe true
      server.requests should have size 2
    }
  }

  it should "allow a URL with no host without making a request" in {
    withServer(200, "User-agent: *\nDisallow: /\n") { server =>
      RobotsTxtParser.isAllowed("not a url", agent, timeout) shouldBe true
      RobotsTxtParser.isAllowed("/relative/path", agent, timeout) shouldBe true
      server.requests shouldBe empty
    }
  }

  "RobotsTxtParser.getRules" should "return the rules served at /robots.txt for the user agent" in {
    val content =
      """User-agent: *
        |Disallow: /private/
        |Allow: /private/open/
        |Crawl-delay: 2
        |""".stripMargin

    withServer(200, content) { server =>
      val rules = RobotsTxtParser.getRules(s"${server.baseUrl}/anything", agent, timeout)
      rules.disallowRules shouldBe Seq("/private/")
      rules.allowRules shouldBe Seq("/private/open/")
      rules.crawlDelay shouldBe Some(2.seconds)
    }
  }

  it should "be empty when /robots.txt answers 404" in {
    withServer(404, "User-agent: *\nDisallow: /\n") { server =>
      RobotsTxtParser.getRules(s"${server.baseUrl}/anything", agent, timeout) shouldBe RobotsTxtParser.RobotsTxt.empty
    }
  }

  it should "be empty when /robots.txt answers 500" in {
    withServer(500, "User-agent: *\nDisallow: /\n") { server =>
      RobotsTxtParser.getRules(s"${server.baseUrl}/anything", agent, timeout) shouldBe RobotsTxtParser.RobotsTxt.empty
    }
  }

  it should "be empty for a URL with no host, without making a request" in {
    withServer(200, "User-agent: *\nDisallow: /\n") { server =>
      RobotsTxtParser.getRules("not a url", agent, timeout) shouldBe RobotsTxtParser.RobotsTxt.empty
      server.requests shouldBe empty
    }
  }

  "RobotsTxt.isAllowed" should "allow a path matched only by an Allow rule" in {
    val robots = RobotsTxtParser.RobotsTxt(allowRules = Seq("/a"))
    robots.isAllowed("/a") shouldBe true
    robots.isAllowed("/a/b") shouldBe true
  }
}
