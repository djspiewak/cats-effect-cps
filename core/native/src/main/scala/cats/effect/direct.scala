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

  private[effect] def asyncImpl[F[_]: Sync, A](body: Await[F] => A): F[A] =
    boundary[F[A]] {
      Sync[F].delay {
        val await = new Await[F] {
          type Result = A
          val label = implicitly[BoundaryLabel[F[A]]]
        }

        body(await)
      }
    }

  sealed abstract class Await[F[_]] private[direct] (private[direct] implicit val F: Sync[F]) {
    private[direct] type Result
    private[direct] val label: BoundaryLabel[F[Result]]
  }

  implicit final class AwaitSyntax[F[_], A](val self: F[A]) extends AnyVal {
    def await(implicit await: Await[F]): A = {
      implicit val F: Sync[F] = await.F
      implicit val label: BoundaryLabel[F[await.Result]] = await.label

      suspend[A, F[await.Result]] { k =>
        self.flatMap(a => F.defer(k(a)))
      }
    }
  }
}
