package org.llm4s.rag.permissions.pg

import org.llm4s.error.{ ProcessingError, ValidationError }
import org.llm4s.rag.permissions._
import org.llm4s.types.Result

import java.sql.{ Array => SqlArray, Connection, ResultSet }
import scala.collection.mutable.ArrayBuffer
import scala.util.{ Try, Using }

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

  override def create(config: CollectionConfig): Result[Collection] =
    for {
      // Validate parent exists and permissions are valid
      _ <- config.parentPath match {
        case Some(parent) =>
          for {
            parentOpt <- get(parent)
            _ <- parentOpt.toRight(ValidationError("collection", s"Parent collection not found: ${parent.value}"))
            parentColl = parentOpt.get
            _ <- validatePermissionsNotLoosened(parentColl.queryableBy, config.queryableBy)
            // Mark parent as non-leaf if it was a leaf
            _ <- if (parentColl.isLeaf) markAsNonLeaf(parent) else Right(())
          } yield ()
        case None => Right(())
      }
      collection <- doCreate(config)
    } yield collection

  private def doCreate(config: CollectionConfig): Result[Collection] = Try {
    withConnection { conn =>
      val queryableByArray = createIntArray(conn, config.queryableBy.map(_.value).toSeq)
      val metadataJson     = mapToJson(config.metadata)

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
  }.toEither.left.map(e => ProcessingError("pg-collection-create", e.getMessage)).flatMap(identity)

  private def markAsNonLeaf(path: CollectionPath): Result[Unit] = Try {
    withConnection { conn =>
      Using.resource(
        conn.prepareStatement(
          "UPDATE llm4s_collections SET is_leaf = FALSE, updated_at = NOW() WHERE path = ?"
        )
      ) { stmt =>
        stmt.setString(1, path.value)
        stmt.executeUpdate()
        ()
      }
    }
  }.toEither.left.map(e => ProcessingError("pg-collection-update", e.getMessage))

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
  }.toEither.left.map(e => ProcessingError("pg-collection-get", e.getMessage))

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
  }.toEither.left.map(e => ProcessingError("pg-collection-get-by-id", e.getMessage))

  override def list(pattern: CollectionPattern): Result[Seq[Collection]] = Try {
    withConnection { conn =>
      val (whereClause, params) = patternToSql(pattern)
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
  }.toEither.left.map(e => ProcessingError("pg-collection-list", e.getMessage))

  override def findAccessible(
    auth: UserAuthorization,
    pattern: CollectionPattern
  ): Result[Seq[Collection]] = Try {
    withConnection { conn =>
      val (patternClause, patternParams) = patternToSql(pattern)

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
  }.toEither.left.map(e => ProcessingError("pg-collection-find-accessible", e.getMessage))

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
            _ <- validatePermissionsNotLoosened(parentColl.queryableBy, queryableBy)
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
  }.toEither.left.map(e => ProcessingError("pg-collection-update-perms", e.getMessage)).flatMap(identity)

  override def updateMetadata(
    path: CollectionPath,
    metadata: Map[String, String]
  ): Result[Collection] = Try {
    withConnection { conn =>
      val metadataJson = mapToJson(metadata)

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
  }.toEither.left.map(e => ProcessingError("pg-collection-update-metadata", e.getMessage)).flatMap(identity)

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
  }.toEither.left.map(e => ProcessingError("pg-collection-delete", e.getMessage))

  override def getEffectivePermissions(path: CollectionPath): Result[Set[PrincipalId]] =
    for {
      collOpt <- get(path)
      coll    <- collOpt.toRight(ProcessingError("pg-collection-effective", s"Collection not found: ${path.value}"))
      result  <- computeEffectivePermissions(coll)
    } yield result

  private def computeEffectivePermissions(coll: Collection): Result[Set[PrincipalId]] =
    if (coll.queryableBy.isEmpty) {
      // This collection is public
      Right(Set.empty)
    } else {
      coll.parentPath match {
        case None =>
          // Root collection - just return its own permissions
          Right(coll.queryableBy)
        case Some(parentPath) =>
          // Get parent and intersect permissions
          for {
            parentOpt <- get(parentPath)
            parentColl <- parentOpt.toRight(
              ProcessingError("pg-collection-effective", s"Parent not found: ${parentPath.value}")
            )
            parentEffective <- computeEffectivePermissions(parentColl)
          } yield
            if (parentEffective.isEmpty) {
              // Parent is public, use this collection's permissions
              coll.queryableBy
            } else {
              // Intersect with parent (child can only restrict, never expand)
              coll.queryableBy.intersect(parentEffective)
            }
      }
    }

  override def canQuery(path: CollectionPath, auth: UserAuthorization): Result[Boolean] =
    if (auth.isAdmin) {
      Right(true)
    } else {
      for {
        effective <- getEffectivePermissions(path)
      } yield effective.isEmpty || auth.principalIds.exists(effective.contains)
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
  }.toEither.left.map(e => ProcessingError("pg-collection-list-children", e.getMessage))

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
  }.toEither.left.map(e => ProcessingError("pg-collection-count-docs", e.getMessage))

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
  }.toEither.left.map(e => ProcessingError("pg-collection-count-chunks", e.getMessage))

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

  private def patternToSql(pattern: CollectionPattern): (String, Seq[String]) = pattern match {
    case CollectionPattern.All                       => ("TRUE", Seq.empty)
    case CollectionPattern.Exact(path)               => ("path = ?", Seq(path.value))
    case CollectionPattern.ImmediateChildren(parent) => ("parent_path = ?", Seq(parent.value))
    case CollectionPattern.AllDescendants(prefix) =>
      ("(path = ? OR path LIKE ?)", Seq(prefix.value, s"${prefix.value}/%"))
  }

  private def validatePermissionsNotLoosened(
    parent: Set[PrincipalId],
    child: Set[PrincipalId]
  ): Result[Unit] =
    if (parent.isEmpty) {
      // Parent is public, child can have any permissions (including restricting)
      Right(())
    } else if (child.isEmpty) {
      // Child is public but parent is restricted - NOT allowed
      Left(ValidationError("permissions", "Cannot make collection public when parent is restricted"))
    } else {
      // Child permissions must be a subset of parent permissions.
      // This prevents accidentally loosening permissions through disjoint sets
      // (where intersection would be empty, treated as public).
      val invalidPrincipals = child -- parent
      if (invalidPrincipals.nonEmpty) {
        Left(
          ValidationError(
            "permissions",
            s"Child collection cannot grant access to principals not in parent: ${invalidPrincipals.map(_.value).mkString(", ")}"
          )
        )
      } else {
        Right(())
      }
    }

  private def rowToCollection(rs: ResultSet): Collection = {
    val queryableByArray = Option(rs.getArray("queryable_by"))
      .map(_.getArray.asInstanceOf[Array[java.lang.Integer]])
      .getOrElse(Array.empty[java.lang.Integer])
    val queryableBy = queryableByArray.map(i => PrincipalId(i.intValue())).toSet

    val parentPath = Option(rs.getString("parent_path")).flatMap(p => CollectionPath.create(p).toOption)
    val path       = CollectionPath.unsafe(rs.getString("path"))
    val metadata   = jsonToMap(rs.getString("metadata"))

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

  private def mapToJson(map: Map[String, String]): String =
    if (map.isEmpty) "{}"
    else {
      val entries = map.map { case (k, v) =>
        val escapedKey   = k.replace("\"", "\\\"")
        val escapedValue = v.replace("\"", "\\\"")
        s""""$escapedKey":"$escapedValue""""
      }
      s"{${entries.mkString(",")}}"
    }

  private def jsonToMap(json: String): Map[String, String] =
    if (json == null || json.isEmpty || json == "{}") Map.empty
    else {
      // Simple JSON parsing for flat string maps
      val pattern = """"([^"]+)"\s*:\s*"([^"]*)"""".r
      pattern.findAllMatchIn(json).map(m => m.group(1) -> m.group(2)).toMap
    }

  private def withConnection[A](f: Connection => A): A =
    Using.resource(getConnection())(f)
}
