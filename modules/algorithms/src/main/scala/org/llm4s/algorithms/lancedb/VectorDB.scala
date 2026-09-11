package org.llm4s.algorithms.lancedb

import scala.collection.mutable

class VectorDB private (val name: String):
  private val tables = mutable.Map.empty[String, VectorTable]

  def createTable(name: String, schema: Schema): VectorTable =
    require(!tables.contains(name), s"Table '$name' already exists")
    val table = new VectorTable(name, schema)
    tables(name) = table
    table

  def openTable(name: String): Option[VectorTable] =
    tables.get(name)

  def dropTable(name: String): Boolean =
    tables.remove(name).isDefined

  def tableNames: Seq[String] =
    tables.keys.toSeq.sorted

object VectorDB:
  def open(name: String): VectorDB = new VectorDB(name)
