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
package gql.gentest

import cats.effect._
import cats.implicits._
import gql.client.gen.test._
import gql._
import gql.dsl.all._
import gql.ast._
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.Request
import cats.data.NonEmptyList
import cats.data.Ior

class GenTest extends CatsEffectSuite {
  import GenTest._

  lazy val sch = schema.unsafeRunSync()

  test("should be able to use the generated query") {
    val q = AlienQuery.query.compile(AlienQuery.Variables(name = "Colt"))
    val client = Client.fromHttpApp[IO](
      gql.http4s.Http4sRoutes
        .sync[IO]()(x => x.parse.flatMap(qp => gql.http4s.Http4sRoutes.toResponse(Compiler[IO].compileWith(sch, qp))))
        .orNotFound
    )

    import gql.client.http4s.syntax._
    import org.http4s.implicits._
    val res = Request[IO](uri = uri"https://notreal.com/graphql").graphql(q, client)
    res.map(aq => assert(aq.dog.isDefined))
  }

  test("schema and it's stub should be equivalent") {
    val s = sch.shape
    val primitives = Set("String", "Int", "Boolean", "ID", "Float")
    def f[A](m: Map[String, A]): Map[String, A] =
      m.collect { case (k, v) if !primitives.contains(k) => k -> v }
    (f(s.stub.ast) align f(s.ast)).foreach {
      case (_, Ior.Both(x, y)) =>
        if (x != y) fail(s"$x is not equal to $y")
      case (k, Ior.Left(x))  => fail(s"$k is in stub but not in schema (${x})")
      case (k, Ior.Right(x)) => fail(s"$k is in schema but not in stub (${x})")
    }
  }
}

object GenTest {
  sealed trait DogCommand extends Product with Serializable
  object DogCommand {
    case object Sit extends DogCommand
    case object Down extends DogCommand
    case object Heel extends DogCommand
  }

  trait Pet {
    def name: String
  }

  final case class Dog(
      name: String,
      nickname: Option[String],
      barkVolume: Int,
      doesKnowCommand: DogCommand => Boolean,
      isHousetrained: Boolean => Boolean,
      owner: Option[String] = None
  ) extends Pet

  trait Sentient {
    def name: String
  }

  final case class Alien(
      name: String,
      homePlanet: String
  ) extends Sentient

  final case class Human(
      name: String,
      pets: List[String]
  ) extends Sentient

  sealed trait CatCommand extends Product with Serializable
  object CatCommand {
    case object Jump extends CatCommand
  }

  final case class Cat(
      name: String,
      nickname: Option[String],
      meowVolume: Int,
      doesKnowCommand: CatCommand => Boolean
  ) extends Pet

  sealed trait CatOrDog extends Product with Serializable
  object CatOrDog {
    case class CatType(cat: Cat) extends CatOrDog
    case class DogType(dog: Dog) extends CatOrDog
  }

  sealed trait DogOrHuman extends Product with Serializable
  object DogOrHuman {
    case class DogType(dog: Dog) extends DogOrHuman
    case class HumanType(human: Human) extends DogOrHuman
  }

  sealed trait HumanOrAlien extends Product with Serializable
  object HumanOrAlien {
    case class HumanType(human: Human) extends HumanOrAlien
    case class AlienType(alien: Alien) extends HumanOrAlien
  }

  sealed trait OutputOnly extends Product with Serializable
  object OutputOnly {
    case object Ok extends OutputOnly
    case object NotOk extends OutputOnly
  }

  final case class FindDogInput(
      name: Option[String] = None,
      owner: Option[String] = None
  )

  val dogDatabase = Map(
    "Rover" -> Dog("Rover", Some("Rover"), 10, _ == DogCommand.Sit, _ && true),
    "Spot" -> Dog("Spot", None, 10, _ == DogCommand.Sit, _ || false),
    "Lassie" -> Dog("Lassie", None, 10, _ == DogCommand.Sit, _ && true),
    "Colt" -> Dog("Colt", None, 10, _ == DogCommand.Sit, _ => false, Some("Hank"))
  )

  val humanDatabase = Map(
    "Hank" -> Human("Hank", List("Colt"))
  )

  implicit lazy val dogCommand: Enum[DogCommand] = enumType[DogCommand](
    "DogCommand",
    "SIT" -> enumVal(DogCommand.Sit),
    "DOWN" -> enumVal(DogCommand.Down),
    "HEEL" -> enumVal(DogCommand.Heel)
  )

