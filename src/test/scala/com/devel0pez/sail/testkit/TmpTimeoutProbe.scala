package com.devel0pez.sail.testkit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** TEMPORAL - borrar */
final class TmpTimeoutProbe extends AnyFunSuite with Matchers {
  test("a hanging binary is bounded by VersionTimeout, not by readAllBytes") {
    val env = System.getenv()
    val f = env.getClass.getDeclaredField("m"); f.setAccessible(true)
    val w = f.get(env).asInstanceOf[java.util.Map[String, String]]
    w.put("SAIL_BIN", sys.props("hangingSail"))
    val started = System.nanoTime()
    val result = SailServer.version
    val elapsed = (System.nanoTime() - started) / 1000000000.0
    w.remove("SAIL_BIN")
    println(
      f"### resultado=$result  tardó=$elapsed%.1f s (timeout declarado: ${SailServer.StartTimeout})"
    )
    result shouldBe None
    elapsed should be < 20.0
  }
}
