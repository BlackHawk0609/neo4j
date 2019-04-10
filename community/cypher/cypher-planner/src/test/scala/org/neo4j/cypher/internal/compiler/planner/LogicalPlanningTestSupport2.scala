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
package org.neo4j.cypher.internal.compiler.planner

import org.neo4j.csv.reader.Configuration
import org.neo4j.cypher.internal.compiler.phases._
import org.neo4j.cypher.internal.compiler.planner.logical.Metrics.{CardinalityModel, QueryGraphCardinalityModel, QueryGraphSolverInput}
import org.neo4j.cypher.internal.compiler.planner.logical._
import org.neo4j.cypher.internal.compiler.planner.logical.cardinality.QueryGraphCardinalityModel
import org.neo4j.cypher.internal.compiler.planner.logical.idp._
import org.neo4j.cypher.internal.compiler.planner.logical.plans.rewriter.unnestApply
import org.neo4j.cypher.internal.compiler.planner.logical.steps.{LogicalPlanProducer, devNullListener, replacePropertyLookupsWithVariables}
import org.neo4j.cypher.internal.compiler.test_helpers.ContextHelper
import org.neo4j.cypher.internal.compiler.{CypherPlannerConfiguration, NotImplementedPlanContext, StatsDivergenceCalculator, SyntaxExceptionCreator}
import org.neo4j.cypher.internal.ir.{PeriodicCommit, PlannerQuery, ProvidedOrder, QueryGraph}
import org.neo4j.cypher.internal.logical.plans._
import org.neo4j.cypher.internal.planner.spi.IndexDescriptor.{OrderCapability, ValueCapability}
import org.neo4j.cypher.internal.planner.spi.PlanningAttributes.{Cardinalities, ProvidedOrders, Solveds}
import org.neo4j.cypher.internal.planner.spi._
import org.neo4j.cypher.internal.v4_0.ast._
import org.neo4j.cypher.internal.v4_0.ast.semantics.{SemanticFeature, SemanticTable}
import org.neo4j.cypher.internal.v4_0.expressions.PatternExpression
import org.neo4j.cypher.internal.v4_0.frontend.phases._
import org.neo4j.cypher.internal.v4_0.parser.CypherParser
import org.neo4j.cypher.internal.v4_0.rewriting.RewriterStepSequencer.newPlain
import org.neo4j.cypher.internal.v4_0.rewriting.rewriters._
import org.neo4j.cypher.internal.v4_0.rewriting.{Deprecations, RewriterStepSequencer, ValidatingRewriterStepSequencer}
import org.neo4j.cypher.internal.v4_0.util.attribution.{Attribute, Attributes}
import org.neo4j.cypher.internal.v4_0.util.helpers.fixedPoint
import org.neo4j.cypher.internal.v4_0.util.test_helpers.{CypherFunSuite, CypherTestSupport}
import org.neo4j.cypher.internal.v4_0.util.{Cardinality, Cost, PropertyKeyId}
import org.neo4j.helpers.collection.Visitable
import org.neo4j.kernel.impl.util.dbstructure.DbStructureVisitor
import org.scalatest.matchers.{BeMatcher, MatchResult}

import scala.language.reflectiveCalls
import scala.reflect.ClassTag

trait LogicalPlanningTestSupport2 extends CypherTestSupport with AstConstructionTestSupport with LogicalPlanConstructionTestSupport {
  self: CypherFunSuite =>

