package com.devel0pez.sail.testkit

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

import io.cucumber.datatable.DataTable
import io.cucumber.scala.{EN, ScalaDsl}
import org.apache.spark.sql.DataFrame

/** Step definitions for Sail's own `.feature` files.
  *
  * The feature files are copied verbatim from Sail and are language-agnostic: SQL in, schema or
  * rows out. Only the step definitions are rewritten, which is the whole point — the same corpus,
  * driven by a JVM client instead of the Python one. A divergence here is a real finding, not a
  * transcription slip.
  */
final class QuerySteps extends ScalaDsl with EN {

  private var result: Try[DataFrame] = Failure(new IllegalStateException("no query yet"))
  private var finalStatements: Seq[String] = Nil
  private var touchedConfig: Seq[(String, Option[String])] = Nil

  private def spark = SailWorld.spark

  private def df: DataFrame = result match {
    case Success(d) => d
    case Failure(e) => throw new AssertionError(s"the query failed: ${e.getMessage}", e)
  }

  /** Renders a value the way Spark does, which is not the way Java does.
    *
    * Typed intervals arrive from the Connect client as `java.time.Period` and `java.time.Duration`,
    * whose `toString` is ISO-8601 (`P3Y`, `PT26H3M4S`). Spark prints `INTERVAL '3-0' YEAR TO
    * MONTH`, and that is what the expected tables hold. Getting this wrong turns a perfectly good
    * result into a fake failure.
    */
  private def render(value: Any): String = value match {
    case null                  => "NULL"
    case s: String             => s
    case p: java.time.Period   => renderPeriod(p)
    case d: java.time.Duration => renderDuration(d)
    // Binary comes back as a byte array; Spark prints it as hex, not as the
    // JVM's `[B@1a2b3c`.
    case b: Array[Byte] => b.map(x => f"$x%02X").mkString("[", "", "]")
    case a: Array[_]    => a.map(render).mkString("[", ", ", "]")
    // `scala.collection.Seq`, not the `Seq` alias: that alias is
    // `immutable.Seq`, and the Connect client hands back `mutable.ArraySeq`,
    // so the case silently fell through to `toString` and printed
    // `ArraySeq(1, 2)` where Spark prints `[1, 2]`.
    case s: scala.collection.Seq[_] => s.map(render).mkString("[", ", ", "]")
    case m: scala.collection.Map[_, _] =>
      m.map { case (k, v) => s"${render(k)} -> ${render(v)}" }.mkString("{", ", ", "}")
    // Spark prints a struct with braces; `Row.toString` uses brackets.
    case r: org.apache.spark.sql.Row =>
      (0 until r.length).map(i => render(r.get(i))).mkString("{", ", ", "}")
    // `Timestamp.toString` appends a `.0` that Spark does not print.
    case ts: java.sql.Timestamp => ts.toString.replaceAll("\\.0$", "")
    case other                  => String.valueOf(other)
  }

  private def renderPeriod(p: java.time.Period): String = {
    val months = p.toTotalMonths
    val sign = if (months < 0) "-" else ""
    val abs = math.abs(months)
    s"INTERVAL '$sign${abs / 12}-${abs % 12}' YEAR TO MONTH"
  }

  private def renderDuration(d: java.time.Duration): String = {
    val micros = d.toNanos / 1000
    val sign = if (micros < 0) "-" else ""
    val abs = math.abs(micros)
    val (s, us) = (abs / 1000000, abs % 1000000)
    val (m, secs) = (s / 60, s % 60)
    val (h, mins) = (m / 60, m % 60)
    val (days, hrs) = (h / 24, h % 24)
    f"INTERVAL '$sign$days%d $hrs%02d:$mins%02d:$secs%02d.$us%06d' DAY TO SECOND"
  }

  /** The table exactly as the **server** renders it with `show()`.
    *
    * This is how Sail's own suite reads a result, and copying it matters more than it looks.
    * Collecting rows and formatting them here means every value crosses the Arrow deserializer and
    * then gets printed by our own code — so a type the JVM client cannot decode fails a scenario
    * that was never about decoding, and every formatting difference (`ArraySeq(1, 2)` against `[1,
    * 2]`, a trailing `.0` on a timestamp) counts as a divergence that is ours, not Sail's. Asking
    * the server for the text sidesteps both.
    */
  private def shownTable(d: DataFrame): Seq[Seq[String]] = {
    val buffer = new java.io.ByteArrayOutputStream()
    Console.withOut(new java.io.PrintStream(buffer, true, "UTF-8")) {
      d.show(Int.MaxValue, truncate = false)
    }
    parseShow(buffer.toString("UTF-8"))
  }

