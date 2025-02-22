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

import cats.effect.concurrent.{Deferred, MVar}
import cats.syntax.all._
import cats.laws._
import cats.laws.discipline._
import org.scalacheck.Prop.forAll

import scala.concurrent.Promise
import scala.util.Success

class ConcurrentCancelableFTests extends BaseTestsSuite {
  testAsync("Concurrent.cancelableF works for immediate values") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    forAll { (value: Either[Throwable, Int]) =>
      val received = Concurrent.cancelableF[IO, Int](cb => IO { cb(value); IO.unit })
      received <-> IO.fromEither(value)
    }
  }

  testAsync("Concurrent.cancelableF works for async values") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    forAll { (value: Either[Throwable, Int]) =>
      val received = Concurrent.cancelableF[IO, Int] { cb =>
        cs.shift *> IO { cb(value); IO.unit }
      }
      received <-> IO.fromEither(value)
    }
  }

  testAsync("Concurrent.cancelableF can delay callback") { implicit ec =>
    import scala.concurrent.duration._
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val timer = ec.timer[IO]

    val task = Concurrent.cancelableF[IO, Int] { cb =>
      val task = timer.sleep(1.second) *> IO(cb(Right(10)))
      for {
        fiber <- task.start
      } yield {
        fiber.cancel
      }
    }

    val f = task.unsafeToFuture()
    ec.tick()
    assertEquals(f.value, None)

    ec.tick(1.second)
    assertEquals(f.value, Some(Success(10)))
  }

  testAsync("Concurrent.cancelableF can delay task execution") { implicit ec =>
    import scala.concurrent.duration._
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val timer = ec.timer[IO]

    val complete = Promise[Unit]()
    val task = Concurrent.cancelableF[IO, Int] { cb =>
      val effect = timer.sleep(1.second) *> IO(complete.success(())) *> IO(IO.unit)
      IO(cb(Right(10))) *> effect
    }

    val f = task.unsafeToFuture()
    ec.tick()
    assertEquals(f.value, Some(Success(10)))
    assertEquals(complete.future.value, None)

    ec.tick(1.second)
    assertEquals(complete.future.value, Some(Success(())))
  }

  testAsync("Concurrent.cancelableF can yield cancelable tasks") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val task = for {
      d <- MVar.empty[IO, Unit]
      latch <- Deferred[IO, Unit]
      task = Concurrent.cancelableF[IO, Unit] { _ =>
        cs.shift *> latch.complete(()) *> IO(d.put(()))
      }
      fiber <- task.start
      _ <- latch.get
      r <- d.tryTake
      _ <- fiber.cancel
      _ <- d.take
    } yield {
      assertEquals(r, None)
    }

    val f = task.unsafeToFuture()
    ec.tick()
    assertEquals(f.value, Some(Success(assert(true))))
  }

  testAsync("Concurrent.cancelableF executes generated task uninterruptedly") { implicit ec =>
    import scala.concurrent.duration._

    implicit val cs: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    var effect = 0
    val task = Concurrent.cancelableF[IO, Unit] { cb =>
      IO.sleep(1.second) *> IO(effect += 1) *> IO(cb(Right(()))) *> IO(IO.unit)
    }

    val p = Promise[Unit]()
    val cancel = task.unsafeRunCancelable(r => p.success(r.valueOr(throw _)))
    cancel.unsafeRunAsyncAndForget()

    ec.tick()
    assertEquals(p.future.value, None)
    assertEquals(ec.state.tasks.isEmpty, false)
    assertEquals(effect, 0)

    ec.tick(1.second)
    assertEquals(p.future.value, None)
    assertEquals(ec.state.tasks.isEmpty, true)
    assertEquals(effect, 1)
  }
}