  val parser = new CypherParser
  val rewriterSequencer: String => ValidatingRewriterStepSequencer = RewriterStepSequencer.newValidating
  val innerVariableNamer = new GeneratingNamer
  var astRewriter = new ASTRewriter(rewriterSequencer, literalExtraction = Never, getDegreeRewriting = true, innerVariableNamer = innerVariableNamer)
  final var planner = QueryPlanner()
  var queryGraphSolver: QueryGraphSolver = new IDPQueryGraphSolver(SingleComponentPlanner(mock[IDPQueryGraphSolverMonitor]), cartesianProductsOrValueJoins, mock[IDPQueryGraphSolverMonitor])
  val cypherCompilerConfig = CypherPlannerConfiguration(
    queryCacheSize = 100,
    statsDivergenceCalculator = StatsDivergenceCalculator.divergenceNoDecayCalculator(0.5, 1000),
    useErrorsOverWarnings = false,
    idpMaxTableSize = DefaultIDPSolverConfig.maxTableSize,
    idpIterationDuration = DefaultIDPSolverConfig.iterationDurationLimit,
    errorIfShortestPathFallbackUsedAtRuntime = false,
    errorIfShortestPathHasCommonNodesAtRuntime = true,
    legacyCsvQuoteEscaping = false,
    csvBufferSize = Configuration.DEFAULT_BUFFER_SIZE_4MB,
    nonIndexedLabelWarningThreshold = 10000,
    planWithMinimumCardinalityEstimates = true
  )
  val realConfig = RealLogicalPlanningConfiguration(cypherCompilerConfig)

