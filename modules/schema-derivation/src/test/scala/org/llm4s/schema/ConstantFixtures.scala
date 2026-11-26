package org.llm4s.schema

import upickle.default.ReadWriter

/** Types whose annotations are given constants rather than literals, and values that are not constants (#2951). */
object ConstantFixtures {

  object Limits {
    final val Limit           = 750
    final val Text            = "from a final val"
    inline val InlineText     = "from an inline val"
    final val Key             = "user_name"
    final val ToolName        = "find_by_constant"
    final val Widened: String = "a final val with a declared type is not a constant"
    val NotFinal              = "a val that is not final"
    def text: String          = "a def"
    def count: Int            = 3
  }

  // The reproduction of #2951, field for field
  final case class Probed(
    @description("a literal") literal: String,
    @description("two " + "literals") concatenated: String,
    @description("at most " + Limits.Limit + " characters") withConstant: String,
    @description(s"at most ${Limits.Limit} characters") interpolated: String,
    @description(Limits.Text) finalVal: String,
    @description(Limits.InlineText) inlineVal: String
  ) derives SchemaOf

  final case class Mixed(
    @description(s"a tab\there, ${Limits.Text}: " + s"${Limits.Limit}${true}" + '!') escaped: String,
    @description(text = "named " + Limits.InlineText) named: String,
    @description("typed": String) typed: String
  ) derives SchemaOf

  @description("A type " + Limits.Text)
  final case class DescribedType(n: Int) derives SchemaOf

  enum Choice derives SchemaOf {
    @description(s"Refund up to ${Limits.Limit}") case Refund(amount: Int)
    @description("Escalate, " + Limits.InlineText) case Escalate(team: String)
  }

  // uPickle reads only a literal key, so a type with a constant key has no derived ReadWriter to agree with
  final case class ConstantKey(@upickle.implicits.key(Limits.Key) userName: String, age: Int) derives SchemaOf

  final case class Keyed(@upickle.implicits.key("user_name") userName: String, age: Option[Int])
      derives SchemaOf,
        ReadWriter

  trait ConstantTools {
    @toolName(Limits.ToolName)
    @description("Finds " + Limits.Text)
    def find(@description(s"at most ${Limits.Limit} characters") query: String): String
  }

  trait DefToolDescription {
    @description(Limits.text)
    def find(query: String): String
  }

  trait DefParameterDescription {
    def find(@description(s"at most ${Limits.count} characters") query: String): String
  }

  trait DefToolName {
    @toolName(Limits.text)
    def find(query: String): String
  }
}
