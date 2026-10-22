package org.llm4s.vectorstore.redis

import org.llm4s.error.{ AuthenticationError, NetworkError, ProcessingError, TimeoutError }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import redis.clients.jedis.exceptions.{ JedisAccessControlException, JedisConnectionException, JedisDataException }

import java.net.{ ServerSocket, SocketTimeoutException }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

class JedisConnectionSpec extends AnyFlatSpec with Matchers with EitherValues {

  import JedisConnection.*

  "fromJedis" should "read every Jedis reply shape" in {
    fromJedis(null) shouldBe RedisReply.Nil
    fromJedis("OK".getBytes).text shouldBe Some("OK")
    fromJedis(java.lang.Long.valueOf(4L)) shouldBe RedisReply.Integer(4L)
    fromJedis(List[Any]("a".getBytes, java.lang.Long.valueOf(1L)).asJava) match {
      case RedisReply.Arr(Seq(a, b)) =>
        a.text shouldBe Some("a")
        b shouldBe RedisReply.Integer(1L)
      case other => fail(s"not an array: $other")
    }
    fromJedis(new JedisDataException("ERR nope")) shouldBe RedisReply.Error("ERR nope")
    fromJedis(new JedisDataException(null: String)) shouldBe RedisReply.Error("JedisDataException")
    fromJedis(java.lang.Double.valueOf(1.5)).text shouldBe Some("1.5")
  }

  "toReplyOrError" should "keep a server's refusal as an error reply and map the rest to errors" in {
    val timeout = 1.second
    toReplyOrError(new JedisDataException("Unknown index name"), "h:1", timeout) shouldBe
      Right(RedisReply.Error("Unknown index name"))
    toReplyOrError(new JedisDataException(null: String), "h:1", timeout) shouldBe Right(RedisReply.Error("error"))
    toReplyOrError(new JedisAccessControlException("WRONGPASS"), "h:1", timeout).left.value shouldBe
      an[AuthenticationError]
    toReplyOrError(new JedisConnectionException("refused"), "h:1", timeout).left.value shouldBe a[NetworkError]
    val slow =
      toReplyOrError(new JedisConnectionException(new SocketTimeoutException("Read timed out")), "h:1", timeout)
    slow.left.value shouldBe a[TimeoutError]
    toReplyOrError(new IllegalStateException("odd"), "h:1", timeout).left.value shouldBe a[ProcessingError]
  }

  "JedisConnection" should "report a server it cannot reach, for a command and for a pipeline" in {
    val port       = Using.resource(new ServerSocket(0))(_.getLocalPort) // free, and closed again: nothing listens
    val connection = JedisConnection(RedisConfig(host = "127.0.0.1", port = port, timeout = 2.seconds)).value
    connection.execute(Seq("PING".getBytes)).left.value shouldBe a[NetworkError]
    connection.pipeline(Seq(Seq("PING".getBytes))).left.value shouldBe a[NetworkError]
    connection.pipeline(Seq.empty) shouldBe Right(Seq.empty)
    connection.close()
  }

  it should "build a client with credentials and TLS" in {
    val config     = RedisConfig(username = Some("app"), password = Some("pw"), tls = true, database = 1)
    val connection = JedisConnection(config).value
    connection.close()
    JedisConnection(RedisConfig(password = Some("pw"))).value.close()
  }
}
