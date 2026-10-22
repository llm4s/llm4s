package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.Checkpointer
import org.llm4s.agent.testkit.DelegationContract
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Clock

/** `SqliteCheckpointer` passes the delegation contract (design 4.44): rule 14's table, row by row, on one file per case. */
class SqliteDelegationContractSpec extends AnyFlatSpec with DelegationContract:
  protected def newCheckpointer(): Checkpointer = SqliteFiles.opened(SqliteFiles.fresh(), Clock.systemUTC())