  implicit class LogicalPlanningEnvironment[C <: LogicalPlanningConfiguration](config: C) {
    lazy val semanticTable: SemanticTable = config.updateSemanticTableWithTokens(SemanticTable())

    def metricsFactory: MetricsFactory = new MetricsFactory {
      def newCostModel(ignore: CypherPlannerConfiguration): (LogicalPlan, QueryGraphSolverInput, Cardinalities) => Cost =
        (plan: LogicalPlan, input: QueryGraphSolverInput, cardinalities: Cardinalities) => config.costModel()((plan, input, cardinalities))

      def newCardinalityEstimator(queryGraphCardinalityModel: QueryGraphCardinalityModel, evaluator: ExpressionEvaluator): CardinalityModel =
        config.cardinalityModel(queryGraphCardinalityModel, mock[ExpressionEvaluator])

      def newQueryGraphCardinalityModel(statistics: GraphStatistics): QueryGraphCardinalityModel = QueryGraphCardinalityModel.default(statistics)
    }

    def table = Map.empty[PatternExpression, QueryGraph]

    def planContext: NotImplementedPlanContext = new NotImplementedPlanContext {
      override def statistics: InstrumentedGraphStatistics = InstrumentedGraphStatistics(
        config.graphStatistics,
        new MutableGraphStatisticsSnapshot())

      override def indexesGetForLabel(labelId: Int): Iterator[IndexDescriptor] = {
        val label = config.labelsById(labelId)
        config.indexes.collect {
          case (indexDef, indexType) if indexDef.label == label =>
            newIndexDescriptor(indexDef, config.indexes(indexDef))
        }.iterator
      }

      override def uniqueIndexesGetForLabel(labelId: Int): Iterator[IndexDescriptor] = {
        val label = config.labelsById(labelId)
        config.indexes.collect {
          case (indexDef, indexType) if indexType.isUnique && indexDef.label == label =>
            newIndexDescriptor(indexDef, config.indexes(indexDef))
        }.iterator
      }

      override def getPropertiesWithExistenceConstraint(labelName: String): Set[String] = {
        config.constraints.filter(p => p._1 == labelName).flatMap(p => p._2)
      }

      private def newIndexDescriptor(indexDef: IndexDef, indexType: IndexType) = {
        // Our fake index either can always or never return property values
        val canGetValue = if (indexType.withValues) CanGetValue else DoNotGetValue
        val valueCapability: ValueCapability = _ => indexDef.propertyKeys.map(_ => canGetValue)
        val orderCapability: OrderCapability = _ => indexType.withOrdering
        IndexDescriptor(
          semanticTable.resolvedLabelNames(indexDef.label),
          indexDef.propertyKeys.map(semanticTable.resolvedPropertyKeyNames(_)),
          valueCapability = valueCapability,
          orderCapability = orderCapability,
          isUnique = indexType.isUnique
        )
      }

      override def procedureSignature(name: QualifiedName): ProcedureSignature = {
        config.procedureSignatures.find(_.name == name).get
      }

      override def indexExistsForLabel(labelId: Int): Boolean = {
        val labelName = config.labelsById(labelId)
        config.indexes.keys.exists(_.label == labelName)
      }

      override def indexExistsForLabelAndProperties(labelName: String, propertyKey: Seq[String]): Boolean =
        config.indexes.contains(IndexDef(labelName, propertyKey))

      override def indexGetForLabelAndProperties(labelName: String, propertyKeys: Seq[String]): Option[IndexDescriptor] = {
        val indexDef = IndexDef(labelName, propertyKeys)
        config.indexes.get(indexDef).map(indexType => newIndexDescriptor(indexDef, indexType))
      }

      override def getOptPropertyKeyId(propertyKeyName: String): Option[Int] =
        semanticTable.resolvedPropertyKeyNames.get(propertyKeyName).map(_.id)

      override def getOptLabelId(labelName: String): Option[Int] =
        semanticTable.resolvedLabelNames.get(labelName).map(_.id)

      override def getOptRelTypeId(relType: String): Option[Int] =
        semanticTable.resolvedRelTypeNames.get(relType).map(_.id)
    }

    def pipeLine(): Transformer[PlannerContext, BaseState, LogicalPlanState] =
      Parsing andThen
      PreparatoryRewriting(Deprecations.V1) andThen
      SemanticAnalysis(warn = true, SemanticFeature.Cypher9Comparability) andThen
      AstRewriting(newPlain, literalExtraction = Never, innerVariableNamer = innerVariableNamer) andThen
      RewriteProcedureCalls andThen
      Namespacer andThen
      transitiveClosure andThen
      rewriteEqualityToInPredicate andThen
      CNFNormalizer andThen
      LateAstRewriting andThen
      ResolveTokens andThen
      CreatePlannerQuery andThen
      OptionalMatchRemover andThen
      QueryPlanner().adds(CompilationContains[LogicalPlan]) andThen
      replacePropertyLookupsWithVariables andThen
      Do[PlannerContext, LogicalPlanState, LogicalPlanState]((state, context) => removeApply(state, context, state.planningAttributes.solveds, Attributes(idGen, state.planningAttributes.cardinalities)))


    private def removeApply(input: LogicalPlanState, context: PlannerContext, solveds: Solveds, attributes: Attributes[LogicalPlan]): LogicalPlanState = {
      val newPlan = input.logicalPlan.endoRewrite(fixedPoint(unnestApply(solveds, attributes)))
      input.copy(maybeLogicalPlan = Some(newPlan))
    }

    def getLogicalPlanFor(queryString: String): (Option[PeriodicCommit], LogicalPlan, SemanticTable, Solveds, Cardinalities) = {
      val mkException = new SyntaxExceptionCreator(queryString, Some(pos))
      val metrics = metricsFactory.newMetrics(planContext.statistics, mock[ExpressionEvaluator], cypherCompilerConfig)
      def context = ContextHelper.create(planContext = planContext,
        exceptionCreator = mkException,
        queryGraphSolver = queryGraphSolver,
        metrics = metrics,
        config = cypherCompilerConfig,
        logicalPlanIdGen = idGen
      )

      val state = InitialState(queryString, None, IDPPlannerName)
      val output = pipeLine().transform(state, context)
      val logicalPlan = output.logicalPlan.asInstanceOf[ProduceResult].source
      (output.periodicCommit, logicalPlan, output.semanticTable(), output.planningAttributes.solveds, output.planningAttributes.cardinalities)
    }

    def estimate(qg: QueryGraph, input: QueryGraphSolverInput = QueryGraphSolverInput.empty): Cardinality =
      metricsFactory.
        newMetrics(config.graphStatistics, mock[ExpressionEvaluator], cypherCompilerConfig).
        queryGraphCardinalityModel(qg, input, semanticTable)

    def withLogicalPlanningContext[T](f: (C, LogicalPlanningContext) => T): T = {
      val metrics = metricsFactory.newMetrics(config.graphStatistics, mock[ExpressionEvaluator], cypherCompilerConfig)
      val solveds = new Solveds
      val cardinalities = new Cardinalities
      val providedOrders = new ProvidedOrders
      val planningAttributes = new PlanningAttributes(solveds, cardinalities, providedOrders)
      val logicalPlanProducer = LogicalPlanProducer(metrics.cardinality, planningAttributes, idGen)
      val ctx = LogicalPlanningContext(
        planContext = planContext,
        logicalPlanProducer = logicalPlanProducer,
        metrics = metrics,
        semanticTable = semanticTable,
        strategy = queryGraphSolver,
        input = QueryGraphSolverInput.empty,
        notificationLogger = devNullLogger,
        costComparisonListener = devNullListener,
        planningAttributes = planningAttributes,
        innerVariableNamer = innerVariableNamer
      )
      f(config, ctx)
    }


    def withLogicalPlanningContextWithFakeAttributes[T](f: (C, LogicalPlanningContext) => T): T = {
      val metrics = metricsFactory.newMetrics(config.graphStatistics, mock[ExpressionEvaluator], cypherCompilerConfig)
      val solveds = new StubSolveds
      val cardinalities = new StubCardinalities
      val providedOrders = new StubProvidedOrders
      val planningAttributes = new PlanningAttributes(solveds, cardinalities, providedOrders)
      val logicalPlanProducer = LogicalPlanProducer(metrics.cardinality, planningAttributes, idGen)
      val ctx = LogicalPlanningContext(
        planContext = planContext,
        logicalPlanProducer = logicalPlanProducer,
        metrics = metrics,
        semanticTable = semanticTable,
        strategy = queryGraphSolver,
        input = QueryGraphSolverInput.empty,
        notificationLogger = devNullLogger,
        costComparisonListener = devNullListener,
        planningAttributes = planningAttributes,
        innerVariableNamer = innerVariableNamer
      )
      f(config, ctx)
    }
  }

