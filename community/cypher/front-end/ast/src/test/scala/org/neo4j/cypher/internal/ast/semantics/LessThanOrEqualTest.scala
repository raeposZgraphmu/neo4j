/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.cypher.internal.ast.semantics

import org.neo4j.cypher.internal.expressions
import org.neo4j.cypher.internal.util.DummyPosition
import org.neo4j.cypher.internal.util.symbols.CTAny
import org.neo4j.cypher.internal.util.symbols.CTBoolean
import org.neo4j.cypher.internal.util.symbols.CTDate
import org.neo4j.cypher.internal.util.symbols.CTDateTime
import org.neo4j.cypher.internal.util.symbols.CTDuration
import org.neo4j.cypher.internal.util.symbols.CTFloat
import org.neo4j.cypher.internal.util.symbols.CTGeometry
import org.neo4j.cypher.internal.util.symbols.CTInteger
import org.neo4j.cypher.internal.util.symbols.CTList
import org.neo4j.cypher.internal.util.symbols.CTLocalDateTime
import org.neo4j.cypher.internal.util.symbols.CTLocalTime
import org.neo4j.cypher.internal.util.symbols.CTMap
import org.neo4j.cypher.internal.util.symbols.CTNode
import org.neo4j.cypher.internal.util.symbols.CTNumber
import org.neo4j.cypher.internal.util.symbols.CTPath
import org.neo4j.cypher.internal.util.symbols.CTPoint
import org.neo4j.cypher.internal.util.symbols.CTRelationship
import org.neo4j.cypher.internal.util.symbols.CTString
import org.neo4j.cypher.internal.util.symbols.CTTime

class LessThanOrEqualTest extends InfixExpressionTestBase(expressions.LessThanOrEqual(_, _)(DummyPosition(0))) {

  test("should support comparing integers") {
    testValidTypes(CTInteger, CTInteger)(CTBoolean)
  }

  test("should support comparing doubles") {
    testValidTypes(CTFloat, CTFloat)(CTBoolean)
  }

  test("should support comparing strings") {
    testValidTypes(CTString, CTString)(CTBoolean)
  }

  test("should support comparing points") {
    testValidTypes(CTPoint, CTPoint)(CTBoolean)
  }

  test("should support comparing temporals") {
    testValidTypes(CTDate, CTDate)(CTBoolean)
    testValidTypes(CTTime, CTTime)(CTBoolean)
    testValidTypes(CTLocalTime, CTLocalTime)(CTBoolean)
    testValidTypes(CTDateTime, CTDateTime)(CTBoolean)
    testValidTypes(CTLocalDateTime, CTLocalDateTime)(CTBoolean)
  }

  test("should return error if invalid argument types") {
    testInvalidApplication(CTNode, CTInteger)(
      "Type mismatch: expected Float, Integer, Point, String, Date, Time, LocalTime, LocalDateTime or DateTime but was Node")
    testInvalidApplication(CTInteger, CTNode)("Type mismatch: expected Float or Integer but was Node")
    testInvalidApplication(CTDuration, CTDuration)(
      "Type mismatch: expected Float, Integer, Point, String, Date, Time, LocalTime, LocalDateTime or DateTime but was Duration")
  }

  test("should support comparing all types with Cypher 9 comparison semantics") {
    val types = List(CTList(CTAny), CTInteger, CTFloat, CTNumber, CTNode, CTPath, CTRelationship, CTMap, CTPoint,
                     CTDate, CTDuration, CTBoolean, CTString, CTDateTime, CTGeometry, CTLocalDateTime, CTLocalTime,
                     CTTime)

    types.foreach { t1 =>
      types.foreach { t2 =>
        testValidTypes(t1, t2, useCypher9ComparisonSemantics = true)(CTBoolean)
      }
    }
  }
}
