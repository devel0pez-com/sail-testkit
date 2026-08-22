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

  private val estados = mutable.Map.empty[String, Int].withDefaultValue(0)
  private val causas = mutable.Map.empty[String, Int].withDefaultValue(0)
  private val areas = mutable.Map.empty[String, Int].withDefaultValue(0)

  override def setMonochrome(monochrome: Boolean): Unit = ()

  override def setEventPublisher(publisher: EventPublisher): Unit = {
    publisher.registerHandlerFor(classOf[TestCaseFinished], (e: TestCaseFinished) => registrar(e))
    publisher.registerHandlerFor(classOf[TestRunFinished], (_: TestRunFinished) => imprimir())
  }

  private val veredictos = mutable.Map.empty[String, Int].withDefaultValue(0)
  private val nuevas = mutable.Buffer.empty[(String, String)]

  private def registrar(e: TestCaseFinished): Unit = synchronized {
    val estado = e.getResult.getStatus.name.toLowerCase
    val etiquetas = e.getTestCase.getTags.asScala.toSet
    val conocido = etiquetas.contains("@sail-bug")
    estados(estado) += 1

    val soloSail = etiquetas.contains("@sail-only")
    val causa = causaDe(Option(e.getResult.getError))

    (estado, conocido, soloSail) match {
      case ("passed", true, _) => veredictos("@sail-bug passing (fixed upstream?)") += 1
      case ("passed", _, _)    => ()
      case (_, true, _)        => veredictos("@sail-bug failing (expected)") += 1
      case ("undefined", _, _) => veredictos("step not implemented here") += 1
      // Not portable is not a divergence: the plan snapshots are keyed by the
      // pytest names that produced them.
      case (_, _, _) if causa == "pending (not portable)" =>
        veredictos("not portable to a JVM client") += 1
      // A Sail extension failing against Sail is its own thing, and lumping it
      // in with untagged failures would inflate the only number that matters.
      case (_, _, true) => veredictos("@sail-only failing") += 1
      case (_, false, _) =>
        veredictos("UNTAGGED failure (new divergence?)") += 1
        causas(causa) += 1
        areas(areaDe(e.getTestCase.getUri)) += 1
        if (nuevas.size < 40) nuevas += ((e.getTestCase.getName, causa))
    }
  }

  /** Groups by what went wrong, not by which scenario: one defect in a step definition shows up as
    * hundreds of failures, and grouping makes that obvious instead of hiding it in the noise.
    */
  private def causaDe(error: Option[Throwable]): String = error.map(_.toString) match {
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

  private def areaDe(uri: URI): String = {
    val p = uri.getPath
    val i = p.indexOf("/features/")
    if (i < 0) "?" else p.substring(i + 10).split('/').head
  }

  private def imprimir(): Unit = {
    val total = estados.values.sum
    val ok = estados("passed")
    out.println()
    out.println("=" * 62)
    out.println(s"  Sail compatibility report — $total scenarios")
    out.println("=" * 62)
    val resumen = estados.toSeq.sortBy(-_._2).map { case (k, v) => f"$v%d $k" }.mkString("   ")
    out.println(s"  $resumen")
    if (total > 0) out.println(f"  ${ok * 100.0 / total}%.1f%% passing")
    if (veredictos.nonEmpty) {
      out.println()
      out.println("  what the failures mean:")
      veredictos.toSeq.sortBy(-_._2).foreach { case (v, n) => out.println(f"    $n%5d  $v") }
    }
    if (causas.nonEmpty) {
      out.println()
      out.println("  untagged failures by cause:")
      causas.toSeq.sortBy(-_._2).take(10).foreach { case (c, n) => out.println(f"    $n%5d  $c") }
      out.println()
      out.println("  untagged failures by area:")
      areas.toSeq.sortBy(-_._2).take(8).foreach { case (a, n) => out.println(f"    $n%5d  $a") }
      out.println()
      out.println("  a few of them:")
      nuevas.take(12).foreach { case (n, c) => out.println(s"    [$c] ${n.take(72)}") }
    }
    out.println("=" * 62)
    out.println()
  }
}
