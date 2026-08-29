package org.llm4s.rag.loader.internal

import org.llm4s.security.RegexSafetyManager

import java.util.regex.Pattern
import scala.collection.mutable

/**
 * Utility for matching URLs against glob-style patterns.
 *
 * Supports:
 *  - asterisk matches any string (non-greedy within path segments)
 *  - double asterisk matches any string including path separators
 *  - question mark matches single character
 *  - Literal matching for other characters
 *
 * Examples:
 *  - subdomain.example.com/path matches any subdomain and path
 *  - example.com/docs/anything matches any path under /docs/
 *  - example.com/page1.html matches page1.html, page2.html, etc.
 */
object GlobPatternMatcher {

  // Cache compiled patterns for performance
  private val patternCache = mutable.Map[String, Pattern]()

  /**
   * Check if a URL matches any of the given patterns.
   *
   * @param url URL to check
   * @param patterns Glob patterns to match against
   * @return true if URL matches any pattern
   */
  def matchesAny(url: String, patterns: Seq[String]): Boolean =
    patterns.exists(matches(url, _))

  /**
   * Check if a URL matches a glob pattern.
   *
   * @param url URL to check
   * @param pattern Glob pattern
   * @return true if URL matches pattern
   */
  def matches(url: String, pattern: String): Boolean = {
    val regex = patternCache.getOrElseUpdate(pattern, compileGlob(pattern))
    regex.matcher(url).matches()
  }

  /**
   * Filter a list of URLs by patterns.
   *
   * @param urls URLs to filter
   * @param includePatterns Patterns to include (empty = include all)
   * @param excludePatterns Patterns to exclude
   * @return URLs matching include patterns and not matching exclude patterns
   */
  def filter(
    urls: Seq[String],
    includePatterns: Seq[String],
    excludePatterns: Seq[String]
  ): Seq[String] =
    urls.filter { url =>
      val matchesInclude = includePatterns.isEmpty || matchesAny(url, includePatterns)
      val matchesExclude = matchesAny(url, excludePatterns)
      matchesInclude && !matchesExclude
    }

  /**
   * Compile a glob pattern to a regex Pattern.
   */
  private def compileGlob(glob: String): Pattern = {
    val sb = new StringBuilder
    var i  = 0

    while (i < glob.length) {
      val c = glob.charAt(i)
      c match {
        case '*' =>
          // Check for **
          if (i + 1 < glob.length && glob.charAt(i + 1) == '*') {
            sb.append(".*") // ** matches anything including /
            i += 1
          } else {
            sb.append("[^/]*") // * matches anything except /
          }

        case '?' =>
          sb.append("[^/]") // ? matches single non-slash char

        case '.' | '(' | ')' | '[' | ']' | '{' | '}' | '+' | '^' | '$' | '|' | '\\' =>
          sb.append('\\')
          sb.append(c)

        case _ =>
          sb.append(c)
      }
      i += 1
    }

    RegexSafetyManager.safeCompile(sb.toString, Pattern.CASE_INSENSITIVE) match {
      case Right(regex) => regex
      case Left(_)      => RegexSafetyManager.compileLiteral(glob, Pattern.CASE_INSENSITIVE)
    }
  }

  /**
   * Clear the pattern cache (for testing or memory management).
   */
  def clearCache(): Unit = patternCache.clear()
}
