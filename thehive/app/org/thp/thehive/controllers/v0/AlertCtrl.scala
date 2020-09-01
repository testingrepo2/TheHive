package org.thp.thehive.controllers.v0

import java.util.{Base64, List => JList, Map => JMap}

import io.scalaland.chimney.dsl._
import javax.inject.{Inject, Named, Singleton}
import org.apache.tinkerpop.gremlin.structure.Graph
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.controllers.{Entrypoint, FString, FieldsParser}
import org.thp.scalligraph.models.Database
import org.thp.scalligraph.query.{ParamQuery, PropertyUpdater, PublicProperty, Query}
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{Converter, IteratorOutput, Traversal}
import org.thp.scalligraph.{AuthorizationError, InvalidFormatAttributeError, RichSeq}
import org.thp.thehive.controllers.v0.Conversion._
import org.thp.thehive.dto.v0.{InputAlert, InputObservable, OutputSimilarCase}
import org.thp.thehive.models._
import org.thp.thehive.services.AlertOps._
import org.thp.thehive.services.CaseOps._
import org.thp.thehive.services.CaseTemplateOps._
import org.thp.thehive.services.ObservableOps._
import org.thp.thehive.services.OrganisationOps._
import org.thp.thehive.services.UserOps._
import org.thp.thehive.services._
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, Json}
import play.api.mvc.{Action, AnyContent, Results}

import scala.util.{Failure, Success, Try}

