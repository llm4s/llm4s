package org.llm4s.schema

import org.llm4s.annotation.Experimental
import upickle.core.{ Abort, NoOpVisitor, ObjVisitor, StringVisitor, Visitor }

import scala.compiletime.{ constValue, erasedValue, summonInline }

/**
 * A uPickle `ReadWriter` for named tuples, which uPickle does not provide, so that a named tuple can be a structured
 * output or a tool's arguments and result with no case class to declare:
 *
 * {{{
 * import org.llm4s.schema.*
 * import org.llm4s.schema.namedtuples.given
 *
 * val review = client.completeStructuredOf[(sentiment: Sentiment, confidence: Double)](conversation)
 * review.map(_.sentiment)
 * }}}
 *
 * A named tuple is written as uPickle writes a case class with the same fields and no default values: a JSON object
 * keyed by the names, in order, with `null` for a `None`. It is read the same way, except that an absent `Option`
 * element reads as `None` (a case class needs a default for that); any other absent element is an error naming it,
 * and keys the tuple has no element for are ignored. Each element needs a `ReadWriter` of its own.
 *
 * It is imported explicitly, never found on its own, so that it cannot compete with an instance uPickle may add later.
 */
@Experimental
object namedtuples {

  /** The `ReadWriter` of the named tuple whose names are `N` and element types `V`. */
  inline given readWriter[N <: Tuple, V <: Tuple]: upickle.default.ReadWriter[NamedTuple.NamedTuple[N, V]] =
    codec[NamedTuple.NamedTuple[N, V]](names[N], elements[V], optional[V])

  private inline def names[T <: Tuple]: List[String] =
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (h *: t)   => constValue[h].asInstanceOf[String] :: names[t]
    }

  private inline def elements[T <: Tuple]: List[upickle.default.ReadWriter[?]] =
    inline erasedValue[T] match {
      case _: EmptyTuple => Nil
      case _: (h *: t)   => summonInline[upickle.default.ReadWriter[h]] :: elements[t]
    }

  private inline def optional[T <: Tuple]: List[Boolean] =
    inline erasedValue[T] match {
      case _: EmptyTuple       => Nil
      case _: (Option[?] *: t) => true :: optional[t]
      case _: (_ *: t)         => false :: optional[t]
    }

  private def codec[A](
    names: List[String],
    elements: List[upickle.default.ReadWriter[?]],
    optional: List[Boolean]
  ): upickle.default.ReadWriter[A] =
    new NamedTupleReadWriter[A](names.toArray, elements.toArray, optional.toArray)
}

/**
 * Reads and writes a named tuple as a JSON object, as uPickle's derived reader and writer do a case class: the reader
 * keeps the first value of a repeated key and skips unknown keys.
 */
final private class NamedTupleReadWriter[A](
  names: Array[String],
  elements: Array[upickle.default.ReadWriter[?]],
  optional: Array[Boolean]
) extends upickle.default.ReadWriter[A]
    with upickle.default.SimpleReader[A] {

  private val index: Map[String, Int] = names.zipWithIndex.toMap

  override def expectedMsg: String = "expected dictionary"

  def write0[R](out: Visitor[?, R], v: A): R = {
    val values = v.asInstanceOf[Product].productIterator
    val ctx    = out.visitObject(names.length, jsonableKeys = true, -1).narrow
    names.indices.foreach { i =>
      val value = values.next()
      ctx.visitKeyValue(ctx.visitKey(-1).visitString(names(i), -1))
      ctx.visitValue(elements(i).asInstanceOf[upickle.default.Writer[Any]].write(ctx.subVisitor, value), -1)
    }
    ctx.visitEnd(-1)
  }

  override def visitObject(length: Int, jsonableKeys: Boolean, index: Int): ObjVisitor[Any, A] =
    new ObjVisitor[Any, A] {
      private val values  = new Array[Any](names.length)
      private val found   = new Array[Boolean](names.length)
      private var current = -1

      def visitKey(index: Int): Visitor[?, ?] = StringVisitor

      def visitKeyValue(key: Any): Unit = current = NamedTupleReadWriter.this.index.getOrElse(key.toString, -1)

      def subVisitor: Visitor[?, ?] = if (current == -1) NoOpVisitor else elements(current)

      def visitValue(value: Any, index: Int): Unit =
        if (current != -1 && !found(current)) {
          values(current) = value
          found(current) = true
        }

      def visitEnd(index: Int): A = {
        val missing = names.indices.filter { i =>
          if (!found(i) && optional(i)) values(i) = None
          !found(i) && !optional(i)
        }
        if (missing.nonEmpty) throw new Abort("missing keys in dictionary: " + missing.map(names(_)).mkString(", "))
        Tuple.fromArray(values).asInstanceOf[A]
      }
    }
}
