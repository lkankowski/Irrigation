package lkankowski.irrigation

import akka.actor.{Actor, ActorSystem, Props}
import akka.event.Logging

final class MqttActor(config: Config) extends Actor {
  import MqttActor._

  val logger = Logging(context.system, this)
  implicit val system: ActorSystem = context.system

  logger.info(s"${MqttActor.Name}: starting")

  private val mqttDiscovery = MqttDiscovery(config.general.id, config.mqtt.discoveryTopicPrefix)
  val mqtt = Mqtt(config, mqttDiscovery)
  mqtt.subscribeToCommandTopic(mqttDiscovery.getCommandTopicPrefix, processCommand)

  override def receive: Receive = {
    case PublishDiscoveryMessages           => mqtt.sendDiscoveryMessages()
    case PublishIrrigationMode(payload)     => mqtt.publishIrrigationMode(payload)
    case PublishIrrigationInterval(payload) => mqtt.publishIrrigationInterval(payload)
    case PublishAllState(zonesState)        => mqtt.publishAllSwitchState(zonesState)
    case PublishState(id, state)            => mqtt.publishSwitchState(id, state)
    case PublishAllMode(zonesMode)          => mqtt.publishAllZoneMode(zonesMode)
    case PublishMode(id, mode)              => mqtt.publishZoneMode(id, mode)
  }

  def processCommand(topic: String, commandString: String): Unit = {
    import cats.syntax.functor._
    import io.circe.Decoder
    import io.circe.generic.semiauto._
    import io.circe.parser._

    //      implicit val testDecoder: Decoder[Test] = deriveDecoder[Test]
    //      implicit val commandDecoder: Decoder[Command] = List[Decoder[Command]](testDecoder.widen).reduceLeft(_ or _)

    logger.info(s"MQTT command received on '${topic}': $commandString")

    if (topic.startsWith(mqttDiscovery.getCommandTopicPrefix + "/")) {
      val command = topic.substring(mqttDiscovery.getCommandTopicPrefix.length + 1)
      val parts = command.split("_")
      val entity = parts(0)
      val id = parts.tail.mkString
      println(s"entity: $entity, id: $id")
      entity match {
        case "mode"      => context.parent ! MainActor.SetMode(commandString)
        case "interval"  => context.parent ! MainActor.SetInterval(commandString)
        case "zoneState" => context.parent ! MainActor.SetZoneState(id, commandString)
        case "zoneMode"  => context.parent ! MainActor.SetZoneMode(id, commandString)
        case _           => println("entity not match")
      }
    }

    //      try {
    //        parse(commandString) match {
    //          case Left(x)     =>
    //            commandString match {
    //              case Command.Exit        => context.parent ! MainActor.Exit
    ////              case Command.ModeOff     => context.parent ! MainActor.ModeOff
    ////              case Command.ModeAuto    => context.parent ! MainActor.ModeAuto
    ////              case Command.ModeOneTime => context.parent ! MainActor.ModeOneTime
    //              case _ => ()
    //            }
    //          case Right(json) =>
    //            json.as[Command] match {
    //              case Left(_)        => logger.info(s"JSON command decode failure!: ${json.noSpaces}")
    //              case Right(command) =>
    //                command match {
    //                  case Test(test) => logger.info(s"Command Test: $test")
    //                  case _ => ()
    //                }
    //            }
    //        }
    //      } catch {
    //        case ex: Throwable => logger.info(s"MQTT processCommand - exception occured: $ex")
    //      }
  }
}

object MqttActor {
  val Name = "Mqtt-actor"
  def props = Props[MqttActor]()

  sealed trait In
  case object PublishDiscoveryMessages extends In
  case class PublishIrrigationMode(payload: String) extends In
  case class PublishIrrigationInterval(payload: String) extends In
  final case class PublishAllState(zonesState: Map[String, String]) extends In
  final case class PublishState(id: String, state: String) extends In
  final case class PublishAllMode(zonesState: Map[String, String]) extends In
  final case class PublishMode(id: String, state: String) extends In
}