@Singleton
class AlertCtrl @Inject() (
    entrypoint: Entrypoint,
    properties: Properties,
    alertSrv: AlertSrv,
    caseTemplateSrv: CaseTemplateSrv,
    observableSrv: ObservableSrv,
    observableTypeSrv: ObservableTypeSrv,
    attachmentSrv: AttachmentSrv,
    organisationSrv: OrganisationSrv,
    auditSrv: AuditSrv,
    userSrv: UserSrv,
    caseSrv: CaseSrv,
    @Named("with-thehive-schema") implicit val db: Database
) extends QueryableCtrl {
  lazy val logger: Logger                                   = Logger(getClass)
  override val entityName: String                           = "alert"
  override val publicProperties: List[PublicProperty[_, _]] = properties.alert
  override val initialQuery: Query =
    Query
      .init[Traversal.V[Alert]]("listAlert", (graph, authContext) => organisationSrv.get(authContext.organisation)(graph).alerts)
  override val getQuery: ParamQuery[IdOrName] = Query.initWithParam[IdOrName, Traversal.V[Alert]](
    "getAlert",
    FieldsParser[IdOrName],
    (param, graph, authContext) => alertSrv.get(param.idOrName)(graph).visible(authContext)
  )
  override val pageQuery: ParamQuery[OutputParam] =
    Query.withParam[OutputParam, Traversal.V[Alert], IteratorOutput](
      "page",
      FieldsParser[OutputParam],
      (range, alertSteps, _) =>
        alertSteps
          .richPage(range.from, range.to, withTotal = true) { alerts =>
            alerts.project(_.by(_.richAlert).by(_.observables.richObservable.fold))
          }
    )
  override val outputQuery: Query = Query.output[RichAlert, Traversal.V[Alert]](_.richAlert)
  override val extraQueries: Seq[ParamQuery[_]] = Seq(
    Query[Traversal.V[Alert], Traversal.V[Case]]("cases", (alertSteps, _) => alertSteps.`case`),
    Query[Traversal.V[Alert], Traversal.V[Observable]]("observables", (alertSteps, _) => alertSteps.observables),
    Query[
      Traversal.V[Alert],
      Traversal[(RichAlert, Seq[RichObservable]), JMap[String, Any], Converter[(RichAlert, Seq[RichObservable]), JMap[String, Any]]]
    ](
      "withObservables",
      (alertSteps, _) =>
        alertSteps
          .project(
            _.by(_.richAlert)
              .by(_.observables.richObservable.fold)
          )
    ),
    Query.output[(RichAlert, Seq[RichObservable])]
  )

  def create: Action[AnyContent] =
    entrypoint("create alert")
      .extract("alert", FieldsParser[InputAlert])
      .extract("caseTemplate", FieldsParser[String].optional.on("caseTemplate"))
      .extract("observables", FieldsParser[InputObservable].sequence.on("artifacts"))
      .authTransaction(db) { implicit request => implicit graph =>
        val caseTemplateName: Option[String]  = request.body("caseTemplate")
        val inputAlert: InputAlert            = request.body("alert")
        val observables: Seq[InputObservable] = request.body("observables")
        val customFields                      = inputAlert.customFields.map(c => c.name -> c.value).toMap
        val caseTemplate                      = caseTemplateName.flatMap(caseTemplateSrv.get(_).visible.headOption)
        for {
          organisation <- userSrv
            .current
            .organisations(Permissions.manageAlert)
            .get(request.organisation)
            .orFail(AuthorizationError("Operation not permitted"))
          richObservables <- observables.toTry(createObservable).map(_.flatten)
          richAlert       <- alertSrv.create(request.body("alert").toAlert, organisation, inputAlert.tags, customFields, caseTemplate)
          _               <- auditSrv.mergeAudits(richObservables.toTry(o => alertSrv.addObservable(richAlert.alert, o)))(_ => Success(()))
        } yield Results.Created((richAlert -> richObservables).toJson)
      }

  def alertSimilarityRenderer(
      implicit authContext: AuthContext
  ): Traversal.V[Alert] => Traversal[JsArray, JList[JMap[String, Any]], Converter[JsArray, JList[JMap[String, Any]]]] =
    _.similarCases
      .fold
      .domainMap { similarCases =>
        JsArray {
          similarCases.map {
            case (richCase, similarStats) =>
              val similarCase = richCase
                .into[OutputSimilarCase]
                .withFieldConst(_.artifactCount, similarStats.observable._2)
                .withFieldConst(_.iocCount, similarStats.ioc._2)
                .withFieldConst(_.similarArtifactCount, similarStats.observable._1)
                .withFieldConst(_.similarIocCount, similarStats.ioc._1)
                .withFieldRenamed(_._id, _.id)
                .withFieldRenamed(_.number, _.caseId)
                .withFieldComputed(_.status, _.status.toString)
                .withFieldComputed(_.tags, _.tags.map(_.toString).toSet)
                .transform
              Json.toJson(similarCase)
          }
        }
      }

  def get(alertId: String): Action[AnyContent] =
    entrypoint("get alert")
      .extract("similarity", FieldsParser[Boolean].optional.on("similarity"))
      .authRoTransaction(db) { implicit request => implicit graph =>
        val similarity: Option[Boolean] = request.body("similarity")
        val alert =
          alertSrv
            .get(alertId)
            .visible
        if (similarity.contains(true))
          alert
            .richAlertWithCustomRenderer(alertSimilarityRenderer(request))
            .getOrFail("Alert")
            .map {
              case (richAlert, similarCases) =>
                val alertWithObservables: (RichAlert, Seq[RichObservable]) =
                  richAlert -> alertSrv.get(richAlert.alert).observables.richObservableWithSeen.toSeq

                Results.Ok(alertWithObservables.toJson.as[JsObject] + ("similarCases" -> similarCases))
            }
        else
          alert
            .richAlert
            .getOrFail("Alert")
            .map { richAlert =>
              val alertWithObservables: (RichAlert, Seq[RichObservable]) =
                richAlert -> alertSrv.get(richAlert.alert).observables.richObservable.toSeq
              Results.Ok(alertWithObservables.toJson)
            }

      }

  def update(alertId: String): Action[AnyContent] =
    entrypoint("update alert")
      .extract("alert", FieldsParser.update("alert", publicProperties))
      .authTransaction(db) { implicit request => implicit graph =>
        val propertyUpdaters: Seq[PropertyUpdater] = request.body("alert")
        alertSrv
          .update(_.get(alertId).can(Permissions.manageAlert), propertyUpdaters)
          .flatMap { case (alertSteps, _) => alertSteps.richAlert.getOrFail("Alert") }
          .map { richAlert =>
            val alertWithObservables: (RichAlert, Seq[RichObservable]) = richAlert -> alertSrv.get(richAlert.alert).observables.richObservable.toSeq
            Results.Ok(alertWithObservables.toJson)
          }
      }

  def delete(alertId: String): Action[AnyContent] =
    entrypoint("delete alert")
      .authTransaction(db) { implicit request => implicit graph =>
        for {
          alert <- alertSrv
            .get(alertId)
            .can(Permissions.manageAlert)
            .getOrFail("Alert")
          _ <- alertSrv.remove(alert)
        } yield Results.NoContent
      }

  def bulkDelete: Action[AnyContent] =
    entrypoint("bulk delete alerts")
      .extract("ids", FieldsParser.string.sequence.on("ids"))
      .authTransaction(db) { implicit request => implicit graph =>
        val ids: Seq[String] = request.body("ids")
        ids
          .toTry { alertId =>
            for {
              alert <- alertSrv
                .get(alertId)
                .can(Permissions.manageAlert)
                .getOrFail("Alert")
              _ <- alertSrv.remove(alert)
            } yield ()
          }
          .map(_ => Results.NoContent)
      }

  def mergeWithCase(alertId: String, caseId: String): Action[AnyContent] =
    entrypoint("merge alert with case")
      .authTransaction(db) { implicit request => implicit graph =>
        for {
          alert    <- alertSrv.get(alertId).can(Permissions.manageAlert).getOrFail("Alert")
          case0    <- caseSrv.get(caseId).can(Permissions.manageCase).getOrFail("Case")
          _        <- alertSrv.mergeInCase(alert, case0)
          richCase <- caseSrv.get(caseId).richCase.getOrFail("Case")
        } yield Results.Ok(richCase.toJson)
      }

  def bulkMergeWithCase: Action[AnyContent] =
    entrypoint("bulk merge with case")
      .extract("caseId", FieldsParser.string.on("caseId"))
      .extract("alertIds", FieldsParser.string.sequence.on("alertIds"))
      .authTransaction(db) { implicit request => implicit graph =>
        val alertIds: Seq[String] = request.body("alertIds")
        val caseId: String        = request.body("caseId")
        for {
          case0 <- caseSrv.get(caseId).can(Permissions.manageCase).getOrFail("Case")
          _ <- alertIds.toTry { alertId =>
            alertSrv
              .get(alertId)
              .can(Permissions.manageAlert)
              .getOrFail("Alert")
              .flatMap(alertSrv.mergeInCase(_, case0))
          }
          richCase <- caseSrv.get(caseId).richCase.getOrFail("Case")
        } yield Results.Ok(richCase.toJson)
      }

  def markAsRead(alertId: String): Action[AnyContent] =
    entrypoint("mark alert as read")
      .authTransaction(db) { implicit request => implicit graph =>
        alertSrv
          .get(alertId)
          .can(Permissions.manageAlert)
          .existsOrFail
          .map { _ =>
            alertSrv.markAsRead(alertId)
            Results.NoContent
          }
      }

  def markAsUnread(alertId: String): Action[AnyContent] =
    entrypoint("mark alert as unread")
      .authTransaction(db) { implicit request => implicit graph =>
        alertSrv
          .get(alertId)
          .can(Permissions.manageAlert)
          .existsOrFail
          .map { _ =>
            alertSrv.markAsUnread(alertId)
            Results.NoContent
          }
      }

  def createCase(alertId: String): Action[AnyContent] =
    entrypoint("create case from alert")
      .extract("caseTemplate", FieldsParser.string.optional.on("caseTemplate"))
      .authTransaction(db) { implicit request => implicit graph =>
        val caseTemplate: Option[String] = request.body("caseTemplate")
        for {
          (alert, organisation) <- alertSrv
            .get(alertId)
            .can(Permissions.manageAlert)
            .alertUserOrganisation(Permissions.manageCase)
            .getOrFail("Alert")
          alertWithCaseTemplate = caseTemplate.fold(alert)(ct => alert.copy(caseTemplate = Some(ct)))
          richCase <- alertSrv.createCase(alertWithCaseTemplate, None, organisation)
        } yield Results.Created(richCase.toJson)
      }

  def followAlert(alertId: String): Action[AnyContent] =
    entrypoint("follow alert")
      .authTransaction(db) { implicit request => implicit graph =>
        alertSrv
          .get(alertId)
          .can(Permissions.manageAlert)
          .existsOrFail
          .map { _ =>
            alertSrv.followAlert(alertId)
            Results.NoContent
          }
      }

  def unfollowAlert(alertId: String): Action[AnyContent] =
    entrypoint("unfollow alert")
      .authTransaction(db) { implicit request => implicit graph =>
        alertSrv
          .get(alertId)
          .can(Permissions.manageAlert)
          .existsOrFail
          .map { _ =>
            alertSrv.unfollowAlert(alertId)
            Results.NoContent
          }
      }

  private def createObservable(observable: InputObservable)(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[Seq[RichObservable]] =
    observableTypeSrv
      .getOrFail(observable.dataType)
      .flatMap {
        case attachmentType if attachmentType.isAttachment =>
          observable.data.map(_.split(';')).toTry {
            case Array(filename, contentType, value) =>
              val data = Base64.getDecoder.decode(value)
              attachmentSrv
                .create(filename, contentType, data)
                .flatMap(attachment => observableSrv.create(observable.toObservable, attachmentType, attachment, observable.tags, Nil))
            case data =>
              Failure(InvalidFormatAttributeError("artifacts.data", "filename;contentType;base64value", Set.empty, FString(data.mkString(";"))))
          }
        case dataType => observable.data.toTry(d => observableSrv.create(observable.toObservable, dataType, d, observable.tags, Nil))
      }
}
