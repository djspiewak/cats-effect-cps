package cats.effect

import cats.effect.kernel.Sync

private[effect] abstract class DirectCompat { this: direct.type =>

  inline def async[F[_]](using Sync[F]): AsyncSyntax[F] = new AsyncSyntax[F]

  final class AsyncSyntax[F[_]](using Sync[F]) {
    def apply[A](body: Await[F] ?=> A): F[A] =
      asyncImpl[F, A](implicit a => body)
  }
}
