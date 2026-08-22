package com.devel0pez.sail.testkit

import java.io.{BufferedWriter, FileWriter}
import java.nio.file.{Files, Paths}

import scala.util.control.NonFatal

/** Appends `sql <TAB> schema` for every query the corpus runs.
  *
  * Enabled with `-Dsail.schemaDump=<file>`. Run the corpus once against Sail
  * and once against Spark, then diff the two files: every line where the same
  * SQL yields a different schema is a type divergence, and no expected value
  * had to be written for it.
  */
object SchemaDump {

  val file: Option[String] = sys.props.get("sail.schemaDump").filter(_.nonEmpty)

  private lazy val writer: Option[BufferedWriter] = file.map { path =>
    val p = Paths.get(path)
    Option(p.getParent).foreach(Files.createDirectories(_))
    val w = new BufferedWriter(new FileWriter(path, false))
    // Flushed and closed on exit rather than per line: the corpus runs
    // thousands of queries and a flush each time doubles the wall clock.
    sys.addShutdownHook {
      try { w.flush(); w.close() }
      catch { case NonFatal(_) => () }
    }
    w
  }

  /** One line per query. Newlines are folded so a line is always one record. */
  def write(path: String, sql: String, schema: String): Unit = writer.foreach { w =>
    try w.synchronized {
      w.write(sql.replaceAll("\\s+", " ").trim)
      w.write('\t')
      w.write(schema)
      w.newLine()
    } catch { case NonFatal(_) => () }
  }
}
