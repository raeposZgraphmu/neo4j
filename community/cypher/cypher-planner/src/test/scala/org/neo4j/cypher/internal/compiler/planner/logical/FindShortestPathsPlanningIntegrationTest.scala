/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical

import org.neo4j.cypher.internal.ast.AstConstructionTestSupport
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningIntegrationTestSupport
import org.neo4j.cypher.internal.expressions.MultiRelationshipPathStep
import org.neo4j.cypher.internal.expressions.NilPathStep
import org.neo4j.cypher.internal.expressions.NodePathStep
import org.neo4j.cypher.internal.expressions.PathExpression
import org.neo4j.cypher.internal.expressions.SemanticDirection
import org.neo4j.cypher.internal.expressions.SemanticDirection.BOTH
import org.neo4j.cypher.internal.expressions.SemanticDirection.OUTGOING
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.logical.builder.AbstractLogicalPlanBuilder.Predicate
import org.neo4j.cypher.internal.logical.plans.Ascending
import org.neo4j.cypher.internal.logical.plans.ExpandInto
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class FindShortestPathsPlanningIntegrationTest extends CypherFunSuite with LogicalPlanningIntegrationTestSupport
    with AstConstructionTestSupport {

  test("finds shortest paths") {
    val cfg = plannerBuilder().setAllNodesCardinality(100).build()
    val plan = cfg.plan("MATCH (a), (b), shortestPath((a)-[r]->(b)) RETURN b").stripProduceResults
    plan shouldEqual cfg.subPlanBuilder()
      .shortestPath("(a)-[r*1..1]->(b)", pathName = Some("anon_0"))
      .cartesianProduct()
      .|.allNodeScan("b")
      .allNodeScan("a")
      .build()
  }

  test("find shortest path with length predicate and WITH should not plan fallback") {
    val cfg = plannerBuilder().setAllNodesCardinality(100).build()
    val plan =
      cfg.plan("MATCH (a), (b), p = shortestPath((a)-[r]->(b)) WITH p WHERE length(p) > 1 RETURN p").stripProduceResults
    plan shouldEqual cfg.subPlanBuilder()
      .filter("length(p) > 1")
      .shortestPath("(a)-[r*1..1]->(b)", pathName = Some("p"))
      .cartesianProduct()
      .|.allNodeScan("b")
      .allNodeScan("a")
      .build()
  }

  test("finds all shortest paths") {
    val cfg = plannerBuilder().setAllNodesCardinality(100).build()
    val plan = cfg.plan("MATCH (a), (b), allShortestPaths((a)-[r]->(b)) RETURN b").stripProduceResults
    plan shouldEqual cfg.subPlanBuilder()
      .shortestPath("(a)-[r*1..1]->(b)", pathName = Some("anon_0"), all = true)
      .cartesianProduct()
      .|.allNodeScan("b")
      .allNodeScan("a")
      .build()
  }

  test("find shortest paths on top of hash joins") {
    val cfg = plannerBuilder()
      .setAllNodesCardinality(10000)
      .setLabelCardinality("X", 100)
      .setRelationshipCardinality("()-[]->()", 99999)
      .setRelationshipCardinality("()-[]->(:X)", 100)
      .build()

    val plan =
      cfg.plan("MATCH (a:X)<-[r1]-(b)-[r2]->(c:X), p = shortestPath((a)-[r]->(c)) RETURN p").stripProduceResults
    plan shouldEqual cfg.subPlanBuilder()
      .shortestPath("(a)-[r*1..1]->(c)", pathName = Some("p"))
      .filter("not r2 = r1")
      .nodeHashJoin("b")
      .|.expandAll("(c)<-[r2]-(b)")
      .|.nodeByLabelScan("c", "X")
      .expandAll("(a)<-[r1]-(b)")
      .nodeByLabelScan("a", "X")
      .build()
  }

  test("Inline predicates in fallback plan of var expand during shortest path") {
    val cfg = plannerBuilder()
      .setAllNodesCardinality(100)
      .setRelationshipCardinality("()-[]->()", 99999)
      .build()
    val query =
      """
        |MATCH p=shortestPath((a)-[*]-(b))
        |WHERE all(r IN relationships(p) WHERE r.prop = 10)
        |AND length(p) > 4
        |RETURN p
        |""".stripMargin
    val plan = cfg.plan(query).stripProduceResults
    val expected = cfg.subPlanBuilder()
      .antiConditionalApply("p")
      .|.top(Seq(Ascending("anon_1")), 1)
      .|.projection("length(p) AS anon_1")
      .|.filter("length(p) > 4")
      .|.projection(Map("p" -> PathExpression(NodePathStep(
        varFor("a"),
        MultiRelationshipPathStep(varFor("anon_0"), BOTH, Some(varFor("b")), NilPathStep()(pos))(pos)
      )(pos))(pos)))
      .|.expand(
        "(a)-[anon_0*1..]-(b)",
        expandMode = ExpandInto,
        relationshipPredicates = Seq(Predicate("r", "cacheRFromStore[r.prop] = 10"))
      )
      .|.argument("a", "b")
      .apply()
      .|.optional("a", "b")
      .|.shortestPath(
        "(a)-[anon_0*1..]-(b)",
        pathName = Some("p"),
        relationshipPredicates = Seq(Predicate("r", "cacheRFromStore[r.prop] = 10")),
        pathPredicates = Seq("length(p) > 4"),
        withFallback = true
      )
      .|.argument("a", "b")
      .cartesianProduct()
      .|.allNodeScan("b")
      .allNodeScan("a")
      .build()
    plan shouldEqual expected
  }

  test("finds shortest path do fallback") {
    val cfg = plannerBuilder()
      .setAllNodesCardinality(100)
      .setRelationshipCardinality("()-[]-()", 100)
      .build()
    val plan =
      cfg.plan("MATCH (a), (b), p=shortestPath((a)-[r*]->(b)) WHERE length(p) > 1 RETURN b").stripProduceResults
    plan shouldEqual cfg.subPlanBuilder()
      .antiConditionalApply("p")
      .|.top(Seq(Ascending("anon_0")), 1)
      .|.projection("length(p) AS anon_0")
      .|.filter("length(p) > 1")
      .|.projection(
        Map("p" ->
          PathExpression(
            NodePathStep(
              varFor("a"),
              multiRelationshipPathStep("r", OUTGOING, "b")
            )(pos)
          )(pos))
      )
      .|.expand("(a)-[r*]->(b)", expandMode = ExpandInto, projectedDir = OUTGOING)
      .|.argument("a", "b")
      .apply()
      .|.optional("a", "b")
      .|.shortestPath("(a)-[r*]->(b)", Some("p"), pathPredicates = Seq("length(p) > 1"), withFallback = true)
      .|.argument("a", "b")
      .cartesianProduct()
      .|.allNodeScan("b")
      .allNodeScan("a")
      .build()
  }

  test("finds shortest path do fallback with per step relationship predicates from relationship list") {
    val cfg = plannerBuilder()
      .setAllNodesCardinality(100)
      .setRelationshipCardinality("()-[]-()", 100)
      .build()
    val plan =
      cfg.plan(
        """
          |MATCH (a), (b), p=shortestPath((a)-[rs*]->(b))
          |WHERE length(p) > 1
          |AND all(r IN rs WHERE r.prop > 1)
          |RETURN b"""
          .stripMargin
      ).stripProduceResults
    plan shouldEqual cfg.subPlanBuilder()
      .antiConditionalApply("p")
      .|.top(Seq(Ascending("anon_0")), 1)
      .|.projection("length(p) AS anon_0")
      .|.filter("length(p) > 1")
      .|.projection(Map("p" -> outgoingPathExpression("a", "rs", "b")))
      .|.expand(
        "(a)-[rs*]->(b)",
        expandMode = ExpandInto,
        projectedDir = OUTGOING,
        relationshipPredicates = Seq(Predicate("r", "cacheRFromStore[r.prop] > 1"))
      )
      .|.argument("a", "b")
      .apply()
      .|.optional("a", "b")
      .|.shortestPath(
        "(a)-[rs*]->(b)",
        Some("p"),
        relationshipPredicates = Seq(Predicate("r", "cacheRFromStore[r.prop] > 1")),
        pathPredicates = Seq("length(p) > 1"),
        withFallback = true
      )
      .|.argument("a", "b")
      .cartesianProduct()
      .|.allNodeScan("b")
      .allNodeScan("a")
      .build()
  }

  test("finds shortest path do fallback with per step relationship predicates from path") {
    val cfg = plannerBuilder()
      .setAllNodesCardinality(100)
      .setRelationshipCardinality("()-[]-()", 100)
      .build()
    val plan =
      cfg.plan(
        """
          |MATCH (a), (b), p=shortestPath((a)-[rs*]->(b))
          |WHERE length(p) > 1
          |AND all(r IN relationships(p) WHERE r.prop > 1)
          |RETURN b"""
          .stripMargin
      ).stripProduceResults
    plan shouldEqual cfg.subPlanBuilder()
      .antiConditionalApply("p")
      .|.top(Seq(Ascending("anon_0")), 1)
      .|.projection("length(p) AS anon_0")
      .|.filter("length(p) > 1")
      .|.projection(Map("p" -> outgoingPathExpression("a", "rs", "b")))
      .|.expand(
        "(a)-[rs*]->(b)",
        expandMode = ExpandInto,
        nodePredicates = Seq(),
        relationshipPredicates = Seq(Predicate("r", "cacheRFromStore[r.prop] > 1")),
        projectedDir = OUTGOING
      )
      .|.argument("a", "b")
      .apply()
      .|.optional("a", "b")
      .|.shortestPath(
        "(a)-[rs*]->(b)",
        Some("p"),
        relationshipPredicates = Seq(Predicate("r", "cacheRFromStore[r.prop] > 1")),
        pathPredicates = Seq("length(p) > 1"),
        withFallback = true
      )
      .|.argument("a", "b")
      .cartesianProduct()
      .|.allNodeScan("b")
      .allNodeScan("a")
      .build()
  }

  test("finds shortest path do fallback with per step node predicates from path") {
    val cfg = plannerBuilder()
      .setAllNodesCardinality(100)
      .setRelationshipCardinality("()-[]-()", 100)
      .build()
    val plan =
      cfg.plan(
        """
          |MATCH (a), (b), p=shortestPath((a)-[rs*]->(b))
          |WHERE length(p) > 1
          |AND all(n IN nodes(p) WHERE n.prop > 1)
          |RETURN b"""
          .stripMargin
      ).stripProduceResults
    plan shouldEqual cfg.subPlanBuilder()
      .antiConditionalApply("p")
      .|.top(Seq(Ascending("anon_0")), 1)
      .|.projection("length(p) AS anon_0")
      .|.filter("length(p) > 1")
      .|.projection(Map("p" -> outgoingPathExpression("a", "rs", "b")))
      .|.expand(
        "(a)-[rs*]->(b)",
        expandMode = ExpandInto,
        nodePredicates = Seq(Predicate("n", "cacheNFromStore[n.prop] > 1")),
        projectedDir = OUTGOING
      )
      .|.argument("a", "b")
      .apply()
      .|.optional("a", "b")
      .|.shortestPath(
        "(a)-[rs*]->(b)",
        Some("p"),
        nodePredicates = Seq(Predicate("n", "cacheNFromStore[n.prop] > 1")),
        pathPredicates = Seq("length(p) > 1"),
        withFallback = true
      )
      .|.argument("a", "b")
      .cartesianProduct()
      .|.allNodeScan("b")
      .allNodeScan("a")
      .build()
  }

  private def varFor(name: String): Variable = Variable(name)(pos)

  private def outgoingPathExpression(fromNode: String, rels: String, toNode: String) = {
    PathExpression(
      NodePathStep(
        varFor(fromNode),
        multiRelationshipPathStep(rels, OUTGOING, toNode)
      )(pos)
    )(pos)
  }

  private def multiRelationshipPathStep(
    rel: String,
    dir: SemanticDirection,
    toNode: String
  ): MultiRelationshipPathStep =
    MultiRelationshipPathStep(varFor(rel), dir, Some(varFor(toNode)), NilPathStep()(pos))(pos)

}
