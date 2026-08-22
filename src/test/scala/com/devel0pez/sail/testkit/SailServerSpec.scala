package com.devel0pez.sail.testkit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Tests for the launcher itself: it starts, it stops, it tells you why not. */
final class SailServerSpec extends AnyFunSuite with Matchers {

  test("starts a server and hands out an sc:// url") {
    SailServer.withServer { server =>
      server.url should startWith("sc://127.0.0.1:")
      server.owned shouldBe true
    }
  }

  test("picks a free port, so two servers can run at once") {
    SailServer.withServer { a =>
      SailServer.withServer { b =>
        a.url should not be b.url
      }
    }
  }

  test("closing twice is harmless") {
    val server = SailServer.start()
    server.close()
    noException should be thrownBy server.close()
  }

  test("reuses an already running server instead of starting another") {
    SailServer.withServer { running =>
      // What CI does: start one server, point every suite at it.
      val reused = withEnv(SailServer.RemoteEnvVar -> running.url)(SailServer.start())
      try {
        reused.url shouldBe running.url
        reused.owned shouldBe false // not ours, so we must not kill it
      } finally reused.close()
      // The original is still up: closing a borrowed handle stopped nothing.
      running.url should startWith("sc://")
    }
  }

  test("says what to install when the binary is missing") {
    val error = intercept[IllegalStateException] {
      withEnv("SAIL_BIN" -> "sail-that-does-not-exist")(SailServer.start())
    }

    error.getMessage should include("pip install pysail")
  }

  /** Runs `body` with an environment variable set, then puts it back.
    *
    * The JVM has no supported way to set one, so this reaches into the unmodifiable map behind
    * `System.getenv`. Fine for a test; the alternative is spawning a child JVM for each case.
    */
  private def withEnv[A](entry: (String, String))(body: => A): A = {
    val (key, value) = entry
    val env = System.getenv()
    val field = env.getClass.getDeclaredField("m")
    field.setAccessible(true)
    val writable = field.get(env).asInstanceOf[java.util.Map[String, String]]
    val previous = Option(writable.get(key))
    writable.put(key, value)
    try body
    finally previous.fold(writable.remove(key): Unit)(writable.put(key, _): Unit)
  }
}
