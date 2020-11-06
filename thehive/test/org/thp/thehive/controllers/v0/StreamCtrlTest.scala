package org.thp.thehive.controllers.v0

import java.util.Date

import org.thp.scalligraph.EntityName
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.models.{Database, DummyUserSrv}
import org.thp.thehive.TestAppBuilder
import org.thp.thehive.models._
import org.thp.thehive.services.{CaseSrv, OrganisationSrv}
import play.api.test.{FakeRequest, PlaySpecification}

class StreamCtrlTest extends PlaySpecification with TestAppBuilder {
  "stream controller" should {
    "create a stream" in testApp { app =>
      val request = FakeRequest("POST", "/api/stream")
        .withHeaders("user" -> "certuser@thehive.local")
      val result = app[StreamCtrl].create(request)

      status(result) must equalTo(200).updateMessage(s => s"$s\n${contentAsString(result)}")
      contentAsString(result) must not(beEmpty)
    }

    "get a case related stream" in testApp { app =>
      implicit val authContext: AuthContext = DummyUserSrv(permissions = Permissions.all).authContext

      val createStreamRequest = FakeRequest("POST", "/api/stream")
        .withHeaders("user" -> "certuser@thehive.local")
      val createStreamResult = app[StreamCtrl].create(createStreamRequest)

      status(createStreamResult) must equalTo(200).updateMessage(s => s"$s\n${contentAsString(createStreamResult)}")
      val streamId = contentAsString(createStreamResult)

      // Add an event
      app[Database].tryTransaction { implicit graph =>
        app[CaseSrv].create(
          Case(0, s"case audit", s"desc audit", 1, new Date(), None, flag = false, 1, 1, CaseStatus.Open, None),
          None,
          app[OrganisationSrv].getOrFail(EntityName("cert")).get,
          Set.empty,
          Seq.empty,
          None,
          Nil
        )
      } must beASuccessfulTry

      val getStreamRequest = FakeRequest("GET", s"/api/stream/$streamId")
        .withHeaders("user" -> "certuser@thehive.local")
      val getStreamResult = app[StreamCtrl].get(streamId)(getStreamRequest)

      status(getStreamResult) must equalTo(200).updateMessage(s => s"$s\n${contentAsString(getStreamResult)}")
      val stream = contentAsJson(getStreamResult)
      (stream \ 0 \ "summary" \ "case" \ "Creation").asOpt[Int] must beSome(1)
    }
  }
}