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

import cats.Eq
import cats.effect.concurrent.Ref
import cats.effect.implicits._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class ConcurrentTests extends CatsEffectSuite {
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  private val smallDelay: IO[Unit] = timer.sleep(20.millis)

  private def awaitEqual[A: Eq](t: IO[A], success: A): IO[Unit] =
    t.flatMap(a => if (Eq[A].eqv(a, success)) IO.unit else smallDelay *> awaitEqual(t, success))

  test("F.parTraverseN(n)(collection)(f)") {
    val finalValue = 100
    val r = Ref.unsafe[IO, Int](0)
    val list = List.range(0, finalValue)
    val modifies = implicitly[Concurrent[IO]].parTraverseN(3)(list)(_ => IO.shift *> r.update(_ + 1))
    (IO.shift *> modifies.start *> awaitEqual(r.get, finalValue)).as(assert(true))
  }

  test("collection.parTraverseN(n)(f)") {
    val finalValue = 100
    val r = Ref.unsafe[IO, Int](0)
    val list = List.range(0, finalValue)
    val modifies = list.parTraverseN(3)(_ => IO.shift *> r.update(_ + 1))
    (IO.shift *> modifies.start *> awaitEqual(r.get, finalValue)).as(assert(true))
  }

  test("F.parSequenceN(n)(collection)") {
    val finalValue = 100
    val r = Ref.unsafe[IO, Int](0)
    val list = List.fill(finalValue)(IO.shift *> r.update(_ + 1))
    val modifies = implicitly[Concurrent[IO]].parSequenceN(3)(list)
    (IO.shift *> modifies.start *> awaitEqual(r.get, finalValue)).as(assert(true))
  }

  test("collection.parSequenceN(n)") {
    val finalValue = 100
    val r = Ref.unsafe[IO, Int](0)
    val list = List.fill(finalValue)(IO.shift *> r.update(_ + 1))
    val modifies = list.parSequenceN(3)
    (IO.shift *> modifies.start *> awaitEqual(r.get, finalValue)).as(assert(true))
  }

  test("F.parReplicateAN(n)(replicas, fa)") {
    val finalValue = 100
    val r = Ref.unsafe[IO, Int](0)
    val fa = IO.shift *> r.update(_ + 1)
    val modifies = implicitly[Concurrent[IO]].parReplicateAN(3)(finalValue, fa)
    (IO.shift *> modifies.start *> awaitEqual(r.get, finalValue)).as(assert(true))
  }

  test("fa.parSequenceN(n)(replicas)") {
    val finalValue = 100
    val r = Ref.unsafe[IO, Int](0)
    val fa = IO.shift *> r.update(_ + 1)
    val modifies = catsSyntaxParallelReplicateANConcurrent(fa).parReplicateAN(3)(finalValue)
    (IO.shift *> modifies.start *> awaitEqual(r.get, finalValue)).as(assert(true))
  }
}
