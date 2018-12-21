package carldata.borsuk.storms

import java.nio.file.Paths
import java.time.{Duration, Instant, LocalDateTime}

import carldata.borsuk.helper.DateTimeHelper._
import carldata.borsuk.helper.JsonHelper._
import carldata.borsuk.helper.{Model, PVCHelper, TimeSeriesHelper}
import carldata.borsuk.storms.ApiObjects.FitStormsParams
import carldata.series.Sessions.Session
import carldata.series.{Gen, Sessions, TimeSeries}
import org.slf4j.LoggerFactory
import spray.json._

import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.immutable.HashMap

object Storms {

  case class StormParams(session: Session, sessionWindow: Duration, values: Vector[Double], childIds: Seq[String])

  private val Log = LoggerFactory.getLogger("Storms")

  /** Get all storms (with merged storms) from rainfall */
  def getAllStorms(rainfall: TimeSeries[Double]
                   , listOfSessionWindows: Option[Seq[Duration]]): List[(String, StormParams)] = {
    if (rainfall.nonEmpty) {
      Log.debug("Get all storms")
      val baseSessions: List[(Int, StormParams)] = Sessions.findSessions(rainfall)
        .zipWithIndex
        .map(x =>
          x._2 ->
            StormParams(x._1, rainfall.resolution
              , TimeSeriesHelper.slice(rainfall, x._1.startIndex
                , x._1.endIndex.plusSeconds(rainfall.resolution.getSeconds)).values, Seq())
        ).toList

      if (baseSessions != Nil) {
        val highestIndex = baseSessions.map(_._1).max
        val mergedSession = if (listOfSessionWindows.isDefined) {
          mergeSessions(baseSessions, Set(), listOfSessionWindows.get, rainfall.resolution, highestIndex)
        }
        else {
          val complexListOfSessionWindows: Seq[Duration] =
            baseSessions.map(x => x._2.session.endIndex).zip(baseSessions.tail.map(x => x._2.session.startIndex))
              .map(x => Duration.between(x._1, x._2))
              .distinct.sorted

          mergeSessions(baseSessions, baseSessions.toSet, complexListOfSessionWindows, rainfall.resolution, highestIndex)

        }

        mergedSession
      }
        .map(x => (x._1.toString, x._2))
        .sortBy(_._2.session.startIndex)
      else List()
    }
    else List()
  }

  @tailrec
  def mergeSessions(prev: List[(Int, StormParams)], res: Set[(Int, StormParams)]
                    , sessionWindows: Seq[Duration], resolution: Duration
                    , highestIndex: Int): List[(Int, StormParams)] = {

    if (sessionWindows.isEmpty) res.toList
    else {
      val sessionWindow: Duration = sessionWindows.head
      val first: (Int, StormParams) = (prev.head._1
        , StormParams(prev.head._2.session, sessionWindow, prev.head._2.values, prev.head._2.childIds))


      val next: List[(Int, StormParams)] = prev.tail.foldLeft[List[(Int, StormParams)]](List(first))((zs, x) => {
        if (sessionWindow.compareTo(Duration.between(zs.head._2.session.endIndex, x._2.session.startIndex)) >= 0) {
          val gapDuration = Duration.between(zs.head._2.session.endIndex, x._2.session.startIndex)

          val gapValues = for (_ <- 1 until (gapDuration.toMillis / resolution.toMillis).toInt) yield 0.0

          (highestIndex + x._1
            , StormParams(Session(zs.head._2.session.startIndex, x._2.session.endIndex)
            , sessionWindow
            , zs.head._2.values ++ gapValues ++ x._2.values
            , zs.head._2.childIds ++ Seq(zs.head._1.toString, x._1.toString))
          ) :: zs.tail
        } //merge sessions
        else {
          val x2 = (highestIndex + x._1, StormParams(x._2.session, sessionWindow, x._2.values, x._2.childIds))
          x2 :: zs
        }


      }).reverse
      mergeSessions(prev, res ++ next, sessionWindows.tail, resolution, highestIndex + next.length)
    }
  }

  /**
    * Calculate maximum intensity for a single rain event.
    */
  def maxIntensity(session: Session, rainfall: TimeSeries[Double], windowSize: Duration): Double = {
    val xs = TimeSeriesHelper.slice(rainfall, session.startIndex, session.endIndex.plusSeconds(1))
      .rollingWindow(windowSize.minusMillis(1), _.sum)

    if (xs.nonEmpty) xs.dataPoints.maxBy(_._2)._2
    else 0.0
  }
}

/**
  * Storm params json protocol
  */

object StormParamsJsonProtocol extends DefaultJsonProtocol {

