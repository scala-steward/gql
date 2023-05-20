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
package gql.parser

import cats.parse.{Parser => P}
import cats.parse.Caret

// https://spec.graphql.org/June2018/#sec-Source-Text
object QueryParser {
  import QueryAst._
  import GraphqlParser._

  lazy val executableDefinition = {
    import ExecutableDefinition._
    Pos.pos(fragmentDefinition).map(p => Fragment(p.value, p.caret)) |
      Pos.pos(operationDefinition).map(p => Operation(p.value, p.caret))
  }

  lazy val operationDefinition: P[OperationDefinition[Caret]] = {
    import OperationDefinition._
    P.backtrack(selectionSet).map(Simple(_)) |
      (operationType ~ name.? ~ variableDefinitions.? ~ selectionSet).map { case (((opt, name), vars), ss) =>
        Detailed(opt, name, vars, ss)
      }
  }

  lazy val variableDefinitions =
    variableDefinition.rep
      .between(t('('), t(')'))
      .map(VariableDefinitions(_))

  lazy val variableDefinition = 
    Pos.pos(variable ~ (t(':') *> `type`) ~ defaultValue(constValue).?).map { case Pos(c, ((n, t), d)) => 
      VariableDefinition(n, t, d, c) 
    }

  lazy val operationType = {
    import OperationType._

    s("query").as(Query) |
      s("mutation").as(Mutation) |
      s("subscription").as(Subscription)
  }

  lazy val selectionSet: P[SelectionSet[Caret]] = P.defer {
    selection.rep.between(t('{'), t('}')).map(SelectionSet(_))
  }

  lazy val selection: P[Selection[Caret]] = {
    import Selection._
    Pos.pos(field).map(p => FieldSelection(p.value, p.caret)) |
      // expects on, backtrack on failure
      Pos.pos(inlineFragment).map(p => InlineFragmentSelection(p.value, p.caret)) |
      Pos.pos(fragmentSpread).map(p => FragmentSpreadSelection(p.value, p.caret))
  }

  lazy val field: P[Field[Caret]] = P.defer {
    Pos.pos(P.backtrack(alias).?.with1 ~ name ~ arguments.? ~ selectionSet.?)
      .map { case Pos(c, (((a, n), args), s)) => Field(a, n, args, s, c) }
  }

  lazy val alias = name <* t(':')

  lazy val arguments =
    argument.rep.between(t('('), t(')')).map(Arguments.apply)

  lazy val argument =
    (name ~ (t(':') *> value)).map { case (n, v) => Argument(n, v) }

  lazy val fragmentSpread =
    (s("...") *> fragmentName).map(FragmentSpread.apply)

  lazy val inlineFragment =
    ((s("...") *> typeCondition.?).soft ~ selectionSet).map { case (t, s) => InlineFragment(t, s) }

  lazy val fragmentDefinition =
    Pos.pos(s("fragment") *> fragmentName ~ typeCondition ~ selectionSet).map { case Pos(c, ((n, t), s)) =>
      FragmentDefinition(n, t, s, c)
    }

  lazy val fragmentName: P[String] =
    (!s("on")).with1 *> name

  lazy val typeCondition: P[String] =
    s("on") *> name
}
