/*
 * Copyright (c) 2002-2019 "Neo4j,"
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

import org.neo4j.cypher.internal.ir.{LazyMode, StrictnessMode}
import org.neo4j.cypher.internal.v4_0.expressions._
import org.neo4j.cypher.internal.v4_0.util.attribution.IdGen

abstract class ProceduralLogicalPlan(idGen: IdGen) extends LogicalPlan(idGen) {
  override def lhs: Option[LogicalPlan] = None

  override def rhs: Option[LogicalPlan] = None

  override val availableSymbols: Set[String] = Set.empty

  override def strictness: StrictnessMode = LazyMode

}

case class CreateNodeKeyConstraint(node: String, label: LabelName, props: Seq[Property], name: Option[String])(implicit idGen: IdGen) extends ProceduralLogicalPlan(idGen)
case class DropNodeKeyConstraint(label: LabelName, props: Seq[Property])(implicit idGen: IdGen) extends ProceduralLogicalPlan(idGen)

case class CreateUniquePropertyConstraint(node: String, label: LabelName, props: Seq[Property], name: Option[String])(implicit idGen: IdGen) extends ProceduralLogicalPlan(idGen)
case class DropUniquePropertyConstraint(label: LabelName, props: Seq[Property])(implicit idGen: IdGen) extends ProceduralLogicalPlan(idGen)

case class CreateNodePropertyExistenceConstraint(label: LabelName, prop: Property, name: Option[String])(implicit idGen: IdGen) extends ProceduralLogicalPlan(idGen)
case class DropNodePropertyExistenceConstraint(label: LabelName, prop: Property)(implicit idGen: IdGen) extends ProceduralLogicalPlan(idGen)

case class CreateRelationshipPropertyExistenceConstraint(typeName: RelTypeName, prop: Property, name: Option[String])(implicit idGen: IdGen) extends ProceduralLogicalPlan(idGen)
case class DropRelationshipPropertyExistenceConstraint(typeName: RelTypeName, prop: Property)(implicit idGen: IdGen) extends ProceduralLogicalPlan(idGen)

case class DropConstraintOnName(name: String)(implicit idGen: IdGen) extends ProceduralLogicalPlan(idGen)

case class CreateIndex(label: LabelName, propertyKeyNames: List[PropertyKeyName], name: Option[String])(implicit idGen: IdGen) extends ProceduralLogicalPlan(idGen)
case class DropIndex(label: LabelName, propertyKeyNames: List[PropertyKeyName])(implicit idGen: IdGen) extends ProceduralLogicalPlan(idGen)
case class DropIndexOnName(name: String)(implicit idGen: IdGen) extends ProceduralLogicalPlan(idGen)
