/*
 * Copyright 2021-2026 Typelevel
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

import cats.effect.kernel.Sync
import cats.syntax.all._

import scalanative.runtime.Continuations._

// TODO all of this is Scala 3 only
object direct extends DirectCompat {

  private[effect] def asyncImpl[F[_]: Sync, A](body: Await[F] => A): F[A] = {
    val await = new Await[F] {
      type Result = A
      var label = null.asInstanceOf
    }

    def loop(body: => Either[Step[F, A], A]): F[A] = {
      val next = Sync[F].delay {
        boundary[Either[Step[F, A], A]] {
          // this is a new label with each step
          // really it's easiest to think of this like setting and calling a longjmp
          await.label = implicitly[BoundaryLabel[Either[Step[F, A], A]]]
          await.carrier = Thread.currentThread()
          body
        }
      }

      next.flatMap {
        case Left(step) =>
          step.fe.attempt.flatMap(e => loop(step.f(e)))

        case Right(a) =>
          a.pure[F]
      }
    }

    loop(Right(body(await)))
  }

  // kind of like a yoneda, but actually free flatMap due to the CPS
  private[direct] sealed abstract class Step[F[_], A] {
    type E
    val fe: F[E]
    val f: Either[Throwable, E] => Either[Step[F, A], A]
  }

  sealed abstract class Await[F[_]] {
    private[direct] type Result

    // we recreate the boundary with each step, so this needs to be mutable
    private[direct] var label: BoundaryLabel[Either[Step[F, Result], Result]]
    private[direct] var carrier: Thread = _
  }

  implicit final class AwaitSyntax[F[_], A](val self: F[A]) extends AnyVal {
    def await(implicit await: Await[F]): A = {
      implicit val label: BoundaryLabel[Either[Step[F, await.Result], await.Result]] = await.label

      if (await.carrier == Thread.currentThread()) {
        val result = suspend[Either[Throwable, A], Either[Step[F, await.Result], await.Result]] { k =>
          val step = new Step[F, await.Result] {
            type E = A
            val fe = self
            val f = k
          }

          Left(step)
        }

        result match {
          case Left(t) => throw t
          case Right(a) => a
        }
      } else {
        throw new IllegalStateException("call to await from a different thread than surrounding async")
      }
    }
  }
}
