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
 * reads an API key's value; this is a second line of defence, not the first.
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

  private[configpolicy] def clean(text: String): String = Redaction.redact(text)

  def apply(name: String, status: CheckStatus, detail: String): DoctorCheck =
    new DoctorCheck(name, status, clean(detail), None)

  def pass(name: String, detail: String): DoctorCheck    = apply(name, CheckStatus.Pass, detail)
  def warn(name: String, detail: String): DoctorCheck    = apply(name, CheckStatus.Warn, detail)
  def fail(name: String, detail: String): DoctorCheck    = apply(name, CheckStatus.Fail, detail)
  def skipped(name: String, detail: String): DoctorCheck = apply(name, CheckStatus.Skipped, detail)
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
 * What a doctor run may do.
 *
 * @param live        make one small request to the configured provider (billed for a hosted one); off by default
 * @param offline     do not contact the configured local model server either
 * @param liveTimeout how long to wait for the live request before reporting it as failed
 */
final case class DoctorOptions private (live: Boolean, offline: Boolean, liveTimeout: FiniteDuration) {
  def withLive(live: Boolean): DoctorOptions                      = copy(live = live)
  def withOffline(offline: Boolean): DoctorOptions                = copy(offline = offline)
  def withLiveTimeout(liveTimeout: FiniteDuration): DoctorOptions = copy(liveTimeout = liveTimeout)
}

object DoctorOptions {
  def apply(): DoctorOptions = new DoctorOptions(live = false, offline = false, liveTimeout = 30.seconds)
}
