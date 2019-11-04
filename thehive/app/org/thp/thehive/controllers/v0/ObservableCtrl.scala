package org.thp.thehive.controllers.v0

import scala.util.Success

import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, Json}
import play.api.mvc.{Action, AnyContent, Results}

import javax.inject.{Inject, Singleton}
import org.thp.scalligraph._
import org.thp.scalligraph.controllers._
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.query.{ParamQuery, PropertyUpdater, PublicProperty, Query}
import org.thp.scalligraph.steps.PagedResult
import org.thp.scalligraph.steps.StepsOps._
import org.thp.thehive.controllers.v0.Conversion._
import org.thp.thehive.dto.v0.InputObservable
import org.thp.thehive.models._
import org.thp.thehive.services._

@Singleton
class ObservableCtrl @Inject()(
    entryPoint: EntryPoint,
    db: Database,
    properties: Properties,
    observableSrv: ObservableSrv,
    observableTypeSrv: ObservableTypeSrv,
    caseSrv: CaseSrv,
    organisationSrv: OrganisationSrv
) extends QueryableCtrl
    with ObservableRenderer {

  lazy val logger                                           = Logger(getClass)
  override val entityName: String                           = "observable"
  override val publicProperties: List[PublicProperty[_, _]] = properties.observable ::: metaProperties[ObservableSteps]
  override val initialQuery: Query =
    Query.init[ObservableSteps]("listObservable", (graph, authContext) => organisationSrv.get(authContext.organisation)(graph).shares.observables)
  override val getQuery: ParamQuery[IdOrName] = Query.initWithParam[IdOrName, ObservableSteps](
    "getObservable",
    FieldsParser[IdOrName],
    (param, graph, authContext) => observableSrv.get(param.idOrName)(graph).visible(authContext)
  )
  override val pageQuery: ParamQuery[OutputParam] = Query.withParam[OutputParam, ObservableSteps, PagedResult[(RichObservable, JsObject)]](
    "page",
    FieldsParser[OutputParam], {
      case (OutputParam(from, to, withStats, _), observableSteps, authContext) =>
        observableSteps
          .richPage(from, to, withTotal = true) {
            case o if withStats =>
              o.richObservableWithCustomRenderer(observableStatsRenderer(authContext, db, observableSteps.graph))
            case o =>
              o.richObservable.map(_ -> JsObject.empty)
          }
    }
  )
  override val outputQuery: Query = Query.output[(RichObservable, JsObject)]()
  override val extraQueries: Seq[ParamQuery[_]] = Seq(
    Query[ObservableSteps, List[RichObservable]]("toList", (observableSteps, _) => observableSteps.richObservable.toList)
  )

  def create(caseId: String): Action[AnyContent] =
    entryPoint("create artifact")
      .extract("artifact", FieldsParser[InputObservable])
      .authTransaction(db) { implicit request => implicit graph =>
        val inputObservable: InputObservable = request.body("artifact")
        for {
          case0 <- caseSrv
            .get(caseId)
            .can(Permissions.manageObservable)
            .getOrFail()
          observableType <- observableTypeSrv.getOrFail(inputObservable.dataType)
          observablesWithData <- inputObservable
            .data
            .toTry(d => observableSrv.create(inputObservable.toObservable, observableType, d, inputObservable.tags, Nil))
          observableWithAttachment <- inputObservable
            .attachment
            .map(a => observableSrv.create(inputObservable.toObservable, observableType, a, inputObservable.tags, Nil))
            .flip
          createdObservables <- (observablesWithData ++ observableWithAttachment).toTry { richObservables =>
            caseSrv
              .addObservable(case0, richObservables)
              .map(_ => richObservables)
          }
        } yield Results.Created(Json.toJson(createdObservables.map(_.toJson)))
      }

  def get(observableId: String): Action[AnyContent] =
    entryPoint("get observable")
      .authRoTransaction(db) { implicit request => implicit graph =>
        observableSrv
          .getByIds(observableId)
          .visible
          .richObservable
          .getOrFail()
          .map { observable =>
            Results.Ok(observable.toJson)
          }
      }

  def update(observableId: String): Action[AnyContent] =
    entryPoint("update observable")
      .extract("observable", FieldsParser.update("observable", publicProperties))
      .authTransaction(db) { implicit request => implicit graph =>
        val propertyUpdaters: Seq[PropertyUpdater] = request.body("observable")
        observableSrv
          .update(
            _.getByIds(observableId).can(Permissions.manageObservable),
            propertyUpdaters
          )
          .map(_ => Results.NoContent)
      }

  def findSimilar(obsId: String): Action[AnyContent] =
    entryPoint("find similar")
      .authRoTransaction(db) { implicit request => implicit graph =>
        val observables = observableSrv
          .getByIds(obsId)
          .visible
          .similar
          .visible
          .richObservableWithCustomRenderer(observableLinkRenderer(db, graph))
          .toList
          .map {
            case (org, parent) => org.toJson.as[JsObject] ++ parent
          }

        Success(Results.Ok(JsArray(observables)))
      }

  def bulkUpdate: Action[AnyContent] =
    entryPoint("bulk update")
      .extract("input", FieldsParser.update("observable", publicProperties))
      .extract("ids", FieldsParser.seq[String].on("ids"))
      .authTransaction(db) { implicit request => implicit graph =>
        val properties: Seq[PropertyUpdater] = request.body("input")
        val ids: Seq[String]                 = request.body("ids")
        ids
          .toTry { id =>
            observableSrv
              .update(_.getByIds(id).can(Permissions.manageObservable), properties)
          }
          .map(_ => Results.NoContent)
      }

  def delete(obsId: String): Action[AnyContent] =
    entryPoint("delete")
      .authTransaction(db) { implicit request => implicit graph =>
        for {
          observable <- observableSrv
            .getByIds(obsId)
            .can(Permissions.manageObservable)
            .getOrFail()
          _ <- observableSrv.cascadeRemove(observable)
        } yield Results.NoContent
      }
}
