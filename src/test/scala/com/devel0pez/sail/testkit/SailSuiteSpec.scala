package com.devel0pez.sail.testkit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The trait as a user would use it: mix it in and get a working `spark`. */
final class SailSuiteSpec extends AnyFunSuite with Matchers with SailSuite {

  test("the session is connected to Sail") {
    spark.version should not be empty
    sailServer.url should startWith("sc://")
  }

  test("queries actually run") {
    val total = spark.range(1, 5).selectExpr("sum(id) as total").first().getLong(0)

    total shouldBe 10L
  }

  test("dataframes carry the schema back") {
    val df = spark.range(1, 3).selectExpr("id", "id * 2 as double_id")

    df.columns.toSeq shouldBe Seq("id", "double_id")
    df.count() shouldBe 2L
  }
}
