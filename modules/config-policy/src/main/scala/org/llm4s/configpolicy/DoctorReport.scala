package org.llm4s.configpolicy

import org.llm4s.util.Redaction

import scala.concurrent.duration.*

/** How one step of the setup check ended. `Skipped` means it was not run, and is neither good nor bad. */
enum CheckStatus:
  case Pass, Warn, Fail, Skipped

  /** The label printed in the text report. */
  def label: String = this match
    case Pass    => "PASS"
    case Warn    => "WARN"
    case Fail    => "FAIL"
    case Skipped => "SKIP"

/**
 * One step of the setup check.
 *
 * Every string is passed through [[org.llm4s.util.Redaction]] when the check is built, so a message
 * that quotes a configuration error cannot carry a credential into the report. The doctor never
 * puts an API key's value in a message, and [[Doctor.run]] also hides every key the configuration
 * holds by value; this shape-based pass is the last line of defence, not the first.
 *
 * @param name   what was checked, e.g. `Provider config`
 * @param status how it ended
 * @param detail what was found
 * @param fix    what to change when the step did not pass
 */
final case class DoctorCheck private (name: String, status: CheckStatus, detail: String, fix: Option[String]) {

  /** This check with a fix hint. */
  def withFix(fix: String): DoctorCheck = copy(fix = Some(DoctorCheck.clean(fix)))

  /** This check with every one of `secrets` replaced by `***`, longest first so a secret that contains another is hidden whole. */
  private[configpolicy] def scrubbed(secrets: Set[String]): DoctorCheck = {
    val ordered                    = secrets.toList.sortBy(secret => -secret.length)
    def hide(text: String): String = ordered.foldLeft(text)((acc, secret) => acc.replace(secret, "***"))
    copy(detail = hide(detail), fix = fix.map(hide))
  }
}

object DoctorCheck {

  private[configpolicy] def clean(text: String): String = Redaction.redact(DoctorUrls.scrubText(text))

  def apply(name: String, status: CheckStatus, detail: String): DoctorCheck =
    new DoctorCheck(name, status, clean(detail), None)

  def pass(name: String, detail: String): DoctorCheck    = apply(name, CheckStatus.Pass, detail)
  def warn(name: String, detail: String): DoctorCheck    = apply(name, CheckStatus.Warn, detail)
  def fail(name: String, detail: String): DoctorCheck    = apply(name, CheckStatus.Fail, detail)
  def skipped(name: String, detail: String): DoctorCheck = apply(name, CheckStatus.Skipped, detail)
}

/**
 * How the doctor prints a URL: `scheme://host:port/path`, never its user info, query or fragment, which is
 * where a base URL carries a password, a signature or a token.
 */
private[configpolicy] object DoctorUrls {

  private val InText = """[A-Za-z][A-Za-z0-9+.\-]*://[^\s"'<>]+""".r

  /** `url` without its user info, query and fragment. */
  def display(url: String): String = {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) cutQuery(url)
    else {
      val start = schemeEnd + 3
      val rest  = url.substring(start)
      url.substring(0, start) + cutQuery(rest.substring(userInfoEnd(rest) + 1))
    }
  }

  /**
   * Where the user info ends in `rest` (the URL after `scheme://`): the last '@' before the path, so a password
   * holding '?' or '#' is removed whole; failing that, the last '@' before any '?' or '#', so a password holding
   * '/' is removed whole too. `-1` when there is none.
   */
  private def userInfoEnd(rest: String): Int = {
    val inAuthority = rest.takeWhile(_ != '/').lastIndexOf('@')
    if (inAuthority >= 0) inAuthority else cutQuery(rest).lastIndexOf('@')
  }

  /** `text` with every URL in it shown as [[display]] shows it. */
  def scrubText(text: String): String =
    InText.replaceAllIn(text, m => scala.util.matching.Regex.quoteReplacement(display(m.matched)))

  /**
   * The parts of `url` that are secret: the user info, its password, the query, each query value and the
   * fragment, and the user info and password URL-decoded, as an error message may print them.
   */
  def secretsIn(url: String): List[String] = {
    val schemeEnd                  = url.indexOf("://")
    val rest                       = if (schemeEnd < 0) url else url.substring(schemeEnd + 3)
    val at                         = userInfoEnd(rest)
    val userInfo                   = if (at < 0) "" else rest.substring(0, at)
    val password                   = userInfo.dropWhile(_ != ':').drop(1)
    val afterHost                  = rest.substring(at + 1)
    val (beforeFragment, fragment) = afterHost.span(_ != '#')
    val query                      = beforeFragment.dropWhile(_ != '?').drop(1)
    val values                     = query.split('&').toList.map(_.dropWhile(_ != '=').drop(1))
    val decoded = List(userInfo, password).flatMap(decode).filterNot(d => d == userInfo || d == password)
    (userInfo :: password :: query :: fragment.drop(1) :: values ::: decoded).filter(_.nonEmpty)
  }

  private def decode(text: String): Option[String] =
    scala.util.Try(java.net.URLDecoder.decode(text, java.nio.charset.StandardCharsets.UTF_8)).toOption

  private def cutQuery(text: String): String = text.takeWhile(c => c != '?' && c != '#')
}

