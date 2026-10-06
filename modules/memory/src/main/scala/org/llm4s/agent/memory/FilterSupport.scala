package org.llm4s.agent.memory

import org.sqlite.{ Function => SqliteFunction, SQLiteConnection }

/**
 * What the SQL-backed stores need to know about a [[MemoryFilter]] beyond translating it to SQL.
 *
 * One rule: SQL narrows, `matches` decides. A store may only use SQL to leave rows out when no row that
 * [[MemoryFilter.matches]] accepts can be left out, and whatever SQL cannot express exactly is decided by `matches`
 * on the rows that were read, before any limit, count or delete.
 *
 * [[narrow]] applies the rule to a whole filter tree, so the stores share it and differ only in the SQL of each leaf.
 */
private[memory] object FilterSupport {

  /** A SQL condition over the store's columns, with its `?` parameters in order. */
  final case class Sql(clause: String, params: Seq[Any])

  /**
   * The result of narrowing a filter.
   *
   * @param sql   a condition every row the filter accepts satisfies (it may be satisfied by more rows); `None` is
   *              no restriction at all
   * @param exact true if the condition is also satisfied by no other row, so `matches` need not run again
   */
  final case class Narrowing(sql: Option[Sql], exact: Boolean) {

    /** The `WHERE` clause to put in front of the query, or nothing for no restriction. */
    def where: String = sql.fold("")(s => s"WHERE ${s.clause}")

    /** The parameters of [[where]]. */
    def params: Seq[Any] = sql.fold(Seq.empty[Any])(_.params)

    /** True if rows read with [[where]] still have to pass `matches` before they count. */
    def needsMatches: Boolean = !exact
  }

  private val Everything = Sql("1 = 1", Seq.empty)
  private val Nothing    = Sql("1 = 0", Seq.empty)

  /**
   * Narrow `filter` to SQL. `leaf` gives the SQL of one filter that is not `All`, `None`, `And`, `Or`, `Not` or
   * `Custom`, if that SQL means exactly what `matches` means (a row satisfies it if and only if `matches` accepts
   * it, and it is never NULL), and `None` if SQL cannot say so: the store then reads the rows without narrowing by
   * it and lets `matches` decide.
   *
   *   - `And` keeps the conjuncts it can: `And(ByEntity(e), Custom(p))` still reads only the rows of `e`.
   *   - `Or` narrows only if both sides do, because a row either side accepts must be read.
   *   - `Not` negates only an exact condition. The complement of a condition that is merely a superset is a subset,
   *     which would leave out rows `Not` accepts: for `Not(And(ByEntity(e), Custom(p)))` the rows of `e` that `p`
   *     rejects are accepted, and `NOT entity = e` would drop them.
   */
  def narrow(filter: MemoryFilter)(leaf: MemoryFilter => Option[Sql]): Narrowing = filter match {
    case MemoryFilter.All  => Narrowing(None, exact = true)
    case MemoryFilter.None => Narrowing(Some(Nothing), exact = true)

    case _: MemoryFilter.Custom => Narrowing(None, exact = false) // code, not SQL

    case MemoryFilter.And(left, right) =>
      val l = narrow(left)(leaf)
      val r = narrow(right)(leaf)
      val sql = (l.sql, r.sql) match {
        case (Some(a), Some(b)) => Some(Sql(s"(${a.clause}) AND (${b.clause})", a.params ++ b.params))
        case (a, b)             => a.orElse(b)
      }
      Narrowing(sql, l.exact && r.exact)

    case MemoryFilter.Or(left, right) =>
      val l = narrow(left)(leaf)
      val r = narrow(right)(leaf)
      val sql = (l.sql, r.sql) match {
        case (Some(a), Some(b)) => Some(Sql(s"(${a.clause}) OR (${b.clause})", a.params ++ b.params))
        case _                  => None
      }
      Narrowing(sql, l.exact && r.exact)

    case MemoryFilter.Not(inner) =>
      val i = narrow(inner)(leaf)
      if (!i.exact) Narrowing(None, exact = false)
      else Narrowing(Some(i.sql.fold(Nothing)(s => Sql(s"NOT (${s.clause})", s.params))), exact = true)

    case other =>
      leaf(other) match {
        case Some(sql)  => Narrowing(Some(sql), exact = true)
        case scala.None => Narrowing(None, exact = false)
      }
  }

  /**
   * True if deciding `filter` with `matches` may look at a memory's embedding. Only a `Custom` predicate can (it is
   * code that sees the whole [[Memory]]); every other filter reads content, type, metadata, time or importance. A
   * store that reads rows only to run `matches` on them leaves the embedding column out when this is false, so it
   * neither reads nor decodes a vector per row.
   */
  def readsEmbedding(filter: MemoryFilter): Boolean = filter match {
    case _: MemoryFilter.Custom        => true
    case MemoryFilter.And(left, right) => readsEmbedding(left) || readsEmbedding(right)
    case MemoryFilter.Or(left, right)  => readsEmbedding(left) || readsEmbedding(right)
    case MemoryFilter.Not(inner)       => readsEmbedding(inner)
    case _                             => false
  }

  /** The SQL for a condition that holds for every row, for a leaf that restricts nothing (an unbounded time range). */
  val unrestricted: Sql = Everything

  /** The name of the SQL function that lower-cases text the way `String.toLowerCase` does. */
  val JavaLower: String = "java_lower"

  /**
   * Register [[JavaLower]] on `connection`, so a case-insensitive text test in SQL is the very comparison
   * [[MemoryFilter.ContentContains.matches]] makes. SQLite's own `lower()` folds ASCII only, so it disagrees with
   * Java on `\u00c9COLE` (lower-case `\u00e9cole`), and Java lower-cases `\u0130` and the Kelvin sign to the ASCII letters
   * `i` and `k` that `lower()` leaves alone: narrowing on `lower()` would leave out rows `matches` accepts.
   *
   * The connection may be a wrapper around the SQLite one (a test spies on it): the function goes on the real one.
   */
  def registerJavaLower(connection: java.sql.Connection): Unit = {
    val sqlite =
      if (connection.isWrapperFor(classOf[SQLiteConnection])) connection.unwrap(classOf[SQLiteConnection])
      else connection
    SqliteFunction.create(
      sqlite,
      JavaLower,
      new SqliteFunction {
        override protected def xFunc(): Unit = {
          val text = value_text(0)
          if (text == null) result() else result(text.toLowerCase)
        }
      },
      1,
      SqliteFunction.FLAG_DETERMINISTIC
    )
  }
}
