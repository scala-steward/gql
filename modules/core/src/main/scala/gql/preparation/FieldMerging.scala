package gql.preparation

import gql.parser.{QueryAst => QA, Value => V, AnyValue, Const}
import cats.data._
import io.circe._
import cats.mtl._
import cats._
import cats.implicits._
import gql.parser.QueryAst
import gql.parser.Pos
import gql.ast._
import gql.Arg
import gql.InverseModifierStack

trait FieldMerging[F[_], G[_], C] {
  def checkSelectionsMerge(xs: NonEmptyList[SelectionInfo[G, C]]): F[Unit]

  def checkFieldsMerge(
      a: FieldInfo[G, C],
      asi: SelectionInfo[G, C],
      b: FieldInfo[G, C],
      bsi: SelectionInfo[G, C]
  ): F[Unit]

  // These technically don't need to be in the trait, but it's convenient because of error handling
  // If needed, they can always be moved
  def compareArguments(name: String, aa: QA.Arguments, ba: QA.Arguments, caret: Option[C]): F[Unit]

  def compareValues(av: V[AnyValue], bv: V[AnyValue], caret: Option[C]): F[Unit]
}

object FieldMerging {
  def apply[F[_]: Parallel, G[_], C](implicit
      F: Monad[F],
      E: ErrorAlg[F, C],
      PA: PathAlg[F]
  ): FieldMerging[F, G, C] = {
    import E._
    import PA._

    new FieldMerging[F, G, C] {
      override def checkSelectionsMerge(xs: NonEmptyList[SelectionInfo[G, C]]): F[Unit] = {
        val ys: NonEmptyList[NonEmptyList[(SelectionInfo[G, C], FieldInfo[G, C])]] =
          xs.flatMap(si => si.fields tupleLeft si)
            .groupByNem { case (_, f) => f.outputName }
            .toNel
            .map { case (_, v) => v }

        ys.parTraverse_ { zs =>
          // TODO partition into what should be fullchecked and what should be structural
          val mergeFieldsF = {
            val (siHead, fiHead) = zs.head
            zs.tail.parTraverse_ { case (si, fi) => checkFieldsMerge(fiHead, siHead, fi, si) }
          }

          mergeFieldsF >>
            zs.toList
              .map { case (_, fi) => fi.tpe.inner }
              .collect { case s: TypeInfo.Selectable[G, C] => s.selection.toList }
              .flatten
              .toNel
              .traverse_(checkSelectionsMerge)
        }
      }

      // Optimization: we don't check selections recursively since checkSelectionsMerge traverses the whole tree
      // We only need to check the immidiate children and will eventually have checked the whole tree
      def checkSimplifiedTypeShape(a: InverseModifierStack[TypeInfo[G, C]], b: InverseModifierStack[TypeInfo[G, C]], caret: C): F[Unit] = {
        (a.inner, b.inner) match {
          // It turns out we don't care if more fields are selected in one object than the other
          case (TypeInfo.Selectable(_, _), TypeInfo.Selectable(_, _)) => F.unit
          // case (SimplifiedType.Selectable(_, l), SimplifiedType.Selectable(_, r)) => F.unit
          // val lComb = l.flatMap(x => x.fields tupleLeft x).groupByNem { case (_, f) => f.outputName }
          // val rComb = r.flatMap(x => x.fields tupleLeft x).groupByNem { case (_, f) => f.outputName }
          // (lComb align rComb).toNel.parTraverse_ {
          //   case (_, Ior.Both(_, _)) => F.unit
          //   case (k, Ior.Left(_))    => raise(s"Field '$k' was missing when verifying shape equivalence.", Some(caret))
          //   case (k, Ior.Right(_))   => raise(s"Field '$k' was missing when verifying shape equivalence.", Some(caret))
          // }
          case (TypeInfo.Enum(l), TypeInfo.Enum(r)) =>
            if (l === r) F.unit
            else raise(s"Enums are not the same, got '$l' and '$r'.", Some(caret))
          case (TypeInfo.Scalar(l), TypeInfo.Scalar(r)) =>
            if (l === r) F.unit
            else raise(s"Scalars are not the same, got '$l' and '$r'.", Some(caret))
          case _ =>
            raise(s"Types are not the same, got `${a.invert.show(_.name)}` and `${b.invert.show(_.name)}`.", Some(caret))
        }
      }

      override def checkFieldsMerge(a: FieldInfo[G, C], asi: SelectionInfo[G, C], b: FieldInfo[G, C], bsi: SelectionInfo[G, C]): F[Unit] = {
        sealed trait EitherObject
        object EitherObject {
          case object FirstIsObject extends EitherObject
          case object SecondIsObject extends EitherObject
          case object NeitherIsObject extends EitherObject
          case object BothAreObjects extends EitherObject
        }
        lazy val objectPair = (asi.s, bsi.s) match {
          case (_: Type[G, ?], _: Type[G, ?]) => EitherObject.BothAreObjects
          case (_: Type[G, ?], _)             => EitherObject.FirstIsObject
          case (_, _: Type[G, ?])             => EitherObject.SecondIsObject
          case _                              => EitherObject.NeitherIsObject
        }

        val parentNameSame = asi.s.name === bsi.s.name

        lazy val aIn = s"${fieldName(a)} in type `${asi.s.name}`"
        lazy val bIn = s"${fieldName(b)} in type `${bsi.s.name}`"

        lazy val whyMerge = {
          val why1 = if (parentNameSame) Some("they have the same parent type") else None
          val why2 = objectPair match {
            case EitherObject.FirstIsObject   => Some(s"the second field ${fieldName(a)} is not an object but the first was")
            case EitherObject.SecondIsObject  => Some(s"the first field ${fieldName(b)} is not an object but the second was")
            case EitherObject.NeitherIsObject => Some(s"neither field ${fieldName(a)} nor ${fieldName(b)} are objects")
            case EitherObject.BothAreObjects  => None
          }
          List(why1, why2).collect { case Some(err) => err }.mkString(" and ") + "."
        }

        // 2. in FieldsInSetCanMerge
        val thoroughCheckF = if (parentNameSame || objectPair != EitherObject.BothAreObjects) {
          val argsF = (a.args, b.args) match {
            case (None, None)         => F.unit
            case (Some(_), None)      => raise(s"A selection of field ${fieldName(a)} has arguments, while another doesn't.", Some(b.caret))
            case (None, Some(_))      => raise(s"A selection of field ${fieldName(a)} has arguments, while another doesn't.", Some(b.caret))
            case (Some(aa), Some(ba)) => compareArguments(fieldName(a), aa, ba, Some(b.caret))
          }

          val nameSameF =
            if (a.name === b.name) F.unit
            else {
              raise(
                s"Field $aIn and $bIn must have the same name (not alias) when they are merged.",
                Some(a.caret)
              )
            }

          appendMessage(s"They were merged since $whyMerge") {
            argsF &> nameSameF
          }
        } else F.unit

        // 1. in FieldsInSetCanMerge
        val shapeCheckF = checkSimplifiedTypeShape(a.tpe, b.tpe, a.caret)

        thoroughCheckF &> shapeCheckF

      }

      override def compareArguments(name: String, aa: QueryAst.Arguments, ba: QueryAst.Arguments, caret: Option[C]): F[Unit] = {
        def checkUniqueness(x: QA.Arguments): F[Map[String, QA.Argument]] =
          x.nel.toList
            .groupBy(_.name)
            .toList
            .parTraverse {
              case (k, v :: Nil) => F.pure(k -> v)
              case (k, _) =>
                raise[(String, QA.Argument)](s"Argument '$k' of field $name was not unique.", caret)
            }
            .map(_.toMap)

        (checkUniqueness(aa), checkUniqueness(ba)).parTupled.flatMap { case (amap, bmap) =>
          (amap align bmap).toList.parTraverse_[F, Unit] {
            case (k, Ior.Left(_)) =>
              raise(s"Field $name is already selected with argument '$k', but no argument was given here.", caret)
            case (k, Ior.Right(_)) =>
              raise(s"Field $name is already selected without argument '$k', but an argument was given here.", caret)
            case (k, Ior.Both(l, r)) => ambientField(k)(compareValues(l.value, r.value, caret))
          }
        }
      }

      override def compareValues(av: V[AnyValue], bv: V[AnyValue], caret: Option[C]): F[Unit] = {
        (av, bv) match {
          case (V.VariableValue(avv), V.VariableValue(bvv)) =>
            if (avv === bvv) F.unit
            else raise(s"Variable '$avv' and '$bvv' are not equal.", caret)
          case (V.IntValue(ai), V.IntValue(bi)) =>
            if (ai === bi) F.unit
            else raise(s"Int '$ai' and '$bi' are not equal.", caret)
          case (V.FloatValue(af), V.FloatValue(bf)) =>
            if (af === bf) F.unit
            else raise(s"Float '$af' and '$bf' are not equal.", caret)
          case (V.StringValue(as), V.StringValue(bs)) =>
            if (as === bs) F.unit
            else raise(s"String '$as' and '$bs' are not equal.", caret)
          case (V.BooleanValue(ab), V.BooleanValue(bb)) =>
            if (ab === bb) F.unit
            else raise(s"Boolean '$ab' and '$bb' are not equal.", caret)
          case (V.EnumValue(ae), V.EnumValue(be)) =>
            if (ae === be) F.unit
            else raise(s"Enum '$ae' and '$be' are not equal.", caret)
          case (V.NullValue(), V.NullValue()) => F.unit
          case (V.ListValue(al), V.ListValue(bl)) =>
            if (al.length === bl.length) {
              al.zip(bl).zipWithIndex.parTraverse_ { case ((a, b), i) => ambientIndex(i)(compareValues(a, b, caret)) }
            } else
              raise(s"Lists are not af same size. Found list of length ${al.length} versus list of length ${bl.length}.", caret)
          case (V.ObjectValue(ao), V.ObjectValue(bo)) =>
            if (ao.size =!= bo.size)
              raise(
                s"Objects are not af same size. Found object of length ${ao.size} versus object of length ${bo.size}.",
                caret
              )
            else {
              def checkUniqueness(xs: List[(String, V[AnyValue])]) =
                xs.groupMap { case (k, _) => k } { case (_, v) => v }
                  .toList
                  .parTraverse {
                    case (k, v :: Nil) => F.pure(k -> v)
                    case (k, _)        => raise[(String, V[AnyValue])](s"Key '$k' is not unique in object.", caret)
                  }
                  .map(_.toMap)

              (checkUniqueness(ao), checkUniqueness(bo)).parTupled.flatMap { case (amap, bmap) =>
                // TODO test that verifies that order does not matter
                (amap align bmap).toList.parTraverse_[F, Unit] {
                  case (k, Ior.Left(_))    => raise(s"Key '$k' is missing in object.", caret)
                  case (k, Ior.Right(_))   => raise(s"Key '$k' is missing in object.", caret)
                  case (k, Ior.Both(l, r)) => ambientField(k)(compareValues(l, r, caret))
                }
              }
            }
          case _ => raise(s"Values are not same type, got ${pValueName(av)} and ${pValueName(bv)}.", caret)
        }
      }
    }
  }
}