/**
 * The outcome of a doctor run.
 *
 * @param checks the steps in the order they were checked
 */
final case class DoctorReport private (checks: List[DoctorCheck]) {

  private def count(status: CheckStatus): Int = checks.count(_.status == status)

  /**
   * The process exit code: `0` when nothing failed or warned, `1` for warnings only, `2` when any step
   * failed. Skipped steps do not count.
   */
  def exitCode: Int =
    if count(CheckStatus.Fail) > 0 then 2
    else if count(CheckStatus.Warn) > 0 then 1
    else 0

  /** The overall verdict: `pass`, `warn` or `fail`. */
  def verdict: String = exitCode match
    case 0 => "pass"
    case 1 => "warn"
    case _ => "fail"

  /** A plain-text checklist, one step per line with its fix hint underneath. */
  def renderText: String = {
    val width = checks.map(_.name.length).maxOption.getOrElse(0)
    val lines = checks.flatMap { check =>
      val head = s"  ${check.status.label}  ${check.name.padTo(width, ' ')}  ${check.detail}"
      head :: check.fix.map(f => s"        ${" " * width}  fix: $f").toList
    }
    val summary =
      s"Result: ${count(CheckStatus.Pass)} passed, ${count(CheckStatus.Warn)} warnings, " +
        s"${count(CheckStatus.Fail)} failed, ${count(CheckStatus.Skipped)} skipped"
    ("llm4s doctor" :: "" :: lines ::: List("", summary)).mkString("\n")
  }

  /** The same report as JSON, for CI. */
  def renderJson: String = {
    val items = checks.map { check =>
      ujson.Obj(
        "name"   -> check.name,
        "status" -> check.status.label.toLowerCase(java.util.Locale.ROOT),
        "detail" -> check.detail,
        "fix"    -> check.fix.fold[ujson.Value](ujson.Null)(ujson.Str(_))
      )
    }
    ujson.write(ujson.Obj("verdict" -> verdict, "exitCode" -> exitCode, "checks" -> ujson.Arr.from(items)), indent = 2)
  }
}

object DoctorReport {
  def apply(checks: List[DoctorCheck]): DoctorReport = new DoctorReport(checks)
}

/**
 * What a doctor run may do. By default it contacts nothing.
 *
 * @param live                 make one small request to the configured provider (billed for a hosted one)
 * @param local                ask a model server on the loopback interface which models it has
 * @param liveTimeout          how long to wait for the live request before reporting it as failed
 * @param applicationClasspath the registry was discovered on the application's classpath (`--classpath`),
 *                             not the doctor's own, which has every built-in provider module
 */
final case class DoctorOptions private (
  live: Boolean,
  local: Boolean,
  liveTimeout: FiniteDuration,
  applicationClasspath: Boolean
) {
  def withLive(live: Boolean): DoctorOptions                      = copy(live = live)
  def withLocal(local: Boolean): DoctorOptions                    = copy(local = local)
  def withLiveTimeout(liveTimeout: FiniteDuration): DoctorOptions = copy(liveTimeout = liveTimeout)
  def withApplicationClasspath(applicationClasspath: Boolean): DoctorOptions =
    copy(applicationClasspath = applicationClasspath)
}

object DoctorOptions {
  def apply(): DoctorOptions =
    new DoctorOptions(live = false, local = false, liveTimeout = 30.seconds, applicationClasspath = false)
}
