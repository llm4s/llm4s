package org.llm4s.tapir

import sttp.client3.Request
import sttp.client3.testing.SttpBackendStub
import sttp.tapir._
import upickle.default.{ read, write, ReadWriter }

import scala.util.Try

/** Endpoints, JSON codecs and a recording backend shared by the specs. */
object Fixtures {

  /** A tapir JSON body through upickle, so the specs need no tapir JSON integration. */
  def json[T: ReadWriter: Schema]: Codec[String, T, CodecFormat.Json] =
    Codec.json[T](s => Try(read[T](s)).fold(e => DecodeResult.Error(s, e), DecodeResult.Value(_)))(write(_))

  def jsonIn[T: ReadWriter: Schema]: EndpointIO.Body[String, T] = customCodecJsonBody[T](using json[T])

  final case class Order(id: String, total: Double) derives ReadWriter
  object Order { given Schema[Order] = Schema.derived }

  final case class ApiError(message: String) derives ReadWriter
  object ApiError { given Schema[ApiError] = Schema.derived }

  final case class NewOrder(item: String, quantity: Int, note: Option[String] = None) derives ReadWriter
  object NewOrder {
    given Schema[NewOrder] = Schema
      .derived[NewOrder]
      .modify(_.quantity)(_.validate(Validator.min(1)).description("How many to order"))
      .modify(_.item)(_.validate(Validator.minLength(1)))
  }

  enum Status derives ReadWriter { case Open, Shipped, Cancelled }
  object Status {
    given Schema[Status]                               = Schema.derivedEnumeration[Status].defaultStringBased
    given Codec[String, Status, CodecFormat.TextPlain] = Codec.derivedEnumeration[String, Status].defaultStringBased
  }

  final case class Tree(children: List[Tree]) derives ReadWriter
  object Tree {
    // Written out: a derived given for a recursive type summons itself while it initialises.
    private val schema: Schema[Tree] = Schema(
      SchemaType.SProduct[Tree](
        List(
          SchemaType.SProductField[Tree, List[Tree]](
            FieldName("children"),
            Schema(SchemaType.SArray[List[Tree], Tree](Schema(SchemaType.SRef(Schema.SName("Tree"))))(identity)),
            tree => Some(tree.children)
          )
        )
      ),
      Some(Schema.SName("Tree"))
    )
    given Schema[Tree] = schema
  }

  sealed trait Shape derives ReadWriter
  final case class Circle(r: Double)    extends Shape derives ReadWriter
  final case class Square(side: Double) extends Shape derives ReadWriter
  object Shape { given Schema[Shape] = Schema.derived }

  final case class Bag(items: Map[String, Int]) derives ReadWriter
  object Bag { given Schema[Bag] = Schema.derived }

  def getOrder: PublicEndpoint[String, ApiError, Order, Any] =
    endpoint.get
      .in("orders" / path[String]("id").description("The order's id"))
      .out(jsonIn[Order])
      .errorOut(jsonIn[ApiError])
      .description("Look up an order by id")

  def cancelOrder: PublicEndpoint[String, ApiError, Order, Any] =
    endpoint.post
      .in("orders" / path[String]("id") / "cancel")
      .out(jsonIn[Order])
      .errorOut(jsonIn[ApiError])
      .description("Cancel an order")

  /** A stub backend that answers `status` and `body` and records each request it receives. */
  final class Recording(status: Int = 200, body: String = """{"id":"42","total":9.5}""") {
    @volatile var requests: Vector[Request[?, ?]] = Vector.empty
    val backend =
      SttpBackendStub.synchronous.whenAnyRequest.thenRespondF { request =>
        requests = requests :+ request
        sttp.client3.Response(body, sttp.model.StatusCode(status))
      }
    def last: Request[?, ?] = requests.last
  }
}
