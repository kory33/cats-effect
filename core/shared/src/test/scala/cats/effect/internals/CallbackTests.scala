/*
 * Copyright (c) 2017-2022 The Typelevel Cats-effect Project Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect
package internals

import cats.effect.IO
import cats.effect.internals.Callback.{Extensions, T => Callback}

class CallbackTests extends CatsEffectSuite {
  test("Callback.report(Right(_)) is a no-op") {
    val output = catchSystemErr {
      // No-op
      Callback.report[Int](Right(1))
    }
    assert(output.isEmpty)
  }

  test("Callback.report(Left(e)) reports to System.err") {
    val dummy = new RuntimeException("dummy")
    val output = catchSystemErr {
      Callback.report(Left(dummy))
    }
    assert(output.contains("dummy"))
  }

  test("Callback.async references should be stack safe") {
    var result = Option.empty[Either[Throwable, Int]]
    val r: Callback[Int] = r => { result = Some(r) }
    val count = if (IOPlatform.isJVM) 100000 else 10000

    val f = (0 until count).foldLeft(r)((acc, _) => Callback.async(acc.andThen(_ => ())))
    f(Right(1))
    assertEquals(result, Some(Right(1)))
  }

  test("Callback.async pops Connection if provided") {
    val conn = IOConnection()
    val ref = IO(())
    conn.push(ref)

    var result = Option.empty[Either[Throwable, Int]]
    val cb = Callback.async(conn, (r: Either[Throwable, Int]) => result = Some(r))
    assertEquals(result, None)

    cb(Right(100))
    assertEquals(result, Some(Right(100)))
    assertNotEquals(conn.pop(), ref)
  }

  test("Callback.asyncIdempotent should be stack safe") {
    var result = Option.empty[Either[Throwable, Int]]
    val r: Callback[Int] = r => { result = Some(r) }
    val count = if (IOPlatform.isJVM) 100000 else 10000

    val f = (0 until count).foldLeft(r)((acc, _) => Callback.asyncIdempotent(null, acc.andThen(_ => ())))
    f(Right(1))
    assertEquals(result, Some(Right(1)))
  }

  test("Callback.asyncIdempotent pops Connection if provided") {
    val conn = IOConnection()
    val ref = IO(())
    conn.push(ref)

    var result = Option.empty[Either[Throwable, Int]]
    val cb = Callback.asyncIdempotent(conn, (r: Either[Throwable, Int]) => result = Some(r))
    assertEquals(result, None)

    cb(Right(100))
    assertEquals(result, Some(Right(100)))
    assertNotEquals(conn.pop(), ref)
  }

  test("Callback.asyncIdempotent can only be called once") {
    var effect = 0
    val ref: Callback[Int] = { case Right(n) => effect += n; case Left(e) => throw e }
    val safe = Callback.asyncIdempotent(null, ref)

    safe(Right(100))
    safe(Right(100))

    assertEquals(effect, 100)
  }

  test("Callback.asyncIdempotent reports error") {
    var input = Option.empty[Either[Throwable, Int]]
    val ref: Callback[Int] = r => { input = Some(r) }
    val safe = Callback.asyncIdempotent(null, ref)

    val dummy1 = new RuntimeException("dummy1")
    val dummy2 = new RuntimeException("dummy2")

    safe(Left(dummy1))
    val err = catchSystemErr(safe(Left(dummy2)))

    assertEquals(input, Some(Left(dummy1)))
    assert(err.contains("dummy2"))
  }

  test("Callback.Extensions.async(cb)") {
    var result = Option.empty[Either[Throwable, Int]]
    val cb = (r: Either[Throwable, Int]) => { result = Some(r) }

    cb.async(Right(100))
    assertEquals(result, Some(Right(100)))
  }
}
