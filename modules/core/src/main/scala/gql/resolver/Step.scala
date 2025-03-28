/*
 * Copyright 2023 Valdemar Grange
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
package gql.resolver

import gql._
import cats.data._

/** A step is a composable task that takes an input and produces an output.
  */
sealed trait Step[+F[_], -I, +O]

object Step {
  object Alg {
    final case class Lift[I, O](f: I => O) extends AnyRef with Step[Nothing, I, O]

    final case class EmbedEffect[F[_], I]() extends AnyRef with Step[F, F[I], I]

    final case class EmbedStream[F[_], I]() extends AnyRef with Step[F, fs2.Stream[F, I], I]

    final case class EmbedError[I]() extends AnyRef with Step[Nothing, Ior[String, I], I]

    final case class Argument[I, A](arg: Arg[A]) extends Step[Nothing, I, A]

    final case class Compose[F[_], I, A, O](left: Step[F, I, A], right: Step[F, A, O]) extends Step[F, I, O]

    final case class Choose[F[_], A, B, C, D](fac: Step[F, A, C], fab: Step[F, B, D]) extends Step[F, Either[A, B], Either[C, D]]

    final case class GetMeta[F[_], I]() extends Step[Nothing, I, FieldMeta[F]]

    final case class First[F[_], A, B, C](step: Step[F, A, B]) extends Step[F, (A, C), (B, C)]

    final case class Batch[F[_], K, V](id: BatchKey[K, V]) extends Step[F, Set[K], Map[K, V]]

    final case class InlineBatch[F[_], K, V](run: Set[K] => F[Map[K, V]]) extends Step[F, Set[K], Map[K, V]]
  }

  def lift[F[_], I, O](f: I => O): Step[F, I, O] =
    Alg.Lift(f)

  def embedEffect[F[_], I]: Step[F, F[I], I] =
    Alg.EmbedEffect()

  def embedError[F[_], I]: Step[F, Ior[String, I], I] =
    Alg.EmbedError()

  def embedStream[F[_], I, O]: Step[F, fs2.Stream[F, I], I] =
    Alg.EmbedStream()

  def argument[F[_], A](arg: Arg[A]): Step[F, Any, A] =
    Alg.Argument(arg)

  def compose[F[_], I, A, O](left: Step[F, I, A], right: Step[F, A, O]): Step[F, I, O] =
    Alg.Compose(left, right)

  def choose[F[_], A, B, C, D](fac: Step[F, A, C], fab: Step[F, B, D]): Step[F, Either[A, B], Either[C, D]] =
    Alg.Choose(fac, fab)

  def getMeta[F[_]]: Step[F, Any, FieldMeta[F]] =
    Alg.GetMeta()

  def first[F[_], A, B, C](step: Step[F, A, B]): Step[F, (A, C), (B, C)] =
    Alg.First(step)

  final case class BatchKey[K, V](id: Int) extends AnyVal

  def batch[F[_], K, V](f: Set[K] => F[Map[K, V]]): State[gql.SchemaState[F], Step[F, Set[K], Map[K, V]]] =
    State { s =>
      val id = s.nextId
      val k = BatchKey[K, V](id)
      (s.copy(nextId = id + 1, batchFunctions = s.batchFunctions + (k -> SchemaState.BatchFunction(f))), Alg.Batch(k))
    }

  def inlineBatch[F[_], K, V](f: Set[K] => F[Map[K, V]]): Step[F, Set[K], Map[K, V]] =
    Alg.InlineBatch(f)

  import cats.arrow._
  implicit def arrowChoiceForStep[F[_]]: ArrowChoice[Step[F, *, *]] = new ArrowChoice[Step[F, *, *]] {
    override def choose[A, B, C, D](f: Step[F, A, C])(g: Step[F, B, D]): Step[F, Either[A, B], Either[C, D]] =
      Step.choose(f, g)

    override def compose[A, B, C](f: Step[F, B, C], g: Step[F, A, B]): Step[F, A, C] = Step.compose(g, f)

    override def first[A, B, C](fa: Step[F, A, B]): Step[F, (A, C), (B, C)] = Step.first(fa)

    override def lift[A, B](f: A => B): Step[F, A, B] = Step.lift(f)
  }
}
