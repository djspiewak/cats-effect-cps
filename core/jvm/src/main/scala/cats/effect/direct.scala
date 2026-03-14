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

import cats.Applicative
import cats.effect.kernel.Sync
import cats.syntax.all._

import jdk.internal.vm.{Continuation, ContinuationScope}

object direct extends DirectCompat {

  private[effect] def asyncImpl[F[_]: Sync, A](body: Await[F] => A): F[A] = {
    def loop(cont: Continuation, box: Await[F]): F[Unit] =
      Sync[F].delay(cont.isDone()).ifM(
        Applicative[F].unit,
        Sync[F].delay(cont.run()) >> Sync[F].defer {
          if (box.next != null) {
            box.next.attempt.flatMap { r =>
              Sync[F].delay {
                box.next = null.asInstanceOf[F[Any]]
                box.result = r
              } >> loop(cont, box)
            }
          } else {
            // TODO I'm guessing that preemption and/or yielding will eventually show up here
            // (it doesn't today though; yield seems to be ignored)
            Applicative[F].unit
          }
        })

    Sync[F].defer {
      val scope = new ContinuationScope("cats-effect-direct")
      val box = new Await[F](scope)
      val cont = new Continuation(scope, { () =>
        box.result = Right(body(box))
      })

      loop(cont, box) *> Sync[F].delay {
        box.result match {
          case null =>
            throw new IllegalStateException(
              "async block terminated prematurely without producing a result")

          case Left(t) =>
            throw t

          case Right(a) =>
            a.asInstanceOf[A]
        }
      }
    }
  }

  final class Await[F[_]] private[direct] (private[direct] val scope: ContinuationScope) {
    private[direct] var next: F[Any] = _
    private[direct] var result: Either[Throwable, Any] = _
  }

  implicit final class AwaitSyntax[F[_], A](val self: F[A]) extends AnyVal {
    def await(implicit await: Await[F]): A = {
      await.next = self.asInstanceOf[F[Any]]

      try {
        Continuation.`yield`(await.scope)
      } catch {
        case t: IllegalStateException =>
          await.next = null.asInstanceOf[F[Any]]
          throw new IllegalStateException("call to await from a different thread than surrounding async", t)
      }

      await.result.asInstanceOf[Either[Throwable, A]] match {
        case Left(t) => throw t
        case Right(a) => a
      }
    }
  }
}
