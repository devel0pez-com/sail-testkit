package com.devel0pez.sail.testkit

import org.apache.spark.sql.SparkSession

/** One Sail server and one session for the whole feature run.
  *
  * Starting a server per scenario would dwarf the time spent running the queries, and there are
  * hundreds of them.
  *
  * Shutdown is driven by Cucumber's `AfterAll`, not a JVM shutdown hook: the Connect client keeps
  * non-daemon gRPC threads alive, so the JVM will not exit until the session is stopped — and a
  * hook that only runs on exit would therefore never run. The result is a test run that finishes
  * and then hangs. The hook stays as a backstop for an aborted run.
  */
object SailWorld {

  private var startedServer: Option[SailServer] = None
  private var startedSpark: Option[SparkSession] = None

  lazy val server: SailServer = synchronized {
    val s = SailServer.start()
    startedServer = Some(s)
    sys.addShutdownHook(shutdown())
    s
  }

  lazy val spark: SparkSession = synchronized {
    val session = SparkSession.builder().remote(server.url).getOrCreate()
    configure(session)
    startedSpark = Some(session)
    session
  }

  /** The same session configuration Sail's own test suite applies.
    *
    * Without these the corpus is being run under different rules than the one that produced the
    * expected values, and the differences look like findings when they are just a different
    * session. ANSI mode in particular decides whether an operation raises or returns NULL, which is
    * exactly what the `try_*` scenarios are about.
    */
  private def configure(session: SparkSession): Unit = {
    session.conf.set("spark.sql.session.timeZone", "UTC")
    session.conf.set("spark.sql.ansi.enabled", "true")
  }

  /** Idempotent: `AfterAll` and the backstop hook may both call it. */
  def shutdown(): Unit = synchronized {
    startedSpark.foreach(s =>
      try s.stop()
      catch { case _: Throwable => () }
    )
    startedSpark = None
    startedServer.foreach(s =>
      try s.close()
      catch { case _: Throwable => () }
    )
    startedServer = None
  }
}
