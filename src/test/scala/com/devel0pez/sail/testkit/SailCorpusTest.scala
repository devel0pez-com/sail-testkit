package com.devel0pez.sail.testkit

import io.cucumber.junit.{Cucumber, CucumberOptions}
import org.junit.runner.RunWith

/** Runs Sail's entire feature corpus through a JVM Spark Connect client.
  *
  * Nothing is filtered here, because what a tag means depends on the server:
  *
  *   - `@sail-only` Sail extensions. They run and pass against Sail; against Spark they must be
  *     skipped, since Spark has no such thing.
  *   - `@sail-bug` known Sail bugs. Expected to fail against Sail, expected to **pass** against
  *     Spark.
  *   - `@spark-X.Y` needs at least that Spark version.
  *
  * So running against Spark means adding `-Dcucumber.filter.tags='not
  * @sail-only'`;
  *   against Sail, no filter at all. Baking one filter into the annotation, as this did before,
  *   dropped 103 scenarios that Sail handles perfectly well and reported them as though they did
  *   not exist.
  *
  * Scoped to `function/`, which is 4.972 of the corpus's 5.522 scenarios and the only part that is
  * pure SQL in, rows or schema out. Those are Examples rows, which is what Cucumber actually runs —
  * the declared counts are 2.960 and 3.494, and mixing the two bases is how the numbers in here
  * drifted apart before. The rest — delta, iceberg, catalog, dml — drives real tables, temporary
  * directories and external catalogs (Glue, Unity) through steps like `Given variable location for
  * temporary directory ...`. Running those without that harness reports infrastructure we never set
  * up as though Sail disagreed with Spark, which is worse than not reporting them at all.
  *
  * One defect in a step definition still shows up as hundreds of failures, so when a number moves,
  * check a single slice first (`tfs spark/function/features/math`) before reading anything into it.
  *
  * It does not gate the build either — see the `corpus` task in build.sbt. A red suite that nobody
  * can turn green teaches nothing; a report you can diff between versions does.
  */
// JUnit 4's runner, deprecated upstream in favour of the JUnit Platform
// engine. Kept on purpose: it needs no sbt plugin, while the JUnit 5 route
// wants `sbt-jupiter-interface` and more wiring. Worth revisiting, not worth
// blocking on.
@annotation.nowarn("cat=deprecation")
@RunWith(classOf[Cucumber])
@CucumberOptions(
  features = Array("sail-features/python/pysail/tests/spark/function"),
  glue = Array("com.devel0pez.sail.testkit"),
  // No `summary`: it reprints the whole snippet block for every undefined step
  // and duplicates what sbt already prints. `SailReport` writes one readable
  // block instead.
  plugin = Array(
    "html:target/corpus-report.html",
    "json:target/corpus-report.json",
    "com.devel0pez.sail.testkit.SailReport"
  ),
  snippets = CucumberOptions.SnippetType.CAMELCASE
)
class SailCorpusTest
