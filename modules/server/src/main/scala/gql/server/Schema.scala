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
package gql

import cats.effect._
import cats.implicits._
import cats.data._
import cats._
import gql.ast._
import gql.server.planner._

final case class Schema[F[_], Q, M, S](
    shape: SchemaShape[F, Q, M, S],
    state: SchemaState[F],
    statistics: Statistics[F],
    planner: Planner[F]
) {
  protected implicit lazy val s: Statistics[F] = statistics

  lazy val validate: Chain[Validation.Problem] = shape.validate

  lazy val render: String = shape.render
}

object Schema {
  def stateful[F[_]: Applicative, Q, M, S](
      statistics: Statistics[F]
  )(fa: State[SchemaState[F], SchemaShape[F, Q, M, S]]): Schema[F, Q, M, S] = {
    val (state, shape) = fa.run(SchemaState(0, Map.empty, Nil)).value
    Schema(shape.copy(positions = shape.positions ++ state.positions), state, statistics, Planner[F])
  }

  def stateful[F[_]: Concurrent, Q, M, S](fa: State[SchemaState[F], SchemaShape[F, Q, M, S]]): F[Schema[F, Q, M, S]] =
    Statistics[F].map(stateful(_)(fa))

  def query[F[_]: Applicative, Q](statistics: Statistics[F])(query: Type[F, Q]): Schema[F, Q, Unit, Unit] =
    stateful(statistics)(State.pure(SchemaShape(query, None, None)))

  def query[F[_]: Concurrent, Q](query: Type[F, Q]): F[Schema[F, Q, Unit, Unit]] =
    stateful(State.pure(SchemaShape(query, None, None)))

  def simple[F[_]: Concurrent, Q, M, S](shape: SchemaShape[F, Q, M, S]): F[Schema[F, Q, M, S]] =
    Statistics[F].map(Schema(shape, SchemaState.empty[F], _, Planner[F]))

  def simple[F[_]: Applicative, Q, M, S](statistics: Statistics[F])(shape: SchemaShape[F, Q, M, S]): Schema[F, Q, M, S] =
    Schema(shape, SchemaState.empty[F], statistics, Planner[F])
}
