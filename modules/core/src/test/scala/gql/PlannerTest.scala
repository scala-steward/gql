package gql

import munit.CatsEffectSuite
import cats.implicits._
import scala.collection.immutable.TreeSet

class PlannerTest extends CatsEffectSuite {
  type Id = Int
  type Family = Int
  final case class Problem(
      families: List[List[Id]],
      arcs: Map[Id, List[Id]],
      costs: Map[Family, Int]
  ) {
    val reverseArcs: Map[Id, List[Id]] =
      arcs.toList
        .flatMap { case (parent, children) => children tupleRight parent }
        .groupMap { case (child, _) => child } { case (_, parent) => parent }
      
    val familyMap: Map[Id, Family] =
      families.zipWithIndex.flatMap { case (ids, family) => ids tupleRight family }.toMap

    val reverseFamilyMap: Map[Family, List[Id]] =
      familyMap.toList.groupMap { case (_, family) => family } { case (id, _) => id }
  }

  type EndTime = Int
  type StartTime = Int
  case class PlannerState(
      batches: Map[Family, TreeSet[StartTime]],
      lookup: Map[Id, EndTime]
  )

  test("blah") {
    var i = 0
    def solve(problem: Problem): LazyList[PlannerState] = {
      val roots: List[Id] = problem.families.flatten.filter(id => problem.reverseArcs.getOrElse(id, Nil).isEmpty)

      def findMaxEnd(id: Int): Int =
        problem.arcs.getOrElse(id, Nil).map(findMaxEnd).maxOption.getOrElse(0) + problem.costs(problem.familyMap(id))

      //val maxEnd = roots.map(findMaxEnd).max

      def round(id: Id, state: PlannerState): List[PlannerState] = {
        // One of three things can happen for every node
        // 1. It moves as far up as possible
        // 2. It moves up to the furthest batch

        // Furthest is the latest ending parent
        val furthest = problem.reverseArcs
          .getOrElse(id, Nil)
          .map(state.lookup)
          .maxOption
          .getOrElse(0)

        val family = problem.familyMap(id)
        val familyBatches = state.batches.get(family).getOrElse(TreeSet.empty[EndTime])
        val bestBatch = Some(furthest).filter(_ => familyBatches.contains(furthest)) orElse familyBatches.minAfter(furthest)

        // We can prune option1 if bestBatch == furthest
        val batchMove = bestBatch.map(b => state.copy(lookup = state.lookup + (id -> (b + problem.costs(family)))))

        lazy val furthestMove = state.copy(
          lookup = state.lookup + (id -> (furthest + problem.costs(family))),
          batches = state.batches + (family -> (familyBatches + furthest))
        )

        if (bestBatch.contains(furthest)) batchMove.toList
        else furthestMove :: batchMove.toList
      }

      def enumerateRounds(currentRoots: Set[Id], state: PlannerState): LazyList[PlannerState] = {
        if (currentRoots.isEmpty) LazyList(state)
        else
          LazyList.from(currentRoots).flatMap { id =>
            // we move id
            val newStates = round(id, state)
            i = i + newStates.size
            val freedChildren = problem.arcs.getOrElse(id, Nil)
            val newTop = (currentRoots - id) ++ freedChildren
            LazyList.from(newStates).flatMap(enumerateRounds(newTop, _))
          }
      }

      enumerateRounds(roots.toSet, PlannerState(Map.empty, Map.empty))
    }

    /*
Problem(
  n = 11,
  arcs = {
    (0,1),
    (0,2),
    (0,3),
    (0,4),

    (2,5),
    (5,8),

    (3,6),
    (3,7),

    (6,9),
    (7,10),
  },
  fam = [
    {0},
    {1},
    {2,6,8},
    {3,9},
    {5,7},
    {4,10},
  ],
  costs = [
    1,
    13,
    1,1,1,1
  ]
)
     */
    val names = Array(
      "r1",
      "c1",
      "a1",
      "b1",
      "h1",
      "p1",
      "a2",
      "p2",
      "a3",
      "b2",
      "h2"
    )
    val familyNames = Array(
      "r",
      "c",
      "a",
      "b",
      "p",
      "h"
    )
    val problem = Problem(
      families = List(
        List(0),
        List(1),
        List(2, 6, 8),
        List(3, 9),
        List(5, 7),
        List(4, 10)
      ),
      arcs = Map(
        0 -> List(1, 2, 3, 4),
        2 -> List(5),
        5 -> List(8),
        3 -> List(6, 7),
        6 -> List(9),
        7 -> List(10)
      ),
      costs = Map(
        0 -> 1,
        1 -> 13,
        2 -> 1,
        3 -> 1,
        4 -> 1,
        5 -> 1
      )
    )
    val plans = solve(problem)

    //println(s"${plans.size} plans")
    def savings(state: PlannerState) = {
      state.batches.toList.map { case (fam, times) =>
        val original = problem.families(fam).size
        original - times.size
      }.sum
    }
    val best = plans.maxBy(savings)
    val uniques = plans.distinctBy{ state =>
            val fams = state.batches.toList
            fams.flatMap{ case (fam, _) =>
              val famNodes = problem.reverseFamilyMap(fam)
              val timesForNodes: Map[EndTime,List[Id]] = famNodes.groupBy(state.lookup(_))
              timesForNodes.toList
            }.groupBy{ case (t, _) => t }.toList.sortBy{ case (t, _) => t }.map{ case (_, ids) => ids.sortBy{ case (t, _) => t }.map{ case (_, xs) => xs.sorted } }
          }

    def showPlan(state: PlannerState) = {
      s"""|batches
          |${state.batches.toList.map{ case (fam, times) => "  " + familyNames(fam) + " -> " + times.map(_.toString()).toList.foldSmash("{", ",", "}")}.mkString_("\n")}
          |times
          |${state.lookup.toList.map{ case (id, time) => "  " + names(id) + " -> " + time.toString() }.mkString_("\n")}""".stripMargin
    }
    println{
      s"""|savings
          |${savings(best)}
          |best:
          |${showPlan(best)}
          |$i rounds
          |#unique plans
          |${uniques.size}
          |unique plans:
          |${uniques.map(showPlan).mkString_("\n\nplan:\n")}
          |""".stripMargin
    }
    fail("die")
  }
}