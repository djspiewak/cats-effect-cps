/*
 * Copyright 2021-2022 Typelevel
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

import cats.syntax.all._
import cats.data.{Kleisli, OptionT, WriterT}

import munit.CatsEffectSuite

import scala.concurrent.duration._

import cps._

class AsyncAwaitSuite extends CatsEffectSuite {

  test("async[IO] - work on success") {
    val io = IO.sleep(100.millis) >> IO.pure(1)

    val program = async[IO](io.await + io.await)

    program.flatMap { res =>
      IO {
        assertEquals(res, 2)
      }
    }
  }

  test("async[IO] - propagate errors outward") {
    case object Boom extends Throwable
    val io = IO.raiseError[Int](Boom)

    val program = async[IO](io.await)

    program.attempt.flatMap { res =>
      IO {
        assertEquals(res, Left(Boom))
      }
    }
  }

  test("async[IO] - propagate uncaught errors outward") {
    case object Boom extends Throwable

    def boom(): Unit = throw Boom
    val program = async[IO](boom())

    program.attempt.flatMap { res =>
      IO {
        assertEquals(res, Left(Boom))
      }
    }
  }

  test("async[IO] - propagate canceled outcomes outward") {
    val io = IO.canceled

    val program = async[IO](io.await)

    program.start.flatMap(_.join).flatMap { res =>
      IO {
        assertEquals(res, Outcome.canceled[IO, Throwable, Unit])
      }
    }
  }

  test("async[IO] - be cancellable") {
    val program = for {
      ref <- Ref[IO].of(0)
      _ <- async[IO] {
        IO.never[Unit].await
        ref.update(_ + 1).await
      }.start.flatMap(_.cancel)
      result <- ref.get
    } yield {
      result
    }

    program.flatMap { res =>
      IO {
        assertEquals(res, 0)
      }
    }

  }

  test("async[IO] - suspend side effects") {
    var x = 0
    val program = async[IO](x += 1)

    for {
      _ <- IO(assertEquals(x, 0))
      _ <- program
      _ <- IO(assertEquals(x, 1))
      _ <- program
      _ <- IO(assertEquals(x, 2))
    } yield ()
  }

  test("async[Kleisli[IO, R, *]] - work on successes") {
    type F[A] = Kleisli[IO, Int, A]

    val io = Temporal[F].sleep(100.millis) >> Kleisli(x => IO.pure(x + 1))

    val program = async[F](io.await + io.await)

    program.run(0).flatMap { res =>
      IO {
        assertEquals(res, 2)
      }
    }
  }

  test("async[OptionT[IO, *]] - work on successes") {
    val io = Temporal[OptionT[IO, *]].sleep(100.millis) >> OptionT.pure[IO](1)

    val program = async[OptionT[IO, *]](io.await + io.await)

    program.value.flatMap { res =>
      IO {
        assertEquals(res, Some(2))
      }
    }
  }

  test("async[OptionT[IO, *]] - work on None") {
    val io1 = OptionT.pure[IO](1)
    val io2 = OptionT.none[IO, Int]

    val program = async[OptionT[IO, *]](io1.await + io2.await)

    program.value.flatMap { res =>
      IO {
        assertEquals(res, None)
      }
    }
  }

  {
    type F[A] = OptionT[OptionT[IO, *], A]

    test("async[OptionT[OptionT[IO, *], *] - surface None at the right layer (1)") {
      val io = OptionT.liftF(OptionT.none[IO, Int])

      val program = async[F](io.await)

      program.value.value.flatMap { res =>
        IO {
          assertEquals(res, None)
        }
      }
    }

    test("async[OptionT[OptionT[IO, *], *] - surface None at the right layer (2)") {
      val io1 = 1.pure[F]
      val io2 = OptionT.none[OptionT[IO, *], Int]

      val program = async[F](io1.await + io2.await)

      program.value.value.flatMap { res =>
        IO {
          assertEquals(res, Some(None))
        }
      }
    }
  }

  test("async[WriterT[IO, T, *]] - surface logged") {
    type F[A] = WriterT[IO, Int, A]

    val io1 = WriterT(IO((1, 3)))

    val program = async[F](io1.await * io1.await)

    program.run.flatMap { res =>
      IO {
        assertEquals(res, (2, 9))
      }
    }
  }

  type OptionTIO[A] = OptionT[IO, A]

  test("async[F] - respect nested async[G] calls") {
    val optionT = OptionT.liftF(IO(1))

    val program = async[IO]{
      async[OptionTIO](optionT.await).value.await
    }

    program.flatMap { res =>
      IO {
        assertEquals(res, Some(1))
      }
    }
  }

  test("async[F] - allow for polymorphic usage") {
    def foo[F[_] : Async] = async[F]{ 1.pure[F].await }

    foo[IO].flatMap { res =>
      IO {
        assertEquals(res, 1)
      }
    }
  }
}
