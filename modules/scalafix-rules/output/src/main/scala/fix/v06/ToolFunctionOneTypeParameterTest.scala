package fix.v06

import org.llm4s.toolapi.{ ObjectSchema, Schema, ToolBuilder, ToolFunction }

object ToolFunctionOneTypeParameterTest {
  val schema: ObjectSchema[Map[String, Any]] =
    Schema.`object`[Map[String, Any]]("A city").withProperty(Schema.property("city", Schema.string("The city")))

  val built = ToolBuilder[String]("weather", "The weather in a city", schema)
    .withHandler(args => args.getString("city"))
    .build()

  val builder: ToolBuilder[String] =
    ToolBuilder[String]("weather", "The weather in a city", schema)

  val direct: ToolFunction[Int] =
    ToolFunction[Int]("length", "The length of a city's name", schema, _ => Right(1))

  val qualified: org.llm4s.toolapi.ToolFunction[ujson.Value] =
    org.llm4s.toolapi.ToolFunction[ujson.Value]("echo", "Echoes", schema, args => Right(args.params))

  def names(tools: Seq[ToolFunction[_]]): Seq[String] = tools.map(_.name)

  def first(tools: Seq[ToolFunction[?]]): Option[ToolFunction[?]] = tools.headOption

  def results[R](tools: Seq[ToolFunction[R]]): Int = tools.size

  def describe(tool: ToolFunction[Either[String, Int]]): String = tool.description

  def isTool(value: Any): Boolean = value match {
    case _: ToolFunction[_] => true
    case _                     => false
  }

  // not llm4s's: left alone
  final case class ToolBuilder2[A, B](a: A, b: B)
  val other: ToolBuilder2[Int, String] = ToolBuilder2[Int, String](1, "one")
}