  /** Splits `show()` output into cells using the `+` positions of the border, the same way Sail's
    * `parse_show_string` does.
    */
  private def parseShow(text: String): Seq[Seq[String]] = {
    val lines = text.linesIterator.filter(_.trim.nonEmpty).toIndexedSeq
    if (lines.length < 3) return Seq.empty
    val border = lines.head
    val positions = border.zipWithIndex.collect { case ('+', i) => i }
    def cells(line: String): Seq[String] =
      positions
        .sliding(2)
        .collect { case Seq(a, b) =>
          if (a + 1 <= line.length) line.slice(a + 1, math.min(b, line.length)).trim else ""
        }
        .toSeq
    // border, header, border, datos..., border
    (Seq(cells(lines(1))) ++ lines.slice(3, lines.length - 1).map(cells)).toSeq
  }

  /** For `query result collected`, which does go through collect(). */
  private def collectedValue(value: Any): String = value match {
    case null       => "NULL"
    case b: Boolean => b.toString.toLowerCase
    case other      => String.valueOf(other)
  }

  private def expected(table: DataTable): (Seq[String], Seq[Seq[String]]) = {
    val all = table
      .asLists(classOf[String])
      .asScala
      .toSeq
      // An empty Gherkin cell is an empty string, and Cucumber hands it over as
      // null. Turning that into "NULL" made every empty-string expectation
      // look like a NULL that the engine failed to produce.
      .map(_.asScala.toSeq.map(c => if (c == null) "" else c.trim))
    (all.head, all.tail)
  }

  private def compare(table: DataTable, ordered: Boolean): Unit = {
    val (header, rows) = expected(table)
    val shown = shownTable(df)
    assert(shown.nonEmpty, "show() produced no table")
    val (actualHeader, actualRows) = (shown.head, shown.tail)
    assert(
      header == actualHeader,
      s"columns differ\n  expected: $header\n  actual:   $actualHeader"
    )
    val (e, a) =
      if (ordered) (rows, actualRows)
      else (rows.sortBy(_.toString), actualRows.sortBy(_.toString))
    assert(e == a, s"rows differ\n  expected: $e\n  actual:   $a")
  }

  private def compareCollected(table: DataTable, ordered: Boolean): Unit = {
    val (header, rows) = expected(table)
    val actual =
      df.collect().toSeq.map(f => (0 until f.length).map(i => collectedValue(f.get(i))))
    assert(
      header == df.columns.toSeq,
      s"columns differ\n  expected: $header\n  actual:   ${df.columns.toSeq}"
    )
    val (e, a) =
      if (ordered) (rows, actual) else (rows.sortBy(_.toString), actual.sortBy(_.toString))
    assert(e == a, s"rows differ\n  expected: $e\n  actual:   $a")
  }

  /** The named DataFrames from upstream's `dataframe_for`, built with the DataFrame API rather than
    * SQL because that is the only way to express what these scenarios are about — how a null
    * literal keeps its type.
    */
  When("""^dataframe for (.+)$""") { (kase: String) =>
    import org.apache.spark.sql.functions.{col, lit, to_timestamp, try_to_timestamp}
    val r = spark.range(1)
    val d = kase match {
      case "null literal" => r.select(lit(null).as("result"))
      case "null literal alias projection" =>
        r.select(lit(null).as("value")).select(col("value").as("result"))
      case "null literal with column"  => r.withColumn("result", lit(null)).select("result")
      case "to_timestamp null literal" => r.select(to_timestamp(lit(null)).as("result"))
      case "to_timestamp null literal with format" =>
        r.select(to_timestamp(lit(null), "yyyy-MM-dd").as("result"))
      case "try_to_timestamp null literal with format" =>
        r.select(try_to_timestamp(lit(null), lit("yyyy-MM-dd")).as("result"))
      case "try_to_timestamp value with null format" =>
        r.select(try_to_timestamp(lit("2024-01-02"), lit(null)).as("result"))
      case "to_timestamp_ltz null literal with format" =>
        r.selectExpr("to_timestamp_ltz(NULL, 'yyyy-MM-dd') AS result")
      case "to_timestamp_ltz value with null format" =>
        r.selectExpr("to_timestamp_ltz('2024-01-02', NULL) AS result")
      case "to_timestamp_ntz null literal with format" =>
        r.selectExpr("to_timestamp_ntz(NULL, 'yyyy-MM-dd') AS result")
      case "to_timestamp_ntz value with null format" =>
        r.selectExpr("to_timestamp_ntz('2024-01-02', NULL) AS result")
      case other => throw new AssertionError(s"unknown DataFrame case: $other")
    }
    result = Success(d)
  }

  Then("""dataframe schema""") { (expected: String) =>
    val actual = df.schema.treeString.trim
    assert(
      actual == expected.trim,
      s"schema differs\n  expected:\n${expected.trim}\n  actual:\n$actual"
    )
  }