  implicit object StormParamsFormat extends RootJsonFormat[Storms.StormParams] {
    def read(json: JsValue): Storms.StormParams = json match {

      case JsObject(x) =>

        val sessionJson: JsObject = x("session").asJsObject

        Storms.StormParams(
          Session(dtToInstant(timestampFromValue(sessionJson.fields("start-date"))),
            dtToInstant(timestampFromValue(sessionJson.fields("end-date")))),
          Duration.parse(stringFromValue(x("duration"))),
          arrayFromValue(x("values"), doubleFromValue).toVector,
          x("child-ids").convertTo[Array[String]].toSeq
        )
      case _ =>
        Storms.StormParams(Session(dtToInstant(LocalDateTime.now), dtToInstant(LocalDateTime.now)),
          Duration.ZERO, Vector(), Seq())
    }

    def write(obj: Storms.StormParams): JsValue = {
      JsObject(
        "session" -> JsObject(
          "start-date" -> JsString(instantToLDT(obj.session.startIndex).toString),
          "end-date" -> JsString(instantToLDT(obj.session.endIndex).toString)
        ),
        "duration" -> JsString(obj.sessionWindow.toString),
        "values" -> JsArray(obj.values.map(JsNumber(_))),
        "child-ids" -> JsArray(obj.childIds.map(_.toJson).toVector)
      )
    }
  }

}

/**
  * Storm params hash map json protocol
  */
object StormParamsHashMapJsonProtocol extends DefaultJsonProtocol {

  import StormParamsJsonProtocol._
  import Storms.StormParams
  import spray.json._

  implicit object StormParamsHashMapFormat extends RootJsonFormat[immutable.HashMap[String, StormParams]] {
    def read(json: JsValue): HashMap[String, StormParams] = {
      val map = json.asInstanceOf[JsArray].elements.map(jsVal =>
        (stringFromValue(jsVal.asJsObject.fields("key")),
          jsVal.asJsObject.fields("value").convertTo[StormParams])).toMap
      val hash = immutable.HashMap.empty
      hash.++(map)
    }

    def write(obj: HashMap[String, StormParams]): JsValue = {
      JsArray(obj.map(x => JsObject(
        "key" -> JsString(x._1),
        "value" -> x._2.toJson
      )).toVector)
    }
  }

}

class Storms(modelType: String, id: String) {

  import carldata.borsuk.storms.StormParamsHashMapJsonProtocol._

  private val Log = LoggerFactory.getLogger("Storms")

  var model: immutable.HashMap[String, Storms.StormParams] = immutable.HashMap.empty[String, Storms.StormParams]
  var buildNumber: Int = 0

  /** Fit model */
  def fit(params: FitStormsParams): Unit = {

    Log.debug("Start Fit model: " + this.id)
    if (params.rainfall.values.nonEmpty) {

      val resolution = params.rainfall.resolution
      val endIndex: LocalDateTime = params.rainfall.startDate.plusSeconds(resolution.getSeconds * params.rainfall.values.length)
      val index: Seq[Instant] = Gen.mkIndex(dtToInstant(params.rainfall.startDate), dtToInstant(endIndex), params.rainfall.resolution)
      val rainfall: TimeSeries[Double] = TimeSeries(index.toVector, params.rainfall.values.toVector)

      val mergedSession: List[(String, Storms.StormParams)] = Storms.getAllStorms(rainfall, None)
      model = immutable.HashMap(mergedSession: _*)
      save()
      buildNumber += 1
      Log.debug("Stop Fit model: " + this.id)
    }
  }

  def save() {
    Log.debug("Save model: " + this.id)
    val path = Paths.get("/borsuk_data/storms/", this.modelType)
    val model = Model(this.modelType, this.id, this.model.toJson(StormParamsHashMapFormat).toString)
    PVCHelper.saveModel(path, model)
    Log.debug("Model: " + this.id + " saved")
  }

  /**
    * List all storms
    */
  def list(sessionWindow: Duration): Seq[(String, Session)] = {
    Log.debug("List for model: " + this.id)
    if (model.nonEmpty) {
      model.filter(x => x._2.sessionWindow.compareTo(sessionWindow) <= 0)
        .groupBy(_._2.sessionWindow)
        .toSeq.maxBy(_._1)._2
        .map(x => (x._1, x._2.session)).toSeq
    }
    else Seq()
  }

  /** Get the storm */
  def get(storm_id: String): Option[(Instant, Instant, Seq[Double])] = {
    Log.debug("Get model: " + this.id + " with Storm_ID: " + storm_id)
    model.filter(_._1 == storm_id)
      .map(x => (x._2.session.startIndex, x._2.session.endIndex, x._2.values))
      .headOption
  }
}
