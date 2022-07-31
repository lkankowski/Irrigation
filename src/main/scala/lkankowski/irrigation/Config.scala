package lkankowski.irrigation

import cats.syntax.either._
import io.circe._
import io.circe.generic.auto._
import io.circe.yaml.syntax._

import scala.io.Source

case class Config(
  version: String,
  general: General,
  zones: Map[String, Zone],
  waterSupplies: Map[String, WaterSupply],
  sensors: Map[String, Sensor],
  mqtt: MqttParams,
)
case class General(name: String, id: String)
case class Zone(`type`: String, waterRequirement: Float, mqtt: Option[MqttCommand])
case class WaterSupply(capacity: String, flow: Float, pressure: Float, cmdTopic: Option[String])
case class Sensor(`type`: String, zones: List[String], stateTopic: String)
case class MqttParams(
  host: String,
  port: Int,
  username: Option[String],
  password: Option[String],
  discoveryTopicPrefix: Option[String]
)
case class MqttCommand(commandTopic: String, commandOn: Option[String], commandOff: Option[String])

object Config {
  def loadConfig: Config = {
    implicit val generalDecoder: Decoder[General] =
      Decoder.forProduct2("name", "id"){ (name: String, id: String) =>
        if (!id.matches("^[a-zA-Z0-9_]{1,50}$")) throw new RuntimeException("Invalid general.id")
        General.apply(name, id)
    }

    val resource = Source.fromResource("config.yaml")

    yaml.parser.parse(resource.mkString)
      .leftMap(err => err: Error)
      .flatMap(_.as[Config])
      .valueOr(throw _)
  }

  def validate: Unit = ???
  //(Option(1), Option(2), Option(3)).mapN(_ + _ + _)
}
