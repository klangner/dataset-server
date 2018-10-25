package borsuk

import java.time.{Duration, LocalDateTime}

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import carldata.borsuk.BasicApiObjects.TimeSeriesParams
import carldata.borsuk.Routing
import carldata.borsuk.rdiis.ApiObjects._
import carldata.borsuk.rdiis.ApiObjectsJsonProtocol._
import org.scalatest.{Matchers, WordSpec}
import spray.json._


class RDIIApiTest extends WordSpec with Matchers with ScalatestRouteTest with SprayJsonSupport {

  private def mainRoute() = {
    val routing = new Routing()
    routing.route()
  }

  private val createModelRequest: HttpRequest = {
    val params = CreateParams("rdii-v0", "secret-id")
    HttpRequest(
      HttpMethods.POST,
      uri = "/rdiis",
      entity = HttpEntity(MediaTypes.`application/json`, params.toJson.compactPrint))
  }

  "The RDII" should {

    "create new model" in {
      createModelRequest ~> mainRoute() ~> check {
        val res = responseAs[ModelCreatedResponse]
        res.id shouldEqual "secret-id"
        status shouldEqual StatusCodes.OK
      }
    }

    "create new model with id already in use" in {
      val route = mainRoute()
      createModelRequest ~> route ~> check {
        createModelRequest
      } ~> route ~> check {
        responseAs[String] shouldEqual "Error: Model with this id already exist."
        status shouldEqual StatusCodes.Conflict
      }
    }

    "not fit if model doesn't exist" in {
      val resolution: Duration = Duration.ofMinutes(10)
      val trainData = 0.to(1000).map(_ => 1.0).toArray
      val tsp = TimeSeriesParams(LocalDateTime.now, resolution, trainData)

      val fitParams = FitRDIIParams(tsp, tsp, Duration.ofHours(12))

      val request = HttpRequest(
        HttpMethods.POST,
        uri = "/rdiis/000/fit",
        entity = HttpEntity(MediaTypes.`application/json`, fitParams.toJson.compactPrint))

      request ~> mainRoute() ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "not list RDII if model doesn't exist" in {
      val request = HttpRequest(
        HttpMethods.GET,
        uri = "/rdiis/000/rdii?sessionWindow=P2D")

      request ~> mainRoute() ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "not give status if model doesn't exist" in {
      val request = HttpRequest(
        HttpMethods.GET,
        uri = "/rdiis/000")

      request ~> mainRoute() ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "fit the model" in {
      val route = mainRoute()
      val trainData = 0.to(1000).map(_ => 1.0).toArray
      val resolution: Duration = Duration.ofMinutes(10)
      val tsp = TimeSeriesParams(LocalDateTime.now, resolution, trainData)

      val fitParams = FitRDIIParams(tsp, tsp, Duration.ofHours(12))

      createModelRequest ~> route ~> check {
        val mcr = responseAs[ModelCreatedResponse]
        val fitRequest = HttpRequest(
          HttpMethods.POST,
          uri = s"/rdiis/${mcr.id}/fit",
          entity = HttpEntity(MediaTypes.`application/json`, fitParams.toJson.compactPrint))

        fitRequest ~> route ~> check {
          status shouldEqual StatusCodes.OK
          val request = HttpRequest(HttpMethods.GET, uri = s"/rdiis/${mcr.id}")

          request ~> route ~> check {
            val modelStatus = responseAs[ModelStatus]
            modelStatus.build shouldEqual 1
          }

        }
      }
    }

    "list the model" in {
      val route = mainRoute()
      val trainData = 0.to(1000).map(_ => 1.0).toArray
      val resolution: Duration = Duration.ofMinutes(10)
      val tsp = TimeSeriesParams(LocalDateTime.now, resolution, trainData)

      val fitParams = FitRDIIParams(tsp, tsp, Duration.ofHours(12))

      createModelRequest ~> route ~> check {
        val mcr = responseAs[ModelCreatedResponse]
        val fitRequest = HttpRequest(
          HttpMethods.POST,
          uri = s"/rdiis/${mcr.id}/fit",
          entity = HttpEntity(MediaTypes.`application/json`, fitParams.toJson.compactPrint))

        fitRequest ~> route ~> check {
          status shouldEqual StatusCodes.OK
          val request = HttpRequest(HttpMethods.GET, uri = s"/rdiis/${mcr.id}/rdii?sessionWindow=P2D")

          request ~> route ~> check {
            responseAs[ListResponse].rdii.length shouldEqual 0
          }

        }
      }
    }
    "try get model- not existed" in {
      val route = mainRoute()
      val trainData = 0.to(1000).map(_ => 1.0).toArray
      val resolution: Duration = Duration.ofMinutes(10)
      val tsp = TimeSeriesParams(LocalDateTime.now, resolution, trainData)

      val fitParams = FitRDIIParams(tsp, tsp, Duration.ofHours(12))

      createModelRequest ~> route ~> check {
        val mcr = responseAs[ModelCreatedResponse]
        val fitRequest = HttpRequest(
          HttpMethods.POST,
          uri = s"/rdiis/${mcr.id}/fit",
          entity = HttpEntity(MediaTypes.`application/json`, fitParams.toJson.compactPrint))

        fitRequest ~> route ~> check {
          status shouldEqual StatusCodes.OK
          val listRequest = HttpRequest(HttpMethods.GET, uri = s"/rdiis/${mcr.id}/rdii?sessionWindow=P2D")

          listRequest ~> route ~> check {
            responseAs[ListResponse].rdii.length shouldEqual 0
            val getRequest = HttpRequest(HttpMethods.GET, uri = s"/rdiis/${mcr.id}/rdii/test")

            getRequest ~> route ~> check {
              status shouldEqual StatusCodes.NotFound
            }
          }
        }
      }
    }
  }
}