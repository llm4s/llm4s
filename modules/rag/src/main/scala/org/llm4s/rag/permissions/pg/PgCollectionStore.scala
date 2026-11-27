package org.llm4s.rag.permissions.pg

import org.llm4s.util.Redaction
import org.llm4s.error.{ ProcessingError, ValidationError }
import org.llm4s.rag.permissions._
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ PgMetadataJson, SqlLikePattern }

import java.sql.{ Array => SqlArray, Connection, ResultSet }
import scala.collection.mutable.ArrayBuffer
import scala.util.{ Failure, Try, Using }

/**
 * PostgreSQL implementation of CollectionStore.
 *
 * Manages the collection hierarchy with permission inheritance.
 *
 * @param getConnection Function to obtain a database connection
 * @param vectorTableName Name of the vectors table (for document/chunk counts)
 */
final class PgCollectionStore(
  getConnection: () => Connection,
  vectorTableName: String = "vectors"
) extends CollectionStore {

  /**
   * Creates the collection, making its parent a non-leaf if it was a leaf, in one transaction (#2991).
   *
   * The parent's row is locked first (`FOR UPDATE`), so an ingest into it, which takes a share lock on the same row,
   * either finishes before the check for its documents or sees the parent as a non-leaf and is refused. A leaf
   * holding documents is never demoted: queries used to skip non-leaf collections, and its documents vanished.
   */
  override def create(config: CollectionConfig): Result[Collection] = Try {
    withTransaction { conn =>
      for {
        _ <- config.parentPath match {
          case Some(parent) => prepareParent(conn, parent, config)
          case None         => Right(())
        }
        collection <- insert(conn, config)
      } yield collection
    }
  }.toEither.left
    .map(e => ProcessingError("pg-collection-create", Redaction.scrubKnown(e.getMessage)))
    .flatMap(identity)

  /** Locks `parent`, checks `config` may go under it, and makes it a non-leaf if it is an empty leaf. */
  private def prepareParent(conn: Connection, parent: CollectionPath, config: CollectionConfig): Result[Unit] =
    for {
      parentColl <- lockForUpdate(conn, parent).toRight(
        ValidationError("collection", s"Parent collection not found: ${parent.value}")
      )
      _ <- validateNotPublicUnderRestricted(parentColl.queryableBy, config.queryableBy)
      _ <-
        if (!parentColl.isLeaf) Right(())
        else if (holdsChunks(conn, parentColl.id))
          Left(
            ValidationError(
              "collection",
              s"Cannot create ${config.path.value}: its parent ${parent.value} is a leaf holding documents, and a " +
                "collection with children holds none. Clear the parent first, or create the collection elsewhere."
            )
          )
        else Right(markAsNonLeaf(conn, parentColl.id))
    } yield ()

  private def lockForUpdate(conn: Connection, path: CollectionPath): Option[Collection] =
    Using.resource(
      conn.prepareStatement(
        "SELECT id, path, parent_path, queryable_by, is_leaf, metadata FROM llm4s_collections WHERE path = ? FOR UPDATE"
      )
    ) { stmt =>
      stmt.setString(1, path.value)
      Using.resource(stmt.executeQuery())(rs => if (rs.next()) Some(rowToCollection(rs)) else None)
    }

  private def holdsChunks(conn: Connection, collectionId: Int): Boolean =
    Using.resource(
      conn.prepareStatement(s"SELECT EXISTS (SELECT 1 FROM $vectorTableName WHERE collection_id = ?)")
    ) { stmt =>
      stmt.setInt(1, collectionId)
      Using.resource(stmt.executeQuery())(rs => rs.next() && rs.getBoolean(1))
    }

  private def markAsNonLeaf(conn: Connection, collectionId: Int): Unit =
    Using.resource(
      conn.prepareStatement("UPDATE llm4s_collections SET is_leaf = FALSE, updated_at = NOW() WHERE id = ?")
    ) { stmt =>
      stmt.setInt(1, collectionId)
      stmt.executeUpdate()
      ()
    }

  private def insert(conn: Connection, config: CollectionConfig): Result[Collection] = {
    val queryableByArray = createIntArray(conn, config.queryableBy.map(_.value).toSeq)
    val metadataJson     = PgMetadataJson.write(config.metadata)

    Using.resource(conn.prepareStatement("""
      INSERT INTO llm4s_collections (path, parent_path, queryable_by, is_leaf, metadata)
      VALUES (?, ?, ?, ?, ?::jsonb)
      RETURNING id, path, parent_path, queryable_by, is_leaf, metadata
    """)) { stmt =>
      stmt.setString(1, config.path.value)
      stmt.setString(2, config.parentPath.map(_.value).orNull)
      stmt.setArray(3, queryableByArray)
      stmt.setBoolean(4, config.isLeaf)
      stmt.setString(5, metadataJson)

      Using.resource(stmt.executeQuery()) { rs =>
        if (rs.next()) Right(rowToCollection(rs))
        else Left(ProcessingError("pg-collection-create", "Failed to create collection: no row returned"))
      }
    }
  }

  override def get(path: CollectionPath): Result[Option[Collection]] = Try {
    withConnection { conn =>
      Using.resource(
        conn.prepareStatement(
          "SELECT id, path, parent_path, queryable_by, is_leaf, metadata FROM llm4s_collections WHERE path = ?"
        )
      ) { stmt =>
        stmt.setString(1, path.value)
        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) Some(rowToCollection(rs))
          else None
        }
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-collection-get", Redaction.scrubKnown(e.getMessage)))

  override def getById(id: Int): Result[Option[Collection]] = Try {
    withConnection { conn =>
      Using.resource(
        conn.prepareStatement(
          "SELECT id, path, parent_path, queryable_by, is_leaf, metadata FROM llm4s_collections WHERE id = ?"
        )
      ) { stmt =>
        stmt.setInt(1, id)
        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) Some(rowToCollection(rs))
          else None
        }
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-collection-get-by-id", Redaction.scrubKnown(e.getMessage)))

  override def list(pattern: CollectionPattern): Result[Seq[Collection]] = Try {
    withConnection { conn =>
      val (whereClause, params) = PgCollectionStore.patternToSql(pattern)
      val sql =
        s"SELECT id, path, parent_path, queryable_by, is_leaf, metadata FROM llm4s_collections WHERE $whereClause ORDER BY path"

      Using.resource(conn.prepareStatement(sql)) { stmt =>
        params.zipWithIndex.foreach { case (param, idx) =>
          stmt.setString(idx + 1, param)
        }
        Using.resource(stmt.executeQuery()) { rs =>
          val buffer = ArrayBuffer[Collection]()
          while (rs.next())
            buffer += rowToCollection(rs)
          buffer.toSeq
        }
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-collection-list", Redaction.scrubKnown(e.getMessage)))

  override def findAccessible(
    auth: UserAuthorization,
    pattern: CollectionPattern
  ): Result[Seq[Collection]] = Try {
    withConnection { conn =>
      val (patternClause, patternParams) = PgCollectionStore.patternToSql(pattern)

      val sql = if (auth.isAdmin) {
        s"""SELECT id, path, parent_path, queryable_by, is_leaf, metadata
            FROM llm4s_collections
            WHERE $patternClause
            ORDER BY path"""
      } else {
        // Collection is accessible if:
        // 1. queryable_by is empty (public), OR
        // 2. queryable_by overlaps with user's principal IDs
        // Also need to check ancestors are accessible
        s"""WITH RECURSIVE accessible_collections AS (
              -- Base case: root collections that are accessible
              SELECT c.id, c.path, c.parent_path, c.queryable_by, c.is_leaf, c.metadata
              FROM llm4s_collections c
              WHERE c.parent_path IS NULL
                AND (c.queryable_by = '{}' OR c.queryable_by && ?)

              UNION ALL

              -- Recursive case: children of accessible collections
              SELECT c.id, c.path, c.parent_path, c.queryable_by, c.is_leaf, c.metadata
              FROM llm4s_collections c
              INNER JOIN accessible_collections ac ON c.parent_path = ac.path
              WHERE c.queryable_by = '{}' OR c.queryable_by && ?
            )
            SELECT * FROM accessible_collections
            WHERE $patternClause
            ORDER BY path"""
      }

      Using.resource(conn.prepareStatement(sql)) { stmt =>
        var paramIdx = 1

        if (!auth.isAdmin) {
          val authArray = createIntArray(conn, auth.asSeq)
          stmt.setArray(paramIdx, authArray)
          paramIdx += 1
          stmt.setArray(paramIdx, authArray)
          paramIdx += 1
        }

        patternParams.foreach { param =>
          stmt.setString(paramIdx, param)
          paramIdx += 1
        }

        Using.resource(stmt.executeQuery()) { rs =>
          val buffer = ArrayBuffer[Collection]()
          while (rs.next())
            buffer += rowToCollection(rs)
          buffer.toSeq
        }
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-collection-find-accessible", Redaction.scrubKnown(e.getMessage)))

  override def updatePermissions(
    path: CollectionPath,
    queryableBy: Set[PrincipalId]
  ): Result[Collection] =
    for {
      // Get current collection
      collOpt <- get(path)
      coll    <- collOpt.toRight(ProcessingError("pg-collection-update", s"Collection not found: ${path.value}"))
      // Validate against parent
      _ <- coll.parentPath match {
        case Some(parent) =>
          for {
            parentOpt <- get(parent)
            parentColl <- parentOpt.toRight(
              ProcessingError("pg-collection-update", s"Parent not found: ${parent.value}")
            )
            _ <- validateNotPublicUnderRestricted(parentColl.queryableBy, queryableBy)
          } yield ()
        case None => Right(())
      }
      // Do the update
      updated <- doUpdatePermissions(path, queryableBy)
    } yield updated

  private def doUpdatePermissions(path: CollectionPath, queryableBy: Set[PrincipalId]): Result[Collection] = Try {
    withConnection { conn =>
      val queryableByArray = createIntArray(conn, queryableBy.map(_.value).toSeq)

      Using.resource(conn.prepareStatement("""
        UPDATE llm4s_collections
        SET queryable_by = ?, updated_at = NOW()
        WHERE path = ?
        RETURNING id, path, parent_path, queryable_by, is_leaf, metadata
      """)) { stmt =>
        stmt.setArray(1, queryableByArray)
        stmt.setString(2, path.value)

        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) Right(rowToCollection(rs))
          else Left(ProcessingError("pg-collection-update-perms", s"Collection not found: ${path.value}"))
        }
      }
    }
  }.toEither.left
    .map(e => ProcessingError("pg-collection-update-perms", Redaction.scrubKnown(e.getMessage)))
    .flatMap(identity)

  override def updateMetadata(
    path: CollectionPath,
    metadata: Map[String, String]
  ): Result[Collection] = Try {
    withConnection { conn =>
      val metadataJson = PgMetadataJson.write(metadata)

      Using.resource(conn.prepareStatement("""
        UPDATE llm4s_collections
        SET metadata = ?::jsonb, updated_at = NOW()
        WHERE path = ?
        RETURNING id, path, parent_path, queryable_by, is_leaf, metadata
      """)) { stmt =>
        stmt.setString(1, metadataJson)
        stmt.setString(2, path.value)

        Using.resource(stmt.executeQuery()) { rs =>
          if (rs.next()) Right(rowToCollection(rs))
          else Left(ProcessingError("pg-collection-update-metadata", s"Collection not found: ${path.value}"))
        }
      }
    }
  }.toEither.left
    .map(e => ProcessingError("pg-collection-update-metadata", Redaction.scrubKnown(e.getMessage)))
    .flatMap(identity)

  override def delete(path: CollectionPath): Result[Unit] =
    for {
      // Check no children
      children <- listChildren(path)
      _ <-
        if (children.nonEmpty) Left(ValidationError("collection", s"Collection has ${children.size} children"))
        else Right(())
      // Check no documents
      docCount <- countDocuments(path)
      _ <- if (docCount > 0) Left(ValidationError("collection", s"Collection has $docCount documents")) else Right(())
      // Delete
      _ <- doDelete(path)
    } yield ()

  private def doDelete(path: CollectionPath): Result[Unit] = Try {
    withConnection { conn =>
      Using.resource(
        conn.prepareStatement(
          "DELETE FROM llm4s_collections WHERE path = ?"
        )
      ) { stmt =>
        stmt.setString(1, path.value)
        stmt.executeUpdate()
        ()
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-collection-delete", Redaction.scrubKnown(e.getMessage)))

  /** The collection and its ancestors in one query, root first. */
  override def effectiveAccess(path: CollectionPath): Result[EffectiveAccess] = {
    val chain = Iterator.iterate(Option(path))(_.flatMap(_.parent)).takeWhile(_.isDefined).flatten.toList.reverse
    Try {
      withConnection { conn =>
        Using.resource(
          conn.prepareStatement(
            "SELECT id, path, parent_path, queryable_by, is_leaf, metadata FROM llm4s_collections WHERE path = ANY(?)"
          )
        ) { stmt =>
          stmt.setArray(1, conn.createArrayOf("text", chain.map(_.value).toArray[AnyRef]))
          Using.resource(stmt.executeQuery()) { rs =>
            val found = ArrayBuffer[Collection]()
            while (rs.next())
              found += rowToCollection(rs)
            found.map(c => c.path -> c).toMap
          }
        }
      }
    }.toEither.left
      .map(e => ProcessingError("pg-collection-effective", Redaction.scrubKnown(e.getMessage)))
      .flatMap { byPath =>
        chain
          .foldLeft[Result[List[Collection]]](Right(Nil)) { (acc, p) =>
            acc.flatMap(found =>
              byPath
                .get(p)
                .toRight(ProcessingError("pg-collection-effective", s"Collection not found: ${p.value}"))
                .map(found :+ _)
            )
          }
          .map(EffectiveAccess.of)
      }
  }

  @deprecated("an empty set reads as public; use effectiveAccess", since = "1.1.0")
  override def getEffectivePermissions(path: CollectionPath): Result[Set[PrincipalId]] =
    effectiveAccess(path).flatMap(EffectiveAccess.legacyPrincipals(path, _))

  /**
   * The answer [[findAccessible]] gives for exactly `path` (#2990): the same recursive query, so a public child of a
   * restricted parent, or a child whose principals the parent does not list, is decided as a search decides it.
   */
  override def canQuery(path: CollectionPath, auth: UserAuthorization): Result[Boolean] =
    get(path).flatMap {
      case None => Left(ProcessingError("pg-collection-can-query", s"Collection not found: ${path.value}"))
      case Some(_) if auth.isAdmin => Right(true)
      case Some(_)                 => findAccessible(auth, CollectionPattern.Exact(path)).map(_.nonEmpty)
    }

  override def listChildren(parentPath: CollectionPath): Result[Seq[Collection]] = Try {
    withConnection { conn =>
      Using.resource(conn.prepareStatement("""
        SELECT id, path, parent_path, queryable_by, is_leaf, metadata
        FROM llm4s_collections
        WHERE parent_path = ?
        ORDER BY path
      """)) { stmt =>
        stmt.setString(1, parentPath.value)
        Using.resource(stmt.executeQuery()) { rs =>
          val buffer = ArrayBuffer[Collection]()
          while (rs.next())
            buffer += rowToCollection(rs)
          buffer.toSeq
        }
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-collection-list-children", Redaction.scrubKnown(e.getMessage)))

  override def countDocuments(path: CollectionPath): Result[Long] = Try {
    withConnection { conn =>
      // Count unique docIds in the vectors table for this collection
      Using.resource(conn.prepareStatement(s"""
        SELECT COUNT(DISTINCT v.metadata->>'docId')
        FROM $vectorTableName v
        JOIN llm4s_collections c ON v.collection_id = c.id
        WHERE c.path = ?
      """)) { stmt =>
        stmt.setString(1, path.value)
        Using.resource(stmt.executeQuery()) { rs =>
          rs.next()
          rs.getLong(1)
        }
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-collection-count-docs", Redaction.scrubKnown(e.getMessage)))

  override def countChunks(path: CollectionPath): Result[Long] = Try {
    withConnection { conn =>
      Using.resource(conn.prepareStatement(s"""
        SELECT COUNT(*)
        FROM $vectorTableName v
        JOIN llm4s_collections c ON v.collection_id = c.id
        WHERE c.path = ?
      """)) { stmt =>
        stmt.setString(1, path.value)
        Using.resource(stmt.executeQuery()) { rs =>
          rs.next()
          rs.getLong(1)
        }
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-collection-count-chunks", Redaction.scrubKnown(e.getMessage)))

  override def stats(path: CollectionPath): Result[CollectionStats] =
    for {
      docCount   <- countDocuments(path)
      chunkCount <- countChunks(path)
      children   <- listChildren(path)
    } yield CollectionStats(docCount, chunkCount, children.size)

  override def ensureExists(config: CollectionConfig): Result[Collection] =
    get(config.path).flatMap {
      case Some(existing) => Right(existing)
      case None           =>
        // Need to ensure parent exists first
        config.parentPath match {
          case Some(parent) =>
            for {
              _    <- ensureExists(CollectionConfig.publicParent(parent))
              coll <- create(config)
            } yield coll
          case None =>
            create(config)
        }
    }

  // Helper methods

  /**
   * A child of a restricted collection may not be public. It may name any principals, including ones its parent does
   * not: a query needs a principal at every restricted level, so a child can only narrow who reaches it (#2990).
   */
  private def validateNotPublicUnderRestricted(
    parent: Set[PrincipalId],
    child: Set[PrincipalId]
  ): Result[Unit] =
    if (parent.nonEmpty && child.isEmpty)
      Left(ValidationError("permissions", "Cannot make collection public when parent is restricted"))
    else Right(())

  private def rowToCollection(rs: ResultSet): Collection = {
    val queryableByArray = Option(rs.getArray("queryable_by"))
      .map(_.getArray.asInstanceOf[Array[java.lang.Integer]])
      .getOrElse(Array.empty[java.lang.Integer])
    val queryableBy = queryableByArray.map(i => PrincipalId(i.intValue())).toSet

    val parentPath = Option(rs.getString("parent_path")).flatMap(p => CollectionPath.create(p).toOption)
    val path       = CollectionPath.unsafe(rs.getString("path"))
    val metadata   = PgMetadataJson.read(rs.getString("metadata"))

    Collection(
      id = rs.getInt("id"),
      path = path,
      parentPath = parentPath,
      queryableBy = queryableBy,
      isLeaf = rs.getBoolean("is_leaf"),
      metadata = metadata
    )
  }

  private def createIntArray(conn: Connection, values: Seq[Int]): SqlArray =
    conn.createArrayOf("integer", values.map(Int.box).toArray)

  private def withConnection[A](f: Connection => A): A =
    Using.resource(getConnection())(f)

  /** Runs `f` in one transaction: committed if it returns a `Right`, rolled back if it returns a `Left` or throws. */
  private def withTransaction[A](f: Connection => Result[A]): Result[A] =
    withConnection { conn =>
      Using.resource(new AutoCloseable {
        private val original = conn.getAutoCommit
        conn.setAutoCommit(false)
        override def close(): Unit = conn.setAutoCommit(original)
      }) { _ =>
        Try(f(conn))
          .flatMap {
            case done @ Right(_)   => Try(conn.commit()).map(_ => done)
            case refused @ Left(_) => Try(conn.rollback()).map(_ => refused)
          }
          .recoverWith { case e: Exception => Try(conn.rollback()).flatMap(_ => Failure(e)) }
          .get
      }
    }
}

private[pg] object PgCollectionStore {

  /**
   * The `WHERE` clause and parameters selecting the collections `pattern` matches.
   *
   * `AllDescendants` escapes the prefix before using it in `LIKE`: `_` is a legal path character and a
   * `LIKE` wildcard, so `tenant_1/%` also matched `tenant11/...` and `tenant-1/...` (#2989), unlike the
   * exact in-memory [[CollectionPattern.matches]].
   */
  def patternToSql(pattern: CollectionPattern): (String, Seq[String]) = pattern match {
    case CollectionPattern.All                       => ("TRUE", Seq.empty)
    case CollectionPattern.Exact(path)               => ("path = ?", Seq(path.value))
    case CollectionPattern.ImmediateChildren(parent) => ("parent_path = ?", Seq(parent.value))
    case CollectionPattern.AllDescendants(prefix) =>
      (
        s"(path = ? OR path LIKE ? ${SqlLikePattern.EscapeClause})",
        Seq(prefix.value, SqlLikePattern.prefix(s"${prefix.value}/"))
      )
  }
}
