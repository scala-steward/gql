# gql <a href="https://typelevel.org/cats/"><img src="https://typelevel.org/cats/img/cats-badge.svg" height="40px" align="right" alt="Cats friendly" /></a>

gql is a functional server and client GraphQL implementation for Scala.

It enables succintly exposing and consuming GraphQL APIs in a purely functional way.

* **Statically typed.** Statically typed through and through.
* **Purely functional.** Uses the simplest and most precise abstractions.
* **Extensible.** Ability to add features on top of gql ranging from lightweight utility to spec-compliant schema transformations.
* **And so much more!**

To learn more, check out the [docs.](https://valdemargr.github.io/gql/)

# Installation
gql is available for Scala 2.13 and 3.3.
The available modules are listed on the [modules page](https://valdemargr.github.io/gql/docs/overview/modules).

# Example
```scala
import gql.dsl.all._
import gql.ast._
import cats.effect._
import cats.implicits._

case class Human(name: String, friends: List[String])

def getFriend(name: String): IO[Human] = ???

implicit val human: Type[IO, Human] = tpe[IO, Human](
  "Human",
  "name" -> lift(h => h.name),
  "friends" -> eff(_.friends.traverse(getFriend))
)
```

# Developing and using gql
gql features decriptive and precise error messages, to aid the developer in writing a correct implementation.

gql provides descriptive messages in case of query errors.
```graphql
| query MyQuery {
|     test.,test
| >>>>>^^^^^^^ line:2, column:16, offset:41, character code:46
| }
```
And also when the schema has been defined incorrectly.
```
// Argument 'myArg' was defined in field 'myField' in type `MyType` but was not defined in interface `MyInterface`. at root.Query.myField
// Invalid field name '0hey', the field name must match /[_A-Za-z][_0-9A-Za-z]*/ at root.Query.0hey
```