  implicit lazy val outputOnly: Enum[OutputOnly] = enumType[OutputOnly](
    "OutputOnly",
    "OK" -> enumVal(OutputOnly.Ok),
    "NOT_OK" -> enumVal(OutputOnly.NotOk)
  )

  lazy val petFields: Fields[IO, Pet] = fields[IO, Pet](
    "name" -> lift(_.name)
  )

  implicit lazy val pet: Interface[IO, Pet] = interfaceNel[IO, Pet]("Pet", petFields)

  implicit lazy val dog: Type[IO, Dog] = tpe[IO, Dog](
    "Dog",
    "nickname" -> lift(_.nickname),
    "barkVolume" -> lift(_.barkVolume),
    "doesKnowCommand" -> lift(arg[DogCommand]("dogCommand")) { case (x, y) => y.doesKnowCommand(x) },
    "isHousetrained" -> lift(arg[Option[Boolean]]("atOtherHomes")) { case (x, y) => y.isHousetrained(x.getOrElse(false)) },
    "owner" -> lift(_.owner.flatMap(humanDatabase.get))
  ).addFields(petFields.toList: _*)
    .subtypeOf[Pet]

  lazy val sentientFields = fields[IO, Sentient](
    "name" -> lift(_.name)
  )

  implicit lazy val sentient: Interface[IO, Sentient] =
    interfaceNel[IO, Sentient]("Sentient", sentientFields)

  implicit lazy val alient: Type[IO, Alien] = tpe[IO, Alien](
    "Alien",
    "homePlanet" -> lift(_.homePlanet)
  ).addFields(sentientFields.toList: _*)
    .subtypeOf[Sentient]

  implicit lazy val human: Type[IO, Human] = tpe[IO, Human](
    "Human",
    "pets" -> lift(_.pets.flatMap(dogDatabase.get))
  ).addFields(sentientFields.toList: _*)
    .subtypeOf[Sentient]

  implicit lazy val catCommand: Enum[CatCommand] = enumType[CatCommand](
    "CatCommand",
    "JUMP" -> enumVal(CatCommand.Jump)
  )

  implicit lazy val cat: Type[IO, Cat] = tpe[IO, Cat](
    "Cat",
    "nickname" -> lift(_.nickname),
    "meowVolume" -> lift(_.meowVolume),
    "doesKnowCommand" -> lift(arg[CatCommand]("catCommand")) { case (x, y) => y.doesKnowCommand(x) }
  ).addFields(petFields.toList: _*)
    .subtypeOf[Pet]

  implicit lazy val catOrDog: Union[IO, CatOrDog] = union[IO, CatOrDog]("CatOrDog")
    .variant[Cat] { case CatOrDog.CatType(x) => x }
    .variant[Dog] { case CatOrDog.DogType(x) => x }

  implicit lazy val dogOrHuman: Union[IO, DogOrHuman] = union[IO, DogOrHuman]("DogOrHuman")
    .variant[Dog] { case DogOrHuman.DogType(x) => x }
    .variant[Human] { case DogOrHuman.HumanType(x) => x }

  implicit lazy val humanOrAlien: Union[IO, HumanOrAlien] = union[IO, HumanOrAlien]("HumanOrAlien")
    .variant[Human] { case HumanOrAlien.HumanType(x) => x }
    .variant[Alien] { case HumanOrAlien.AlienType(x) => x }

  implicit lazy val findDogInput: Input[FindDogInput] = input[FindDogInput](
    "FindDogInput",
    (
      arg[Option[String]]("name"),
      arg[Option[String]]("owner")
    ).mapN(FindDogInput.apply)
  )

  final case class Escaping(
      tpe: String
  )
  implicit lazy val escaping: Input[Escaping] = input[Escaping](
    "Escaping",
    arg[String]("type").map(Escaping.apply)
  )

  def schema: IO[Schema[IO, Unit, Unit, Unit]] = Schema.simple(
    SchemaShape
      .unit[IO](
        fields(
          "outputOnly" -> lift(_ => OutputOnly.Ok: OutputOnly),
          "dog" -> lift(_ => dogDatabase("Colt")),
          "findDog" -> lift(arg[Option[FindDogInput]]("searchBy")) { case (x, _) =>
            x.flatMap(y => dogDatabase.get(y.name.getOrElse("Colt")))
          },
          "escaping" -> lift(arg[Option[Escaping]]("x")) { case (x, _) => x.foldMap(_.tpe) },
          "stacked" -> lift(arg[Option[NonEmptyList[String]]]("woah", value.nullValue)) { case _ =>
            true
          }
        )
      )
      .addOutputTypes(cat)
  )
}
