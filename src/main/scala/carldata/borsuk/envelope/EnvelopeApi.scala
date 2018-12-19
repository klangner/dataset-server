package carldata.borsuk.envelope

import java.nio.file.{Path, Paths}

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.StandardRoute
import carldata.borsuk.envelope.ApiObjects._
import carldata.borsuk.envelope.ApiObjectsJsonProtocol._
import carldata.borsuk.envelope.EnvelopeResultHashMapJsonProtocol._
import carldata.borsuk.helper.PVCHelper
import carldata.borsuk.rdiis.{RDII, RdiiApi}
import spray.json._

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class EnvelopeApi(rdiiApi: RdiiApi) {
  private val envelopesPath: String = "/borsuk_data/envelopes/"

  def loadModel(modelType: String, id: String): Option[Envelope] = {
    val envelope = new Envelope(modelType, id)

    val path: Path = Paths.get(envelopesPath + modelType)
    PVCHelper.loadModel(path, id).map {
      model =>
        envelope.model = model.content.parseJson.convertTo[immutable.HashMap[String, EnvelopeResult]]
        envelope.buildNumber += 1
        envelope
    }
  }

  /**
    * Create new envelope and rdiis corresponded to it
    */
  def create(params: CreateEnvelopeParams): StandardRoute = {
    val path: Path = Paths.get(envelopesPath + params.modelType)

    if (PVCHelper.modelExist(path, params.id)) {
      complete(StatusCodes.Conflict -> "Error: Model with this id already exist.")
    } else {
      new Envelope(params.modelType, params.id).save()
      val rdii = new RDII("rdii-v0", params.id)
      rdii.save()

      complete(HttpResponse(StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`application/json`, ModelCreatedResponse(params.id).toJson.compactPrint)))
    }
  }

  def fit(id: String, params: FitEnvelopeParams): StandardRoute = {
    loadModel(params.modelType, id) match {
      case Some(envelopeModel: Envelope) =>
        rdiiApi.loadModel("rdii-v0", id) match {
          case Some(rdiiModel) =>
            Future {
              envelopeModel.fit(params, rdiiModel)
            }
            complete(StatusCodes.OK)
          case None => complete(StatusCodes.NotFound)
        }
      case None => complete(StatusCodes.NotFound)
    }
  }

  def list(id: String, modelType: Option[String]): StandardRoute = {
    val mt = if (modelType.isDefined) modelType.get else "envelope-v0"
    loadModel(mt, id) match {
      case Some(envelopeModel: Envelope) =>

        complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`,
          ListResponse(envelopeModel.list().map(x => ApiObjects.EnvelopeObject(x._1, x._2.sessionWindow)).toArray)
            .toJson.compactPrint)))
      case None => complete(StatusCodes.NotFound)
    }
  }

  def get(id: String, envelopeId: String, modelType: Option[String]): StandardRoute = {
    val mt = if (modelType.isDefined) modelType.get else "envelope-v0"
    loadModel(mt, id) match {
      case Some(envelopeModel) => envelopeModel.model.get(envelopeId) match {
        case Some(singleEnvelope: EnvelopeResult) =>
          complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, GetResponse(
            singleEnvelope.rainfall,
            singleEnvelope.flows,
            singleEnvelope.slope,
            singleEnvelope.intercept,
            singleEnvelope.rSquare,
            singleEnvelope.dates
          ).toJson.compactPrint)))

        case None => complete(StatusCodes.NotFound)
      }
      case None => complete(StatusCodes.NotFound)
    }
  }

  def status(id: String, modelType: Option[String]): StandardRoute = {
    val mt = if (modelType.isDefined) modelType.get else "envelope-v0"
    loadModel(mt, id) match {
      case Some(envelopeModel) => complete(HttpResponse(StatusCodes.OK,
        entity = HttpEntity(ContentTypes.`application/json`, ModelStatus(envelopeModel.buildNumber).toJson.compactPrint)))
      case None => complete(StatusCodes.NotFound)
    }

  }

}