  def set[T](plan: LogicalPlan, attribute: Attribute[LogicalPlan, T], t: T): LogicalPlan = {
    attribute.set(plan.id, t)
    plan
  }

  def setC(plan: LogicalPlan, cardinalities: Cardinalities, c: Cardinality): LogicalPlan = {
    cardinalities.set(plan.id, c)
    plan
  }

  def fakeLogicalPlanFor(id: String*): FakePlan = FakePlan(id.toSet)

  def fakeLogicalPlanFor(planningAttributes: PlanningAttributes, id: String*): FakePlan = {
    val res = FakePlan(id.toSet)
    planningAttributes.solveds.set(res.id, PlannerQuery.empty)
    planningAttributes.cardinalities.set(res.id, 0.0)
    planningAttributes.providedOrders.set(res.id, ProvidedOrder.empty)
    res
  }

  def planFor(queryString: String): (Option[PeriodicCommit], LogicalPlan, SemanticTable, Solveds, Cardinalities) =
    new given().getLogicalPlanFor(queryString)

  class given extends StubbedLogicalPlanningConfiguration(realConfig)

  class givenPlanWithMinimumCardinalityEnabled
    extends StubbedLogicalPlanningConfiguration(RealLogicalPlanningConfiguration(cypherCompilerConfig.copy(planWithMinimumCardinalityEstimates = true)))

  class fromDbStructure(dbStructure: Visitable[DbStructureVisitor])
    extends DelegatingLogicalPlanningConfiguration(DbStructureLogicalPlanningConfiguration(cypherCompilerConfig)(dbStructure))

  implicit def propertyKeyId(label: String)(implicit semanticTable: SemanticTable): PropertyKeyId =
    semanticTable.resolvedPropertyKeyNames(label)

  def using[T <: LogicalPlan](implicit tag: ClassTag[T]): BeMatcher[LogicalPlan] = new BeMatcher[LogicalPlan] {
    import org.neo4j.cypher.internal.v4_0.util.Foldable._
    override def apply(actual: LogicalPlan): MatchResult = {
      val matches = actual.treeFold(false) {
        case lp if tag.runtimeClass.isInstance(lp) => acc => (true, None)
      }
      MatchResult(
        matches = matches,
        rawFailureMessage = s"Plan should use ${tag.runtimeClass.getSimpleName}",
        rawNegatedFailureMessage = s"Plan should not use ${tag.runtimeClass.getSimpleName}")
    }
  }
}
