---
title: Example
---

# Example
gql is a GraphQL implementation.
gql comes with many of the standard features of a GraphQL implementation such as a dsl, parser and interpreter.
But also some unique features such as, herustic query planning and signals.
The most important goals of gql is to be simple, predictable and composable.

For this showcase, Star Wars will be out domain of choice:
```scala mdoc
sealed trait Episode
object Episode {
  case object NewHope extends Episode
  case object Empire extends Episode
  case object Jedi extends Episode
}
  
trait Character {
  def id: String
  def name: Option[String]
  def friends: Option[List[Character]]
  def appearsIn: Option[List[Episode]]
}

final case class Human(
  id: String,
  name: Option[String],
  friends: Option[List[Character]],
  appearsIn: Option[List[Episode]],
  homePlanet: Option[String]
) extends Character

final case class Droid(
  id: String,
  name: Option[String],
  friends: Option[List[Character]],
  appearsIn: Option[List[Episode]],
  primaryFunction: Option[String]
) extends Character
```

To construct the schema, we need to import the `ast` and `dsl`.
The `ast` is an adt representation of the GraphQL schema language.
The `dsl` is a thin collection of smart constructors for the `ast`:
```scala mdoc
import gql.dsl._
import gql.ast._
```

With the `dsl` smart constructors in scope; we can now construct the schema:
```scala mdoc
import cats._
import cats.implicits._
import gql._

trait Repository[F[_]] {
  def getHero(episode: Episode): F[Character]

  def getCharacter(id: String): F[Character]

  def getHuman(id: String): F[Human]

  def getDroid(id: String): F[Droid]
}

def schema[F[_]: Applicative](implicit repo: Repository[F]): Schema[F, Unit] = {
  implicit val episode: Enum[F, Episode] = {
    import Episode._
    enum(
      "Episode",
      "NEWHOPE" -> NewHope,
      "EMPIRE" -> Empire,
      "JEDI" -> Jedi
    )
  }

  implicit lazy val human: Type[F, Human] =
    tpe(
      "Human",
      "homePlanet" -> field(pure(_.homePlanet)),
      character.fields.toList: _*
    )

  implicit lazy val droid: Type[F, Droid] =
    tpe(
      "Droid",
      "primaryFunction" -> field(pure(_.primaryFunction)),
      character.fields.toList: _*
    )

  implicit lazy val character: Interface[F, Character] =
    interface[F, Character](
      "Character",
      "id" -> field(pure(_.id)),
      "name" -> field(pure(_.name)),
      "friends" -> field(pure(_.friends)),
      "appearsIn" -> field(pure(_.appearsIn))
    )(
      instance[Human] { case x: Human => x },
      instance[Droid] { case x: Droid => x }
    )

  Schema.simple[F, Unit](
    tpe(
      "Query",
      "hero" -> field(arg[Episode]("episode"))(eff { case (_, episode) => repo.getHero(episode) }),
      "character" -> field(arg[ID[String]]("id"))(eff { case (_, id) => repo.getCharacter(id.value) }),
      "human" -> field(arg[ID[String]]("id"))(eff { case (_, id) => repo.getHuman(id.value) }),
      "droid" -> field(arg[ID[String]]("id"))(eff { case (_, id) => repo.getDroid(id.value) })
    )
  )
}
```

Lets construct a simple in-memory repository and query:
```scala mdoc
import cats.effect._
import cats.effect.unsafe.implicits.global

implicit def repo = new Repository[IO] {
  def getHero(episode: Episode): IO[Character] =
    IO.pure {
      episode match {
        case Episode.NewHope => Droid("1000", Some("R2-D2"), None, Some(List(Episode.NewHope)), Some("Astromech"))
        case Episode.Empire =>
          Human("1002", Some("Luke Skywalker"), None, Some(List(Episode.NewHope, Episode.Empire, Episode.Jedi)), Some("Tatooine"))
        case Episode.Jedi =>
          Human("1003", Some("Leia Organa"), None, Some(List(Episode.NewHope, Episode.Empire, Episode.Jedi)), Some("Alderaan"))
      }
    }
  def getCharacter(id: String): IO[Character] = ???
  def getHuman(id: String): IO[Human] = ???
  def getDroid(id: String): IO[Droid] = ???
}

def s = schema[IO]

def query = """
 query HeroNameQuery {
   hero(episode: NEWHOPE) {
     id
     name
     ... on Droid {
       primaryFunction
     }
     ... HumanDetails
   }
 }

 fragment HumanDetails on Human {
   homePlanet
 }
"""
```

Before running the query, we need to allocate a `Statistics` instance.
The `Statistics` algebra serves as the backbone of query planning.
```scala mdoc
implicit lazy val stats = Statistics[IO].unsafeRunSync()
```

Now we can parse, plan and evaluate the query:
```scala mdoc
def parsed = gql.parser.parse(query).toOption.get

def program = Execute.executor(parsed, s, Map.empty) match {
  case Execute.ExecutorOutcome.Query(run) => run(()).map { case (_, output) => output }
}

println(program.unsafeRunSync())
```