package com.devel0pez.sail.testkit

import org.apache.spark.sql.SparkSession
import org.scalatest.{BeforeAndAfterAll, Suite}

/** Mixin that gives a suite a `SparkSession` backed by Sail.
  *
  * The server is started before the suite and stopped after it, so `sbt test` works with nothing
  * running beforehand:
  *
  * {{{
  * class MyEtlSpec extends AnyFunSuite with SailSuite {
  *   test("aggregates by key") {
  *     val df = spark.range(10)
  *     assert(df.count() == 10)
  *   }
  * }
  * }}}
  *
  * One session per suite, not per test: starting one costs enough that it is worth sharing.
  */
trait SailSuite extends BeforeAndAfterAll { this: Suite =>

  @volatile private var session: SparkSession = _
  @volatile private var server: SailServer = _

  /** The session under test. Valid from `beforeAll` to `afterAll`. */
  protected def spark: SparkSession = {
    if (session == null) {
      throw new IllegalStateException("No session yet: it is created in beforeAll")
    }
    session
  }

  /** The server backing `spark`, in case a test needs its url.
    *
    * Fails the same way `spark` does rather than handing back a null: a null here surfaces as an
    * NPE somewhere else entirely, and the reader has to work back to "you asked before beforeAll
    * ran".
    */
  protected def sailServer: SailServer = {
    if (server == null) {
      throw new IllegalStateException("No server yet: it is started in beforeAll")
    }
    server
  }

  /** Hook for session configuration, called once the session exists.
    *
    * Empty on purpose. Whether ANSI mode is on, or what the session time zone is, changes what a
    * query means — those are the project's decisions, not the kit's, and a test kit that quietly
    * set them would be answering questions nobody asked it.
    *
    * It exists so configuring a session does not force you to reimplement starting one:
    *
    * {{{
    * override protected def configureSession(session: SparkSession): Unit = {
    *   session.conf.set("spark.sql.ansi.enabled", "true")
    * }
    * }}}
    */
  protected def configureSession(session: SparkSession): Unit = ()

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    server = SailServer.start()
    // No `appName`: Connect ignores it and warns on every session, which is
    // noise a test kit has no business printing.
    session = SparkSession.builder().remote(server.url).getOrCreate()
    configureSession(session)
  }

  override protected def afterAll(): Unit =
    try {
      if (session != null) session.stop()
      if (server != null) server.close()
    } finally super.afterAll()
}
