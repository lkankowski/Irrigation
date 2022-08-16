package lkankowski.irrigation

import akka.actor.{Actor, ActorSystem}
import akka.event.Logging
import lkankowski.irrigation.Config._

final class MqttActor(config: Config) extends Actor {
  import MqttActor._

  val logger = Logging(context.system, this)
  implicit val system: ActorSystem = context.system
  private val useRetainForDiscovery = false

  logger.info(s"${MqttActor.Name}: starting")

  private val mqttDiscovery = MqttDiscovery(config.general.id, config.mqtt.discoveryTopicPrefix)
  private val mqtt = Mqtt(config, mqttDiscovery)
  private val killSwitch = mqtt.subscribeToCommandTopic(mqttDiscovery.getCommandTopicPrefix, processCommand)

  override def receive: Receive = {
    case PublishDiscoveryMessages           =>
      sendDiscoveryMessages()

    case PublishIrrigationMode(payload)     =>
      mqtt.sendCommand(mqttDiscovery.getStateTopic("mode"), s"{ \"mode\": \"${payload}\" }")

    case PublishIrrigationDuration(payload) =>
      mqtt.sendCommand(mqttDiscovery.getStateTopic("duration"), s"{ \"mode\": \"${payload}\" }")

    case PublishAllState(zonesState)        =>
      mqtt.sendCommands(zonesState.toSeq.map { case (zoneId, payload) => (
        mqttDiscovery.getStateTopic(s"zoneState_${zoneId}"),
        s"{ \"state\": \"${payload}\" }",
        false
      )})

    case PublishState(id, state)            =>
      mqtt.sendCommand(mqttDiscovery.getStateTopic(s"zoneState_${id}"), s"{ \"state\": \"${state}\" }")

    case PublishAllMode(zonesMode)          =>
      mqtt.sendCommands(zonesMode.toSeq.map { case (zoneId, mode) => (
        mqttDiscovery.getStateTopic(s"zoneMode_${zoneId}"),
        s"{ \"mode\": \"${mode}\" }",
        false
      )})

    case PublishMode(id, mode)              =>
      mqtt.sendCommand(mqttDiscovery.getStateTopic(s"zoneMode_${id}"), s"{ \"mode\": \"${mode}\" }")

    case SendCommand(topic, payload)        => mqtt.sendCommand(topic, payload)
  }

  private def sendDiscoveryMessages(): Unit = {
    val createIrrigationModeDiscoveryMessages = Seq(
      (mqttDiscovery.getDiscoveryTopic("select", s"mode"),
        mqttDiscovery.createSelectMessage("Irrigation mode", "mode", MainActor.IrrigationMode.list),
        useRetainForDiscovery)
      )
    val createIrrigationDurationDiscoveryMessages = Seq(
      (mqttDiscovery.getDiscoveryTopic("select", s"duration"),
        mqttDiscovery.createSelectMessage("Irrigation duration", "duration", MainActor.IrrigationDuration.list),
        useRetainForDiscovery)
      )
    val createZoneModeDiscoveryMessages = config.zones.map { case (zoneId, zone) =>
      (mqttDiscovery.getDiscoveryTopic("select", s"zoneMode_${zoneId.id}"),
        mqttDiscovery.createSelectMessage(s"Zone '${zoneId.id}' mode", s"zoneMode_${zoneId.id}", ZonesActor.ZoneMode.list),
        useRetainForDiscovery)
    }.toSeq
    val createZoneStateDiscoveryMessages = config.zones.map { case (zoneId, zone) =>
      (mqttDiscovery.getDiscoveryTopic("switch", s"zoneState_${zoneId.id}"),
        mqttDiscovery.createSwitchMessage(s"Zone '${zoneId.id}' state", s"zoneState_${zoneId.id}"),
        useRetainForDiscovery)
    }.toSeq
    val createLwtMessage = Seq((mqttDiscovery.getLWT, "Online", true))

    val messages = Seq.concat[(String, String, Boolean)](
      createIrrigationModeDiscoveryMessages,
      createIrrigationDurationDiscoveryMessages,
      createZoneModeDiscoveryMessages,
      createZoneStateDiscoveryMessages,
      createLwtMessage)

    mqtt.sendCommands(messages)
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
        case "duration"  => context.parent ! MainActor.SetDuration(commandString)
        case "zoneState" => context.parent ! MainActor.ToggleZoneIrrigation(id, commandString)
        case "zoneMode"  => context.parent ! MainActor.SetZoneMode(id, commandString)
        case "exit"      => context.parent ! MainActor.Exit; killSwitch.shutdown()
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
//  def props = Props[MqttActor]()

  sealed trait In
  case object PublishDiscoveryMessages extends In
  case class PublishIrrigationMode(payload: String) extends In
  case class PublishIrrigationDuration(payload: String) extends In
  final case class PublishAllState(zonesState: Map[String, String]) extends In
  final case class PublishState(id: String, state: String) extends In
  final case class PublishAllMode(zonesState: Map[String, String]) extends In
  final case class PublishMode(id: String, state: String) extends In
  final case class SendCommand(topic: String, commandPayload: String) extends In
}