  /** Variables that templates can reference, upstream's `PathWrapper`.
    *
    * Four renderings of the same path, because SQL wants it quoted, `LOCATION` wants a URI, and
    * messages want it bare. The directory is deliberately not created: the statement under test is
    * what creates it.
    */
  private final class PathVar(val path: java.nio.file.Path) {
    def string: String = path.toString
    def sql: String = "'" + path.toString.replace("'", "''") + "'"
    def uri: String = "'" + path.toAbsolutePath.toUri.toString + "'"
    def fileUri: String = path.toAbsolutePath.toUri.toString
    def prop(name: String): String = name match {
      case "string"   => string
      case "sql"      => sql
      case "uri"      => uri
      case "file_uri" => fileUri
      case other      => throw new AssertionError(s"unknown path property: $other")
    }
  }

  private var variables: Map[String, PathVar] = Map.empty

  /** One temporary root per scenario, the equivalent of pytest's `tmp_path`. */
  private lazy val temporaryRoot: java.nio.file.Path =
    java.nio.file.Files.createTempDirectory("sail-testkit-")

  Given("""^variable (\S+) for temporary directory (\S+)$""") { (name: String, dir: String) =>
    variables = variables + (name -> new PathVar(temporaryRoot.resolve(dir)))
  }

  /** Renders the `{{ name.property }}` subset of Jinja that the corpus uses.
    *
    * Not a template engine: the corpus only ever asks for a path rendered one of four ways, and
    * pulling in a real Jinja port for that would be a dependency in a test kit that ships none.
    */
  private def render(text: String): String =
    """\{\{\s*(\w+)\.(\w+)\s*\}\}""".r.replaceAllIn(
      text,
      m => {
        val v = variables.getOrElse(
          m.group(1),
          throw new AssertionError(s"template references unknown variable ${m.group(1)}")
        )
        java.util.regex.Matcher.quoteReplacement(v.prop(m.group(2)))
      }
    )

  Given("""statement template""") { (sql: String) =>
    spark.sql(render(repairEscapes(sql))).collect()
  }

  When("""query template""") { (sql: String) =>
    result = Try(spark.sql(render(repairEscapes(sql))))
  }

  Given("""final statement template""") { (sql: String) =>
    finalStatements = finalStatements :+ render(repairEscapes(sql))
  }

  Given("""statement""") { (sql: String) =>
    spark.sql(repairEscapes(sql)).collect()
  }

  /** Registered up front, run when the scenario ends: the corpus uses it for `DROP TABLE IF
    * EXISTS`, so a scenario cannot poison the next one.
    */
  Given("""final statement""") { (sql: String) =>
    finalStatements = finalStatements :+ sql
  }

  /** Where the running scenario lives, so its source line can be read back. */
  private var source: Option[(java.net.URI, Int)] = None

  Before { (s: io.cucumber.scala.Scenario) =>
    source = Some((s.getUri, s.getLine))
  }

  /** Puts back the backslashes Cucumber ate on the way in.
    *
    * Gherkin says a table cell escapes `\\`, `\|` and `\n`, so Cucumber hands over `(\d+)` where
    * the file says `(\\d+)`. Spark then treats the remaining backslash as its own escape and the
    * regex arrives as `(d+)` — which is why `regexp_extract('hello 123 world', '(\\d+)', 0)`
    * returned `d`, the letter in "world".
    *
    * pytest-bdd does not unescape, so upstream never sees this. Docstrings are not unescaped by
    * anyone, which is why the same patterns work there. So the repair has to be surgical: read the
    * Examples row from the file and put the original text back only where a cell was substituted.
    * Doubling every backslash in the SQL would break the 164 single ones and the 28 pairs that live
    * in docstrings and work fine today.
    */
  private def repairEscapes(sql: String): String = source match {
    case None => sql
    case Some((uri, line)) =>
      val repaired =
        try {
          val file = java.nio.file.Paths.get(uri)
          val lines = java.nio.file.Files.readAllLines(file)
          if (line < 1 || line > lines.size) sql
          else {
            val row = lines.get(line - 1).trim
            if (!row.startsWith("|") || !row.contains("\\")) sql
            else
              row.split('|').map(_.trim).filter(_.contains("\\")).foldLeft(sql) { (acc, raw) =>
                val eaten = raw.replace("\\\\", "\\")
                if (eaten.nonEmpty && eaten != raw) acc.replace(eaten, raw) else acc
              }
          }
        } catch { case NonFatal(_) => sql }
      repaired
  }

