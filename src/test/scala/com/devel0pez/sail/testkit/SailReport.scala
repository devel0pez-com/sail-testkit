package com.devel0pez.sail.testkit

import java.io.PrintStream
import java.net.URI

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import io.cucumber.plugin.event._
import io.cucumber.plugin.{ColorAware, ConcurrentEventListener}

/** Turns the corpus run into something a person can read, classified by tag.
  *
  * The corpus encodes **Spark's** behaviour, so the tags decide what a result means and a bare
  * pass/fail count says almost nothing:
  *
  *   - failing against Spark -> our harness is wrong, nothing else
  *   - `@sail-bug` failing -> expected; Sail already knows
  *   - untagged failing -> a divergence nobody has recorded yet
  *   - `@sail-bug` passing -> possibly fixed upstream, worth a look
  *
  * Only the third line is news. Reporting the raw total instead would bury twenty real findings
  * under four hundred known ones.
  *
  * Cucumber's own `summary` plugin reprints the full snippet block for every undefined step, and
  * sbt prints a stack trace per failure on top of that: a run with a thousand failures buries the
  * number you actually wanted under thousands of lines. This prints one block at the end — totals,
  * failures grouped by cause, and the worst areas — which is what goes in the README.
  */
final class SailReport extends ConcurrentEventListener with ColorAware {

  // An empty constructor on purpose: Cucumber only instantiates plugins with
  // no arguments or with one of a fixed set of types, and PrintStream is not
  // among them.
  private val out: PrintStream = System.out

  private val states = mutable.Map.empty[String, Int].withDefaultValue(0)
  private val causes = mutable.Map.empty[String, Int].withDefaultValue(0)
  private val areas = mutable.Map.empty[String, Int].withDefaultValue(0)

  override def setMonochrome(monochrome: Boolean): Unit = ()

  override def setEventPublisher(publisher: EventPublisher): Unit = {
    publisher.registerHandlerFor(classOf[TestCaseFinished], (e: TestCaseFinished) => record(e))
    publisher.registerHandlerFor(classOf[TestRunFinished], (_: TestRunFinished) => printReport())
  }

  private val verdicts = mutable.Map.empty[String, Int].withDefaultValue(0)
  private val fresh = mutable.Buffer.empty[(String, String)]

  private def record(e: TestCaseFinished): Unit = synchronized {
    val state = e.getResult.getStatus.name.toLowerCase
    val tags = e.getTestCase.getTags.asScala.toSet
    val known = tags.contains("@sail-bug")
    states(state) += 1

    val sailOnly = tags.contains("@sail-only")
    val cause = causeOf(Option(e.getResult.getError))

    (state, known, sailOnly) match {
      case ("passed", true, _) => verdicts("@sail-bug passing (fixed upstream?)") += 1
      case ("passed", _, _)    => ()
      case (_, true, _)        => verdicts("@sail-bug failing (expected)") += 1
      case ("undefined", _, _) => verdicts("step not implemented here") += 1
      // Not portable is not a divergence: the plan snapshots are keyed by the
      // pytest names that produced them.
      case (_, _, _) if cause == "pending (not portable)" =>
        verdicts("not portable to a JVM client") += 1
      // A Sail extension failing against Sail is its own thing, and lumping it
      // in with untagged failures would inflate the only number that matters.
      case (_, _, true) => verdicts("@sail-only failing") += 1
      case (_, false, _) =>
        verdicts("UNTAGGED failure (new divergence?)") += 1
        causes(cause) += 1
        areas(areaOf(e.getTestCase.getUri)) += 1
        if (fresh.size < 40) fresh += ((e.getTestCase.getName, cause))
    }
  }

  /** Groups by what went wrong, not by which scenario: one defect in a step definition shows up as
    * hundreds of failures, and grouping makes that obvious instead of hiding it in the noise.
    */
  private def causeOf(error: Option[Throwable]): String = error.map(_.toString) match {
    case None => "undefined step"
    case Some(m) if m.contains("Unsupported Vector Type") =>
      "arrow: vector the JVM client cannot read"
    case Some(m) if m.contains("ClassCastException")         => "arrow: vector cast"
    case Some(m) if m.contains("expected an error matching") => "expected an error, got none"
    case Some(m) if m.contains("error does not match")       => "error message differs"
    case Some(m) if m.contains("rows differ")                => "rows differ"
    case Some(m) if m.contains("schema differs")             => "schema differs"
    case Some(m) if m.contains("columns differ")             => "columns differ"
    case Some(m) if m.contains("PendingException")           => "pending (not portable)"
    case Some(m) if m.contains("AnalysisException")          => "AnalysisException"
    case Some(m)                                             => m.split(':').head.split('.').last
  }

  private def areaOf(uri: URI): String = {
    val p = uri.getPath
    val i = p.indexOf("/features/")
    if (i < 0) "?" else p.substring(i + 10).split('/').head
  }

  /** What produced these numbers, printed with them.
    *
    * A report is meant to be diffed against one from another Sail release, and until now it
    * recorded nothing about which release that was. Two saved reports were indistinguishable, so
    * comparing them meant trusting memory or digging through git history.
    *
    * The engine version is read from the binary, not from `versions.json`: the point is what
    * actually ran, and the two can disagree — which is what the check in `SailHooks` is about.
    */
  private def provenance: String = {
    val engine = SailServer.version.map("sail " + _).getOrElse("sail (unknown)")
    val corpus = PinnedVersions.corpusTag.map("corpus " + _).getOrElse("corpus (unpinned)")
    val client = PinnedVersions.spark.map("spark-connect-client-jvm " + _).getOrElse("client ?")
    s"$engine · $corpus · $client"
  }

  private def printReport(): Unit = {
    val total = states.values.sum
    val ok = states("passed")
    out.println()
    out.println("=" * 62)
    out.println(s"  Sail compatibility report — $total scenarios")
    out.println(s"  $provenance")
    out.println("=" * 62)
    val summary = states.toSeq.sortBy(-_._2).map { case (k, v) => f"$v%d $k" }.mkString("   ")
    out.println(s"  $summary")
    if (total > 0) out.println(f"  ${ok * 100.0 / total}%.1f%% passing")
    if (verdicts.nonEmpty) {
      out.println()
      out.println("  what the failures mean:")
      verdicts.toSeq.sortBy(-_._2).foreach { case (v, n) => out.println(f"    $n%5d  $v") }
    }
    if (causes.nonEmpty) {
      out.println()
      out.println("  untagged failures by cause:")
      causes.toSeq.sortBy(-_._2).take(10).foreach { case (c, n) => out.println(f"    $n%5d  $c") }
      out.println()
      out.println("  untagged failures by area:")
      areas.toSeq.sortBy(-_._2).take(8).foreach { case (a, n) => out.println(f"    $n%5d  $a") }
      out.println()
      out.println("  a few of them:")
      fresh.take(12).foreach { case (n, c) => out.println(s"    [$c] ${n.take(72)}") }
    }
    out.println("=" * 62)
    out.println()
  }
}
