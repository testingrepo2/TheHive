package org.thp.thehive.services

import org.apache.tinkerpop.gremlin.process.traversal.TextP

import java.util.{Map => JMap}
import javax.inject.{Inject, Named, Singleton}
import org.apache.tinkerpop.gremlin.structure.Graph
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models.{Database, Entity}
import org.thp.scalligraph.services.{EdgeSrv, VertexSrv}
import org.thp.scalligraph.traversal.Converter.Identity
import org.thp.scalligraph.traversal.TraversalOps.TraversalOpsDefs
import org.thp.scalligraph.traversal.{Converter, Traversal}
import org.thp.scalligraph.utils.FunctionalCondition.When
import org.thp.scalligraph.{BadRequestError, EntityId, EntityIdOrName, RichSeq}
import org.thp.thehive.models._
import org.thp.thehive.services.OrganisationOps._
import org.thp.thehive.services.TaxonomyOps._
import org.thp.thehive.services.TagOps._

import scala.util.{Failure, Success, Try}

@Singleton
class TaxonomySrv @Inject() (
    organisationSrv: OrganisationSrv,
    tagSrv: TagSrv
)(implicit @Named("with-thehive-schema") db: Database)
    extends VertexSrv[Taxonomy] {

  val taxonomyTagSrv          = new EdgeSrv[TaxonomyTag, Taxonomy, Tag]
  val organisationTaxonomySrv = new EdgeSrv[OrganisationTaxonomy, Organisation, Taxonomy]

  def create(taxo: Taxonomy, tags: Seq[Tag with Entity])(implicit graph: Graph, authContext: AuthContext): Try[RichTaxonomy] =
    for {
      taxonomy     <- createEntity(taxo)
      _            <- tags.toTry(t => taxonomyTagSrv.create(TaxonomyTag(), taxonomy, t))
      richTaxonomy <- Try(RichTaxonomy(taxonomy, tags))
    } yield richTaxonomy

  def createFreetag(organisation: Organisation with Entity)(implicit graph: Graph, authContext: AuthContext): Try[RichTaxonomy] = {
    val customTaxo = Taxonomy(s"_freetags_${organisation._id}", "Custom taxonomy", 1)
    for {
      taxonomy     <- createEntity(customTaxo)
      richTaxonomy <- Try(RichTaxonomy(taxonomy, Seq()))
      _            <- organisationTaxonomySrv.create(OrganisationTaxonomy(), organisation, taxonomy)
    } yield richTaxonomy
  }

  override def getByName(name: String)(implicit graph: Graph): Traversal.V[Taxonomy] =
    Try(startTraversal.getByNamespace(name)).getOrElse(startTraversal.limit(0))

  def update(taxonomy: Taxonomy with Entity, input: Taxonomy)(implicit graph: Graph): Try[RichTaxonomy] =
    for {
      updatedTaxonomy <-
        get(taxonomy)
          .when(taxonomy.namespace != input.namespace)(_.update(_.namespace, input.namespace))
          .when(taxonomy.description != input.description)(_.update(_.description, input.description))
          .when(taxonomy.version != input.version)(_.update(_.version, input.version))
          .richTaxonomy
          .getOrFail("Taxonomy")
    } yield updatedTaxonomy

  def updateOrCreateTag(namespace: String, t: Tag)(implicit graph: Graph, authContext: AuthContext): Try[Tag with Entity] =
    if (getByName(namespace).doesTagExists(t))
      for {
        tag        <- tagSrv.getTag(t).getOrFail("Tag")
        updatedTag <- tagSrv.update(tag, t)
      } yield updatedTag
    else
      for {
        tag  <- tagSrv.create(t)
        taxo <- getByName(namespace).getOrFail("Taxonomy")
        _    <- taxonomyTagSrv.create(TaxonomyTag(), taxo, tag)
      } yield tag

  def activate(taxonomyId: EntityIdOrName)(implicit graph: Graph, authContext: AuthContext): Try[Unit] =
    for {
      taxo <- get(taxonomyId).getOrFail("Taxonomy")
      _ <-
        if (taxo.namespace.startsWith("_freetags")) Failure(BadRequestError("Cannot activate a freetags taxonomy"))
        else Success(())
      _ <-
        organisationSrv
          .startTraversal
          .filterNot(_.out[OrganisationTaxonomy].v[Taxonomy].has(_.namespace, taxo.namespace))
          .toSeq
          .toTry(o => organisationTaxonomySrv.create(OrganisationTaxonomy(), o, taxo))
    } yield ()

  def deactivate(taxonomyId: EntityIdOrName)(implicit graph: Graph): Try[Unit] =
    for {
      taxo <- getOrFail(taxonomyId)
      _ <-
        if (taxo.namespace.startsWith("_freetags")) Failure(BadRequestError("Cannot deactivate a freetags taxonomy"))
        else Success(())
    } yield get(taxonomyId).inE[OrganisationTaxonomy].remove()

}

object TaxonomyOps {
  implicit class TaxonomyOpsDefs(traversal: Traversal.V[Taxonomy]) {

    def get(idOrName: EntityId): Traversal.V[Taxonomy] =
      idOrName.fold(traversal.getByIds(_), getByNamespace)

    def getByNamespace(namespace: String): Traversal.V[Taxonomy] = traversal.has(_.namespace, namespace)

    def visible(implicit authContext: AuthContext): Traversal.V[Taxonomy] =
      if (authContext.isPermitted(Permissions.manageTaxonomy))
        noFreetags
      else
        noFreetags.filter(_.organisations.get(authContext.organisation))

    private def noFreetags: Traversal.V[Taxonomy] =
      traversal.filterNot(_.has(_.namespace, TextP.startingWith("_freetags")))

    def alreadyImported(namespace: String): Boolean =
      traversal.getByNamespace(namespace).exists

    def organisations: Traversal.V[Organisation] = traversal.in[OrganisationTaxonomy].v[Organisation]

    def enabled: Traversal[Boolean, Boolean, Identity[Boolean]] =
      traversal.choose(_.organisations, true, false)

    def tags: Traversal.V[Tag] = traversal.out[TaxonomyTag].v[Tag]

    def doesTagExists(tag: Tag): Boolean = traversal.tags.getTag(tag).exists

    def richTaxonomy: Traversal[RichTaxonomy, JMap[String, Any], Converter[RichTaxonomy, JMap[String, Any]]] =
      traversal
        .project(
          _.by
            .by(_.tags.fold)
        )
        .domainMap { case (taxonomy, tags) => RichTaxonomy(taxonomy, tags) }

    def richTaxonomyWithCustomRenderer[D, G, C <: Converter[D, G]](
        entityRenderer: Traversal.V[Taxonomy] => Traversal[D, G, C]
    ): Traversal[(RichTaxonomy, D), JMap[String, Any], Converter[(RichTaxonomy, D), JMap[String, Any]]] =
      traversal
        .project(
          _.by
            .by(_.tags.fold)
            .by(entityRenderer)
        )
        .domainMap {
          case (taxo, tags, renderedEntity) =>
            RichTaxonomy(
              taxo,
              tags
            ) -> renderedEntity
        }
  }
}