  After { (_: io.cucumber.scala.Scenario) =>
    finalStatements.foreach(sql =>
      try spark.sql(sql).collect()
      catch { case NonFatal(_) => () }
    ) // cleanup must not fail the scenario
    finalStatements = Nil
    // Restore configs in reverse, so nested overrides unwind correctly.
    touchedConfig.reverse.foreach { case (key, previous) =>
      try
        previous match {
          case Some(v) => spark.conf.set(key, v)
          case None    => spark.conf.unset(key)
        }
      catch { case NonFatal(_) => () }
    }
    touchedConfig = Nil
  }

  Given("""^statement with error (.*)$""") { (pattern: String, sql: String) =>
    Try(spark.sql(sql).collect()) match {
      case Success(_) =>
        throw new AssertionError(s"expected the statement to fail matching /$pattern/, it did not")
      case Failure(e) =>
        val message = Option(e.getMessage).getOrElse("")
        assert(
          pattern.r.findFirstIn(message).isDefined,
          s"statement error does not match /$pattern/:\n$message"
        )
    }
  }

  /** Not portable: the snapshots are keyed by the pytest test name that produced them, which
    * Cucumber cannot reproduce. Reported as pending rather than silently passed — a green tick we
    * did not earn would be worse than an honest gap in the report.
    */
  Then("""query plan matches snapshot""") { () =>
    throw new io.cucumber.scala.PendingException()
  }

  Then("""^query result row where "(.+)" is "(.+)" has "(.+)" equal to "(.*)"$""") {
    (keyColumn: String, keyValue: String, column: String, expected: String) =>
      assert(
        cell(keyColumn, keyValue, column) == expected,
        s"$column in the row where $keyColumn=$keyValue " +
          s"is ${cell(keyColumn, keyValue, column)}, expected $expected"
      )
  }

  Then("""^query result row where "(.+)" is "(.+)" has "(.+)" containing "(.*)"$""") {
    (keyColumn: String, keyValue: String, column: String, expected: String) =>
      val real = cell(keyColumn, keyValue, column)
      assert(real.contains(expected), s"$column is $real, expected it to contain $expected")
  }

  /** El valor de `column` en la row donde `keyColumn` vale `keyValue`. */
  private def cell(keyColumn: String, keyValue: String, column: String): String = {
    val rows = df.collect().toSeq
    val i = df.columns.indexOf(keyColumn)
    val j = df.columns.indexOf(column)
    assert(i >= 0, s"no column named $keyColumn in ${df.columns.toSeq}")
    assert(j >= 0, s"no column named $column in ${df.columns.toSeq}")
    rows.find(f => render(f.get(i)) == keyValue) match {
      case Some(f) => render(f.get(j))
      case None    => throw new AssertionError(s"no row where $keyColumn=$keyValue")
    }
  }

  /** Sets a config **for this scenario only**.
    *
    * The previous value is put back afterwards. Without that, a scenario that sets
    * `spark.sql.session.timeZone = America/New_York` leaves it set for every scenario that runs
    * after it, and the results come out shifted by hours — which reads like a timezone bug in the
    * engine instead of a scenario that forgot to clean up after itself.
    */
  Given("""^config (\S+) = (.*)$""") { (key: String, value: String) =>
    val previous = Try(spark.conf.get(key)).toOption
    touchedConfig = touchedConfig :+ ((key, previous))
    spark.conf.set(key, value.trim)
  }

  // Analysis only, not execution. Forcing a `collect()` here would turn every
  // schema-only scenario into an execution one: a query whose schema is fine
  // but whose rows blow up (a decimal overflow, say) would be reported as a
  // schema failure. Each step forces exactly what it needs.
  When("""query""") { (sql: String) =>
    result = Try(spark.sql(repairEscapes(sql)))
  }

  Then("""query schema""") { (expected: String) =>
    val actual = df.schema.treeString.trim
    assert(
      actual == expected.trim,
      s"schema differs\n  expected:\n${expected.trim}\n  actual:\n$actual"
    )
  }

  Then("""query result""") { (table: DataTable) => compare(table, ordered = false) }

  Then("""query result ordered""") { (table: DataTable) => compare(table, ordered = true) }

  // A separate step upstream, and separate here: this one does collect, and
  // formats NULL and booleans the way their `_format_collected_value` does.
  Then("""query result collected""") { (table: DataTable) =>
    compareCollected(table, ordered = false)
  }

  Then("""query result collected ordered""") { (table: DataTable) =>
    compareCollected(table, ordered = true)
  }

  Then("""^query error (.*)$""") { (pattern: String) =>
    // An error can surface at analysis or at execution: force both.
    result.flatMap(d => Try(d.collect())) match {
      case Success(_) =>
        throw new AssertionError(s"expected an error matching /$pattern/, got none")
      case Failure(e) =>
        val message = Option(e.getMessage).getOrElse("")
        assert(
          pattern.r.findFirstIn(message).isDefined || message.matches(pattern),
          s"error does not match /$pattern/:\n$message"
        )
    }
  }
}
