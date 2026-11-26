package org.llm4s.schema

/**
 * Types whose annotations are not constants (#2951). None derives anything here: deriving one is a compile error,
 * which `AnnotationConstantsSpec` checks from another file than the annotation's.
 */
object NonConstantFixtures {

  def text: String = "known only at run time"

  final case class DefDescription(@description(text) field: String)

  enum DefCase {
    @description(text) case Refund(amount: Int)
    case Escalate(team: String)
  }

  enum DefMixed {
    @description(text) case Stop
    case Go(speed: Int)
  }

  @upickle.implicits.key(text)
  sealed trait DefDiscriminator
  final case class One(n: Int)    extends DefDiscriminator
  final case class Two(s: String) extends DefDiscriminator
}
