package org.llm4s.rag.permissions.pg

import org.llm4s.error.ProcessingError
import org.llm4s.rag.permissions._
import org.llm4s.types.Result

import java.sql.Connection
import scala.collection.mutable.ArrayBuffer
import scala.util.{ Try, Using }

/**
 * PostgreSQL implementation of PrincipalStore.
 *
 * Uses the llm4s_principals table to map external identifiers to
 * internal integer IDs:
 * - User IDs are positive (from SERIAL auto-increment)
 * - Group IDs are negative (from llm4s_group_id_seq sequence)
 *
 * @param getConnection Function to obtain a database connection
 */
final class PgPrincipalStore(getConnection: () => Connection) extends PrincipalStore {

  override def getOrCreate(external: ExternalPrincipal): Result[PrincipalId] =
    // First try to lookup
    lookup(external).flatMap {
      case Some(id) => Right(id)
      case None     => create(external)
    }

  private def create(external: ExternalPrincipal): Result[PrincipalId] = Try {
    withConnection { conn =>
      val (sql, _) = external match {
        case _: ExternalPrincipal.User =>
          (
            """INSERT INTO llm4s_principals (external_id, principal_type)
               VALUES (?, 'user')
               RETURNING id""",
            "user"
          )
        case _: ExternalPrincipal.Group =>
          (
            """INSERT INTO llm4s_principals (id, external_id, principal_type)
               VALUES (nextval('llm4s_group_id_seq'), ?, 'group')
               RETURNING id""",
            "group"
          )
      }

      Using.resource(conn.prepareStatement(sql)) { stmt =>
        stmt.setString(1, external.externalId)
        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) {
            Right(PrincipalId(rs.getInt("id")))
          } else {
            Left(ProcessingError("pg-principal-create", "Failed to create principal: no ID returned"))
          }
        }
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-principal-create", e.getMessage)).flatMap(identity)

  override def getOrCreateBatch(externals: Seq[ExternalPrincipal]): Result[Map[ExternalPrincipal, PrincipalId]] =
    // First lookup all existing
    lookupBatch(externals).flatMap { existing =>
      val missing = externals.filterNot(existing.contains)
      if (missing.isEmpty) {
        Right(existing)
      } else {
        // Create missing ones
        val createdResults = missing.map(ext => getOrCreate(ext).map(id => ext -> id))
        val errors         = createdResults.collect { case Left(e) => e }
        if (errors.nonEmpty) {
          Left(errors.head)
        } else {
          val created = createdResults.collect { case Right(pair) => pair }.toMap
          Right(existing ++ created)
        }
      }
    }

  override def lookup(external: ExternalPrincipal): Result[Option[PrincipalId]] = Try {
    withConnection { conn =>
      Using.resource(
        conn.prepareStatement(
          "SELECT id FROM llm4s_principals WHERE external_id = ?"
        )
      ) { stmt =>
        stmt.setString(1, external.externalId)
        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) Some(PrincipalId(rs.getInt("id")))
          else None
        }
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-principal-lookup", e.getMessage))

  override def lookupBatch(externals: Seq[ExternalPrincipal]): Result[Map[ExternalPrincipal, PrincipalId]] = {
    if (externals.isEmpty) {
      return Right(Map.empty)
    }

    Try {
      withConnection { conn =>
        val placeholders = externals.map(_ => "?").mkString(",")
        val sql          = s"SELECT id, external_id FROM llm4s_principals WHERE external_id IN ($placeholders)"

        Using.resource(conn.prepareStatement(sql)) { stmt =>
          externals.zipWithIndex.foreach { case (ext, idx) =>
            stmt.setString(idx + 1, ext.externalId)
          }

          Using.resource(stmt.executeQuery()) { rs =>
            val result = scala.collection.mutable.Map[ExternalPrincipal, PrincipalId]()
            while (rs.next()) {
              val id         = rs.getInt("id")
              val externalId = rs.getString("external_id")
              ExternalPrincipal.parse(externalId).foreach(ext => result(ext) = PrincipalId(id))
            }
            result.toMap
          }
        }
      }
    }.toEither.left.map(e => ProcessingError("pg-principal-lookup-batch", e.getMessage))
  }

  override def getExternalId(id: PrincipalId): Result[Option[ExternalPrincipal]] = Try {
    withConnection { conn =>
      Using.resource(
        conn.prepareStatement(
          "SELECT external_id FROM llm4s_principals WHERE id = ?"
        )
      ) { stmt =>
        stmt.setInt(1, id.value)
        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) {
            ExternalPrincipal.parse(rs.getString("external_id")).toOption
          } else {
            None
          }
        }
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-principal-reverse", e.getMessage))

  override def delete(external: ExternalPrincipal): Result[Unit] = Try {
    withConnection { conn =>
      Using.resource(
        conn.prepareStatement(
          "DELETE FROM llm4s_principals WHERE external_id = ?"
        )
      ) { stmt =>
        stmt.setString(1, external.externalId)
        stmt.executeUpdate()
        ()
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-principal-delete", e.getMessage))

  override def list(principalType: String, limit: Int, offset: Int): Result[Seq[ExternalPrincipal]] = Try {
    withConnection { conn =>
      Using.resource(
        conn.prepareStatement(
          """SELECT external_id FROM llm4s_principals
           WHERE principal_type = ?
           ORDER BY created_at
           LIMIT ? OFFSET ?"""
        )
      ) { stmt =>
        stmt.setString(1, principalType)
        stmt.setInt(2, limit)
        stmt.setInt(3, offset)

        Using.resource(stmt.executeQuery()) { rs =>
          val buffer = ArrayBuffer[ExternalPrincipal]()
          while (rs.next())
            ExternalPrincipal.parse(rs.getString("external_id")).foreach(buffer += _)
          buffer.toSeq
        }
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-principal-list", e.getMessage))

  override def count(principalType: String): Result[Long] = Try {
    withConnection { conn =>
      Using.resource(
        conn.prepareStatement(
          "SELECT COUNT(*) FROM llm4s_principals WHERE principal_type = ?"
        )
      ) { stmt =>
        stmt.setString(1, principalType)
        Using.resource(stmt.executeQuery()) { rs =>
          rs.next()
          rs.getLong(1)
        }
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-principal-count", e.getMessage))

  private def withConnection[A](f: Connection => A): A =
    Using.resource(getConnection())(f)
}
