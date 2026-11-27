package org.llm4s.rag.permissions

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Who may query a collection once every collection above it is taken into account: what
 * [[CollectionStore.effectiveAccess]] returns, and the rule [[CollectionStore.canQuery]] and
 * [[CollectionStore.findAccessible]] apply.
 *
 * A collection is queryable by a non-admin user only if, at every restricted level from the root down to the
 * collection itself, the user holds at least one of that level's principals. The principal may differ from level to
 * level: with `company` queryable by `employees` and `company/executive` by `managers`, a user in both groups may
 * query `company/executive`, and a user in only one may not. An admin may query every collection.
 *
 * Unlike the deprecated [[CollectionStore.getEffectivePermissions]], an empty answer never means public: "anyone" is
 * [[EffectiveAccess.Public]], "these principals at these levels" is [[EffectiveAccess.Restricted]], and "no
 * non-admin user" is [[EffectiveAccess.Nobody]].
 */
sealed trait EffectiveAccess {

  /** True if `auth` may query the collection: the same answer as [[CollectionStore.canQuery]]. */
  def permits(auth: UserAuthorization): Boolean

  /** True if anyone, including an anonymous user, may query the collection. */
  def isPublic: Boolean = this == EffectiveAccess.Public
}

object EffectiveAccess {

  /** No collection on the path restricts access: anyone may query it. */
  case object Public extends EffectiveAccess {
    def permits(auth: UserAuthorization): Boolean = true
  }

  /**
   * Only an admin may query the collection. Returned when a collection on the path is queryable by no principal;
   * a collection's own permissions cannot say that yet (an empty `queryableBy` means public), so this is what an
   * explicit "nobody" visibility will produce ([#3014](https://github.com/llm4s/llm4s/issues/3014)).
   */
  case object Nobody extends EffectiveAccess {
    def permits(auth: UserAuthorization): Boolean = auth.isAdmin
  }

  /**
   * One restricted collection on the path, and the principals that satisfy it.
   *
   * @param path The restricted collection: the queried collection or one of its ancestors
   * @param anyOf Its `queryableBy`: holding any one of these satisfies this level. Never empty.
   */
  final case class Level private[permissions] (path: CollectionPath, anyOf: Set[PrincipalId])

  /**
   * The collection is restricted: a non-admin user needs a principal of every level.
   *
   * @param levels The restricted collections from the root down to the queried one, public ones left out. Never
   *               empty.
   */
  final case class Restricted private[permissions] (levels: Seq[Level]) extends EffectiveAccess {
    def permits(auth: UserAuthorization): Boolean =
      auth.isAdmin || levels.forall(level => auth.principalIds.exists(level.anyOf.contains))
  }

  /**
   * The effective access of the last collection of `chain`, given the collection and all its ancestors from the root
   * down. A level whose `queryableBy` is empty is public and adds no requirement.
   *
   * @param chain The root first and the queried collection last
   */
  def of(chain: Seq[Collection]): EffectiveAccess = {
    val levels = chain.collect { case c if c.queryableBy.nonEmpty => Level(c.path, c.queryableBy) }
    if (levels.isEmpty) Public else Restricted(levels)
  }

  /**
   * The answer of the deprecated [[CollectionStore.getEffectivePermissions]], which can only say "public" (an empty
   * set) or "any of these principals": the principals that satisfy every level alone, or an error when there are
   * none, since an empty set would read as public.
   */
  private[permissions] def legacyPrincipals(path: CollectionPath, access: EffectiveAccess): Result[Set[PrincipalId]] =
    access match {
      case Public => Right(Set.empty)
      case Restricted(levels) =>
        val everyLevel = levels.map(_.anyOf).reduce(_.intersect(_))
        if (everyLevel.nonEmpty) Right(everyLevel)
        else Left(notOneSet(path, "no single principal satisfies every restricted level above it"))
      case Nobody => Left(notOneSet(path, "it is queryable by no principal"))
    }

  private def notOneSet(path: CollectionPath, why: String): ValidationError =
    ValidationError(
      "permissions",
      s"The effective permissions of ${path.value} are not one set of principals: $why. Use effectiveAccess."
    )
}
