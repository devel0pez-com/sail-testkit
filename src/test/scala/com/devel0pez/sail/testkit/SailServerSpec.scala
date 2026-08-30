package com.devel0pez.sail.testkit

import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Tests for the launcher itself: it starts, it stops, it tells you why not. */
final class SailServerSpec extends AnyFunSuite with Matchers with OptionValues {

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

  test("quotes what the server said when it dies on startup") {
    // Sail explains itself on stderr. Before this was captured the failure read
    // "exited while starting up (code 1)" and the reason died with the pipe.
    val fake = stubBinary(
      """|#!/bin/sh
         |echo "error: failed to bind: Address already in use (os error 48)" >&2
         |exit 1
         |""".stripMargin
    )

    val error = intercept[IllegalStateException] {
      withEnv("SAIL_BIN" -> fake)(SailServer.start())
    }

    error.getMessage should include("Address already in use")
  }

  test("a server that starts keeps its output available") {
    SailServer.withServer { server =>
      // Not asserting on the text: what Sail logs at startup is its business
      // and would make this a test of Sail's log format. That it arrives at all
      // is the contract, and it is what proves the pipe is being drained.
      server.recentOutput should not be empty
    }
  }

  /** Writes an executable stand-in for the Sail binary and returns its path. */
  private def stubBinary(script: String): String = {
    val path = java.nio.file.Files.createTempFile("fake-sail", "")
    java.nio.file.Files.write(path, script.getBytes("UTF-8"))
    path.toFile.setExecutable(true)
    path.toFile.deleteOnExit()
    path.toString
  }

  test("reports the version the binary answers with") {
    // Not compared against versions.json here: this asserts the accessor works, and
    // PinnedVersionsSpec is where the pairing itself is checked.
    SailServer.version.value should fullyMatch regex """\d+\.\d+\.\d+.*"""
  }

  test("returns no version rather than failing when the binary is missing") {
    // A diagnostic must never be the reason a suite goes red; `start()` is what reports a
    // missing binary properly, and the test below covers that.
    withEnv("SAIL_BIN" -> "sail-that-does-not-exist")(SailServer.version) shouldBe None
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
