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
package org.neo4j.cypher.internal.logical.plans

import org.neo4j.cypher.internal.util.attribution.IdGen

/**
 * For every row in left, set that row as the argument, and apply to right. Produce left row, and set 'idName' =
 * true if right contains at least one row.
 *
 * for ( leftRow <- left ) {
 *   right.setArgument( leftRow )
 *   leftRow('idName') = right.nonEmpty
 *   produce leftRow
 * }
 */
case class LetSemiApply(override val left: LogicalPlan, override val right: LogicalPlan, idName: String)(implicit
idGen: IdGen) extends AbstractLetSemiApply(left, right, idName)(idGen) {
  override def withLhs(newLHS: LogicalPlan)(idGen: IdGen): LogicalBinaryPlan = copy(left = newLHS)(idGen)
  override def withRhs(newRHS: LogicalPlan)(idGen: IdGen): LogicalBinaryPlan = copy(right = newRHS)(idGen)
}

/**
 * For every row in left, set that row as the argument, and apply to right. Produce left row, and set 'idName' =
 * true if right contains no rows.
 *
 * for ( leftRow <- left ) {
 *   right.setArgument( leftRow )
 *   leftRow('idName') = right.isEmpty
 *   produce leftRow
 * }
 */
case class LetAntiSemiApply(override val left: LogicalPlan, override val right: LogicalPlan, idName: String)(implicit
idGen: IdGen) extends AbstractLetSemiApply(left, right, idName)(idGen) {
  override def withLhs(newLHS: LogicalPlan)(idGen: IdGen): LogicalBinaryPlan = copy(left = newLHS)(idGen)
  override def withRhs(newRHS: LogicalPlan)(idGen: IdGen): LogicalBinaryPlan = copy(right = newRHS)(idGen)
}

abstract class AbstractLetSemiApply(left: LogicalPlan, right: LogicalPlan, idName: String)(implicit idGen: IdGen)
    extends LogicalBinaryPlan(idGen) with ApplyPlan with SingleFromRightLogicalPlan {

  override val availableSymbols: Set[String] = left.availableSymbols + idName
}
