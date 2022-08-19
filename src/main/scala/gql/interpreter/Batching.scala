package gql.interpreter

import gql._
import gql.PreparedQuery._
import cats.data._
import cats.effect._
import cats.implicits._
import cats._

sealed trait StateSubmissionOutcome
final case class FinalSubmission(accumulatedInputs: Map[Int, List[NodeValue]]) extends StateSubmissionOutcome
case object NoState extends StateSubmissionOutcome
case object NotFinalSubmission extends StateSubmissionOutcome

final case class BatchExecutionState(remainingInputs: Set[Int], inputMap: Map[Int, List[NodeValue]])

final case class ExecutionDeps[F[_]](
    nodeMap: Map[Int, Optimizer.Node],
    dataFieldMap: Map[Int, PreparedDataField[F, Any, Any]],
    batches: List[NonEmptyList[Int]]
) {
  def batchExecutionState[F[_]](implicit F: Concurrent[F]): F[Map[Int, Ref[F, BatchExecutionState]]] =
    batches
      .flatTraverse { batch =>
        val l = batch.toList
        F.ref(BatchExecutionState(l.toSet, Map.empty[Int, List[NodeValue]])).map(s => l.map(_ -> s))
      }
      .map(_.toMap)
}
object ExecutionDeps {
  def planExecutionDeps[F[_]](rootSel: NonEmptyList[PreparedField[F, Any]], plan: NonEmptyList[Optimizer.Node]): ExecutionDeps[F] = {
    val flat = Optimizer.flattenNodeTree(plan)

    def unpackPrep(prep: Prepared[F, Any]): Eval[List[(Int, PreparedDataField[F, Any, Any])]] = Eval.defer {
      prep match {
        case PreparedLeaf(_, _) => Eval.now(Nil)
        case PreparedList(of)   => unpackPrep(of)
        case Selection(fields)  => flattenDataFieldMap(fields).map(_.toList)
      }
    }

    def flattenDataFieldMap(sel: NonEmptyList[PreparedField[F, Any]]): Eval[NonEmptyList[(Int, PreparedDataField[F, Any, Any])]] =
      Eval.defer {
        sel.flatTraverse { pf =>
          pf match {
            case df @ PreparedDataField(id, name, resolve, selection, batchName) =>
              val hd = id -> df
              unpackPrep(selection).map(tl => NonEmptyList(hd, tl))
            case PreparedFragField(_, _, selection) => flattenDataFieldMap(selection.fields)
          }
        }
      }

    val nodeMap: Map[Int, Optimizer.Node] = flat.toList.map(x => x.id -> x).toMap

    val dataFieldMap: Map[Int, PreparedDataField[F, Any, Any]] = flattenDataFieldMap(rootSel).value.toList.toMap

    val batches: List[NonEmptyList[Int]] =
      flat
        .groupBy(_.end)
        .toList
        .zipWithIndex
        .flatMap { case ((_, group), idx) =>
          group
            .groupBy(_.batchName)
            .filter { case (o, nodes) => nodes.size > 1 && o.isDefined }
            .toList
            .map { case (nodeType, nodes) => nodes.map(_.id) }
        }

    ExecutionDeps(nodeMap, dataFieldMap, batches)
  }
}
