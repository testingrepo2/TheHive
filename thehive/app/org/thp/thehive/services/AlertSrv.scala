package org.thp.thehive.services

import java.lang.{Long => JLong}
import java.util.{Date, List => JList, Map => JMap}

import javax.inject.{Inject, Named, Singleton}
import org.apache.tinkerpop.gremlin.structure.Graph
import org.thp.scalligraph.auth.{AuthContext, Permission}
import org.thp.scalligraph.models._
import org.thp.scalligraph.query.PropertyUpdater
import org.thp.scalligraph.services._
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{Converter, StepLabel, Traversal}
import org.thp.scalligraph.{CreateError, RichOptionTry, RichSeq}
import org.thp.thehive.controllers.v1.Conversion._
import org.thp.thehive.models._
import org.thp.thehive.services.AlertOps._
import org.thp.thehive.services.CaseOps._
import org.thp.thehive.services.CaseTemplateOps._
import org.thp.thehive.services.CustomFieldOps._
import org.thp.thehive.services.ObservableOps._
import org.thp.thehive.services.OrganisationOps._
import play.api.libs.json.{JsObject, Json}

import scala.util.{Failure, Try}

@Singleton
class AlertSrv @Inject() (
    caseSrv: CaseSrv,
    tagSrv: TagSrv,
    organisationSrv: OrganisationSrv,
    customFieldSrv: CustomFieldSrv,
    caseTemplateSrv: CaseTemplateSrv,
    observableSrv: ObservableSrv,
    auditSrv: AuditSrv
)(
    implicit @Named("with-thehive-schema") db: Database
) extends VertexSrv[Alert] {

  val alertTagSrv          = new EdgeSrv[AlertTag, Alert, Tag]
  val alertCustomFieldSrv  = new EdgeSrv[AlertCustomField, Alert, CustomField]
  val alertOrganisationSrv = new EdgeSrv[AlertOrganisation, Alert, Organisation]
  val alertCaseSrv         = new EdgeSrv[AlertCase, Alert, Case]
  val alertCaseTemplateSrv = new EdgeSrv[AlertCaseTemplate, Alert, CaseTemplate]
  val alertObservableSrv   = new EdgeSrv[AlertObservable, Alert, Observable]

  override def get(idOrSource: String)(implicit graph: Graph): Traversal.V[Alert] = idOrSource.split(';') match {
    case Array(tpe, source, sourceRef) => startTraversal.getBySourceId(tpe, source, sourceRef)
    case _                             => super.getByIds(idOrSource)
  }

  def create(
      alert: Alert,
      organisation: Organisation with Entity,
      tagNames: Set[String],
      customFields: Map[String, Option[Any]],
      caseTemplate: Option[CaseTemplate with Entity]
  )(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[RichAlert] =
    tagNames.toTry(tagSrv.getOrCreate).flatMap(create(alert, organisation, _, customFields, caseTemplate))

  def create(
      alert: Alert,
      organisation: Organisation with Entity,
      tags: Seq[Tag with Entity],
      customFields: Map[String, Option[Any]],
      caseTemplate: Option[CaseTemplate with Entity]
  )(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[RichAlert] = {
    val alertAlreadyExist = organisationSrv.get(organisation).alerts.getBySourceId(alert.`type`, alert.source, alert.sourceRef).getCount
    if (alertAlreadyExist > 0)
      Failure(CreateError(s"Alert ${alert.`type`}:${alert.source}:${alert.sourceRef} already exist in organisation ${organisation.name}"))
    else
      for {
        createdAlert <- createEntity(alert)
        _            <- alertOrganisationSrv.create(AlertOrganisation(), createdAlert, organisation)
        _            <- caseTemplate.map(ct => alertCaseTemplateSrv.create(AlertCaseTemplate(), createdAlert, ct)).flip
        _            <- tags.toTry(t => alertTagSrv.create(AlertTag(), createdAlert, t))
        cfs          <- customFields.toTry { case (name, value) => createCustomField(createdAlert, name, value) }
        richAlert = RichAlert(createdAlert, organisation.name, tags, cfs, None, caseTemplate.map(_.name), 0)
        _ <- auditSrv.alert.create(createdAlert, richAlert.toJson)
      } yield richAlert
  }

  override def update(
      traversal: Traversal.V[Alert],
      propertyUpdaters: Seq[PropertyUpdater]
  )(implicit graph: Graph, authContext: AuthContext): Try[(Traversal.V[Alert], JsObject)] =
    auditSrv.mergeAudits(super.update(traversal, propertyUpdaters)) {
      case (alerts, updatedFields) =>
        alerts
          .clone()
          .getOrFail("Alert")
          .flatMap(auditSrv.alert.update(_, updatedFields))
    }

  def updateTags(alert: Alert with Entity, tags: Set[Tag with Entity])(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    val (tagsToAdd, tagsToRemove) = get(alert)
      .tags
      .toIterator
      .foldLeft((tags, Set.empty[Tag with Entity])) {
        case ((toAdd, toRemove), t) if toAdd.contains(t) => (toAdd - t, toRemove)
        case ((toAdd, toRemove), t)                      => (toAdd, toRemove + t)
      }
    for {
//      createdTags <- tagsToAdd.toTry(tagSrv.getOrCreate)
      _ <- tagsToAdd.toTry(alertTagSrv.create(AlertTag(), alert, _))
      _ = get(alert).removeTags(tagsToRemove)
      _ <- auditSrv.alert.update(alert, Json.obj("tags" -> tags.map(_.toString)))
    } yield ()

  }

  def updateTagNames(alert: Alert with Entity, tags: Set[String])(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    tags.toTry(tagSrv.getOrCreate).flatMap(t => updateTags(alert, t.toSet))

  def addTags(alert: Alert with Entity, tags: Set[String])(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    val currentTags = get(alert)
      .tags
      .toSeq
      .map(_.toString)
      .toSet
    for {
      createdTags <- (tags -- currentTags).toTry(tagSrv.getOrCreate)
      _           <- createdTags.toTry(alertTagSrv.create(AlertTag(), alert, _))
      _           <- auditSrv.alert.update(alert, Json.obj("tags" -> (currentTags ++ tags)))
    } yield ()
  }

  def removeObservable(alert: Alert with Entity, observable: Observable with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    observableSrv
      .get(observable)
      .inE[AlertObservable]
      .filter(_.outV.hasId(alert._id))
      .getOrFail("Observable")
      .flatMap { alertObservable =>
        alertObservableSrv.get(alertObservable).remove()
        auditSrv.observableInAlert.delete(observable, Some(alert))
      }

  def addObservable(alert: Alert with Entity, richObservable: RichObservable)(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[Unit] = {
    val alreadyExistInThatCase = observableSrv
      .get(richObservable.observable)
      .similar
      .alert
      .hasId(alert._id)
      .exists
    if (alreadyExistInThatCase)
      Failure(CreateError("Observable already exists"))
    else
      for {
        _ <- alertObservableSrv.create(AlertObservable(), alert, richObservable.observable)
        _ <- auditSrv.observableInAlert.create(richObservable.observable, alert, richObservable.toJson)
      } yield ()
  }

  def createCustomField(
      alert: Alert with Entity,
      customFieldName: String,
      customFieldValue: Option[Any]
  )(implicit graph: Graph, authContext: AuthContext): Try[RichCustomField] =
    for {
      cf   <- customFieldSrv.getOrFail(customFieldName)
      ccf  <- CustomFieldType.map(cf.`type`).setValue(AlertCustomField(), customFieldValue)
      ccfe <- alertCustomFieldSrv.create(ccf, alert, cf)
    } yield RichCustomField(cf, ccfe)

  def setOrCreateCustomField(alert: Alert with Entity, customFieldName: String, value: Option[Any])(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[Unit] = {
    val cfv = get(alert).customFields(customFieldName)
    if (cfv.clone().exists)
      cfv.setValue(value)
    else
      createCustomField(alert, customFieldName, value).map(_ => ())
  }

  def getCustomField(alert: Alert with Entity, customFieldName: String)(implicit graph: Graph): Option[RichCustomField] =
    get(alert).customFields(customFieldName).richCustomField.headOption

  def updateCustomField(
      alert: Alert with Entity,
      customFieldValues: Seq[(CustomField, Any)]
  )(implicit graph: Graph, authContext: AuthContext): Try[Unit] = {
    val customFieldNames = customFieldValues.map(_._1.name)
    get(alert)
      .customFields
      .richCustomField
      .toIterator
      .filterNot(rcf => customFieldNames.contains(rcf.name))
      .foreach(rcf => get(alert).customFields(rcf.name).remove())
    customFieldValues
      .toTry { case (cf, v) => setOrCreateCustomField(alert, cf.name, Some(v)) }
      .map(_ => ())
  }

  def markAsUnread(alertId: String)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    for {
      alert <- get(alertId).update(_.read, false: Boolean).getOrFail("Alert")
      _     <- auditSrv.alert.update(alert, Json.obj("read" -> false))
    } yield ()

  def markAsRead(alertId: String)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    for {
      alert <- get(alertId).update(_.read, true: Boolean).getOrFail("Alert")
      _     <- auditSrv.alert.update(alert, Json.obj("read" -> true))
    } yield ()

  def followAlert(alertId: String)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    for {
      alert <- get(alertId).update(_.follow, true: Boolean).getOrFail("Alert")
      _     <- auditSrv.alert.update(alert, Json.obj("follow" -> true))
    } yield ()

  def unfollowAlert(alertId: String)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    for {
      alert <- get(alertId).update(_.follow, false: Boolean).getOrFail("Alert")
      _     <- auditSrv.alert.update(alert, Json.obj("follow" -> false))
    } yield ()

  def createCase(alert: RichAlert, user: Option[User with Entity], organisation: Organisation with Entity)(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[RichCase] =
    for {
      caseTemplate <- alert
        .caseTemplate
        .map(caseTemplateSrv.get(_).richCaseTemplate.getOrFail("CaseTemplate"))
        .flip
      customField = alert.customFields.map(f => (f.name, f.value, f.order))
      case0 = Case(
        number = 0,
        title = caseTemplate.flatMap(_.titlePrefix).getOrElse("") + alert.title,
        description = alert.description,
        severity = alert.severity,
        startDate = new Date,
        endDate = None,
        flag = false,
        tlp = alert.tlp,
        pap = alert.pap,
        status = CaseStatus.Open,
        summary = None
      )

      createdCase <- caseSrv.create(case0, user, organisation, alert.tags.toSet, customField, caseTemplate, Nil)
      _           <- importObservables(alert.alert, createdCase.`case`)
      _           <- alertCaseSrv.create(AlertCase(), alert.alert, createdCase.`case`)
      _           <- markAsRead(alert._id)
    } yield createdCase

  def mergeInCase(alertId: String, caseId: String)(implicit graph: Graph, authContext: AuthContext): Try[Case with Entity] =
    for {
      alert       <- getOrFail(alertId)
      case0       <- caseSrv.getOrFail(caseId)
      updatedCase <- mergeInCase(alert, case0)
    } yield updatedCase

  def mergeInCase(alert: Alert with Entity, `case`: Case with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Case with Entity] =
    for {
      _ <- caseSrv.addTags(`case`, get(alert).tags.toSeq.map(_.toString).toSet)
      description = `case`.description + s"\n  \n#### Merged with alert #${alert.sourceRef} ${alert.title}\n\n${alert.description.trim}"
      c <- caseSrv.get(`case`).update(_.description, description).getOrFail("Case")
      _ <- importObservables(alert, `case`)
      _ <- alertCaseSrv.create(AlertCase(), alert, `case`)
      _ <- markAsRead(alert._id)
      _ <- auditSrv.alertToCase.merge(alert, c)
    } yield c

  def importObservables(alert: Alert with Entity, `case`: Case with Entity)(
      implicit graph: Graph,
      authContext: AuthContext
  ): Try[Unit] =
    get(alert)
      .observables
      .richObservable
      .toIterator
      .toTry { richObservable =>
        observableSrv
          .duplicate(richObservable)
          .flatMap(duplicatedObservable => caseSrv.addObservable(`case`, duplicatedObservable))
          .recover { case _: CreateError => () } // ignore if case already contains observable
      }
      .map(_ => ())

  def remove(alert: Alert with Entity)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    for {
      organisation <- organisationSrv.getOrFail(authContext.organisation)
      _            <- get(alert).observables.toIterator.toTry(observableSrv.remove(_))
      _ = get(alert).remove()
      _ <- auditSrv.alert.delete(alert, organisation)
    } yield ()
}

object AlertOps {

  implicit class AlertOpsDefs(traversal: Traversal.V[Alert]) {
    def get(idOrSource: String): Traversal.V[Alert] = idOrSource.split(';') match {
      case Array(tpe, source, sourceRef) => getBySourceId(tpe, source, sourceRef)
      case _                             => traversal.getByIds(idOrSource)
    }

    def getBySourceId(`type`: String, source: String, sourceRef: String): Traversal.V[Alert] =
      traversal
        .has("type", `type`)
        .has("source", source)
        .has("sourceRef", sourceRef)

    def organisation: Traversal.V[Organisation] = traversal.out[AlertOrganisation].v[Organisation]

    def tags: Traversal.V[Tag] = traversal.out[AlertTag].v[Tag]

    def `case`: Traversal.V[Case] = traversal.out[AlertCase].v[Case]

    def removeTags(tags: Set[Tag with Entity]): Unit =
      if (tags.nonEmpty)
        traversal.outE[AlertTag].filter(_.otherV.hasId(tags.map(_._id).toSeq: _*)).remove()

    def visible(implicit authContext: AuthContext): Traversal.V[Alert] =
      traversal.filter(
        _.out[AlertOrganisation]
          .has("name", authContext.organisation)
      )

    def can(permission: Permission)(implicit authContext: AuthContext): Traversal.V[Alert] =
      if (authContext.permissions.contains(permission))
        traversal.filter(
          _.out[AlertOrganisation]
            .has("name", authContext.organisation)
        )
      else traversal.limit(0)

    def imported: Traversal[Boolean, JLong, Converter[Boolean, JLong]] = traversal.outE[AlertCase].count.domainMap(_ > 0)

    def similarCases(
        implicit authContext: AuthContext
    ): Traversal[(RichCase, SimilarStats), JMap[String, Any], Converter[(RichCase, SimilarStats), JMap[String, Any]]] =
      observables
        .similar
        .visible
        .group(_.by(_.`case`))
        .unfold
        .project(
          _.by(
            _.selectKeys
              .project(
                _.by(_.richCaseWithoutPerms)
                  .by((_: Traversal.V[Case]).observables.groupCount(_.byValue(_.ioc)))
              )
          ).by(_.selectValues.unfold.groupCount(_.byValue(_.ioc)))
        )
        .domainMap {
          case ((richCase, obsStats), similarStats) =>
            val obsStatsMap     = obsStats.mapValues(_.toInt)
            val similarStatsMap = similarStats.mapValues(_.toInt)
            richCase -> SimilarStats(
              similarStatsMap.values.sum         -> obsStatsMap.values.sum,
              similarStatsMap.getOrElse(true, 0) -> obsStatsMap.getOrElse(true, 0)
            )
        }

    def alertUserOrganisation(
        permission: Permission
    )(
        implicit authContext: AuthContext
    ): Traversal[(RichAlert, Organisation with Entity), JMap[String, Any], Converter[(RichAlert, Organisation with Entity), JMap[String, Any]]] = {
      val alertLabel            = StepLabel.v[Alert]
      val organisationLabel     = StepLabel.v[Organisation]
      val tagsLabel             = StepLabel.vs[Tag]
      val customFieldValueLabel = StepLabel.e[AlertCustomField]
      val customFieldLabel      = StepLabel.v[CustomField]
      val customFieldWithValueLabel =
        StepLabel[Seq[(AlertCustomField with Entity, CustomField with Entity)], JList[JMap[String, Any]], Converter.CList[
          (AlertCustomField with Entity, CustomField with Entity),
          JMap[String, Any],
          Converter[(AlertCustomField with Entity, CustomField with Entity), JMap[String, Any]]
        ]]
      val caseIdLabel           = StepLabel[Seq[String], JList[AnyRef], Converter.CList[String, AnyRef, Converter[String, AnyRef]]]
      val caseTemplateNameLabel = StepLabel[Seq[String], JList[String], Converter.CList[String, String, Converter[String, String]]]

      val observableCountLabel = StepLabel[Long, JLong, Converter[Long, JLong]]
      val result =
        traversal
          .`match`(
            _.as(alertLabel)(_.out("AlertOrganisation").has("name", authContext.organisation).v[Organisation]).as(organisationLabel),
            _.as(alertLabel)(_.out("AlertTag").v[Tag].fold).as(tagsLabel),
            _.as(alertLabel)(
              _.outE[AlertCustomField]
                .e[AlertCustomField]
                .as(customFieldValueLabel)
                .inV
                .v[CustomField]
                .as(customFieldLabel)
                .select((customFieldValueLabel, customFieldLabel))
                .fold
            ).as(customFieldWithValueLabel),
            _.as(alertLabel)(_.out[AlertCase]._id.fold).as(caseIdLabel),
            _.as(alertLabel)(_.out[AlertCaseTemplate].v[CaseTemplate].value(_.name).fold).as(caseTemplateNameLabel),
            _.as(alertLabel)(_.outE[AlertObservable].count).as(observableCountLabel)
          )
          .select((alertLabel, organisationLabel, tagsLabel, customFieldWithValueLabel, caseIdLabel, caseTemplateNameLabel, observableCountLabel))
          .domainMap {
            case (alert, organisation, tags, customFields, caseId, caseTemplateName, observableCount) =>
              RichAlert(
                alert,
                organisation.name,
                tags,
                customFields.map(cf => RichCustomField(cf._2, cf._1)),
                caseId.headOption,
                caseTemplateName.headOption,
                observableCount
              ) -> organisation
          }
      if (authContext.permissions.contains(permission))
        result
      else
        result.limit(0)
    }

    def customFields(name: String): Traversal.E[AlertCustomField] =
      traversal.outE[AlertCustomField].filter(_.inV.has("name", name)).e[AlertCustomField]

    def customFields: Traversal.E[AlertCustomField] =
      traversal.outE[AlertCustomField].e[AlertCustomField]

    def richCustomFields: Traversal[RichCustomField, JMap[String, Any], Converter[RichCustomField, JMap[String, Any]]] =
      traversal
        .outE[AlertCustomField]
        .e[AlertCustomField]
        .project(_.by.by(_.inV.v[CustomField]))
        .domainMap {
          case (cfv, cf) => RichCustomField(cf, cfv)
        }

    def observables: Traversal.V[Observable] = traversal.out[AlertObservable].v[Observable]

    def richAlertWithCustomRenderer[D, G, C <: Converter[D, G]](
        entityRenderer: Traversal.V[Alert] => Traversal[D, G, C]
    ): Traversal[(RichAlert, D), JMap[String, Any], Converter[(RichAlert, D), JMap[String, Any]]] =
      traversal
        .project(
          _.by
            .by(_.out[AlertOrganisation].v[Organisation].value(_.name))
            .by(_.out[AlertTag].v[Tag].fold)
            .by(_.richCustomFields.fold)
            .by(_.out[AlertCase]._id.fold)
            .by(_.out[AlertCaseTemplate].v[CaseTemplate].value(_.name).fold)
            .by(_.outE[AlertObservable].count)
            .by(entityRenderer)
        )
        .domainMap {
          case (alert, organisation, tags, customFields, caseId, caseTemplate, observableCount, renderedEntity) =>
            RichAlert(
              alert,
              organisation,
              tags,
              customFields,
              caseId.headOption,
              caseTemplate.headOption,
              observableCount
            ) -> renderedEntity
        }

    def richAlert: Traversal[RichAlert, JMap[String, Any], Converter[RichAlert, JMap[String, Any]]] =
      traversal
        .project(
          _.by
            .by(_.out[AlertOrganisation].v[Organisation].value(_.name).fold)
            .by(_.out[AlertTag].v[Tag].fold)
            .by(_.richCustomFields.fold)
            .by(_.out[AlertCase]._id.fold)
            .by(_.out[AlertCaseTemplate].v[CaseTemplate].value(_.name).fold)
            .by(_.outE[AlertObservable].count)
        )
        .domainMap {
          case (alert, organisation, tags, customFields, caseId, caseTemplate, observableCount) =>
            RichAlert(
              alert,
              organisation.head,
              tags,
              customFields,
              caseId.headOption,
              caseTemplate.headOption,
              observableCount
            )
        }
  }

  implicit class AlertCustomFieldsOpsDefs(traversal: Traversal.E[AlertCustomField]) extends CustomFieldValueOpsDefs(traversal)
}
