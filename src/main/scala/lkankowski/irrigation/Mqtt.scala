package lkankowski.irrigation

import akka.actor.{ActorContext, ActorSystem}
import akka.stream.alpakka.mqtt._
import akka.stream.alpakka.mqtt.scaladsl.{MqttSink, MqttSource}
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.Done
import lkankowski.irrigation.IrrigationMain.MainActor
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Future

trait Mqtt {
  def initialize(discoveryTopicPrefix: Option[String], name: String): Unit
  def publishMode(mode: Int): Unit
}

object Mqtt {
  def apply(host: String, port: Int, username: Option[String], password: Option[String], id: String)(implicit context: ActorContext): Mqtt = new Mqtt {

    val logger: Logger = LoggerFactory.getLogger(this.getClass)
    implicit val system: ActorSystem = context.system
    import system.dispatcher

    val clientId = s"$id-Client-cmd"

    val lastWill: MqttMessage = MqttMessage(getLWT, ByteString("Offline"))
      .withQos(MqttQoS.atLeastOnce)
      .withRetained(true)

    val connectionSettings: MqttConnectionSettings =
      MqttConnectionSettings(s"tcp://${host}:${port}", "N/A", new MemoryPersistence)
        .withAuth(username.getOrElse(""), password.getOrElse(""))
        .withAutomaticReconnect(true)
        .withWill(lastWill)

    def initialize(discoveryTopicPrefix: Option[String], name: String): Unit = {

      val source: Source[MqttMessage, Future[Done]] = MqttSource.atMostOnce(
        connectionSettings.withClientId(s"Sub: $clientId"),
        MqttSubscriptions(getModeCommandTopic, MqttQoS.atLeastOnce),
        8
      )

      val (subscribed, streamCompletion) = source
        .wireTap(each => {
          logger.info(s"Sub: $clientId received payload: ${each.payload.utf8String}")
          processCommand(each.payload.utf8String)
        })
        //.via()
        .toMat(Sink.ignore)(Keep.both)
        .run()

      subscribed.onComplete(each => logger.info(s"Sub: $clientId subscribed: $each"))
      streamCompletion.recover { case ex => logger.error(s"Sub stream failed with: ${ex.getCause}") }

      val messages = Seq(
        MqttMessage(getDiscoveryTopic(discoveryTopicPrefix.getOrElse("homeassistant")), getDiscoveryMessage(name, id))
          .withQos(MqttQoS.atLeastOnce)
          .withRetained(true),
        MqttMessage(getLWT, ByteString("Online"))
          .withQos(MqttQoS.atLeastOnce)
          .withRetained(true)
      )
      val sink = MqttSink(connectionSettings.withClientId(s"Pub Startup: $clientId"), MqttQoS.AtLeastOnce)

      Source(messages).runWith(sink)
    }

    def processCommand(commandString: String): Unit = {
      import cats.syntax.functor._
      import io.circe.Decoder
      import io.circe.generic.semiauto._
      import io.circe.parser._

      implicit val testDecoder   : Decoder[Test] = deriveDecoder[Test]
      implicit val commandDecoder: Decoder[Command] = List[Decoder[Command]](testDecoder.widen).reduceLeft(_ or _)

      logger.info(s"MQTT command topic received: $commandString")

      parse(commandString) match {
        case Left(_) =>
          commandString match {
            case Command.Exit     => context.parent ! MainActor.Exit
            case Command.ModeOff  => context.parent ! MainActor.ModeOff
            case Command.ModeAuto => context.parent ! MainActor.ModeAuto
            case Command.ModeOneTime => context.parent ! MainActor.ModeOneTime
          }
        case Right(json) =>
          json.as[Command] match {
            case Left(_) => logger.info(s"JSON command decode failure!: ${json.noSpaces}")
            case Right(command) =>
              command match {
                case Test(test) => logger.info(s"Command Test: $test")
              }
          }
      }
    }

    def publishMode(mode: Int): Unit = {
      val messages = Seq(MqttMessage(getModeStatusTopic, ByteString(mode.toString)))
      val sink = MqttSink(connectionSettings.withClientId(s"Pub status: $clientId"), MqttQoS.AtLeastOnce)

      Source(messages).runWith(sink)
    }


    private def getDiscoveryTopic(prefix: String): String = s"${prefix}/select/${id}/config"

    private def getDiscoveryMessage(name: String, id: String): ByteString = {
      import io.circe.generic.auto._
      import io.circe.syntax.EncoderOps

      ByteString(MqttSelectDiscoveryMessage(
        name,
        getModeCommandTopic,
        getModeStatusTopic,
        List(Command.ModeOff, Command.ModeAuto, Command.ModeOneTime),
        "{{value_json.MODE}}",
        id,
        getLWT,
        "Online",
        "Offline",
        Device(List(id))
        ).asJson.noSpaces)
    }

    private def getLWT = s"tele/${id}/LWT"
    private def getModeCommandTopic = s"cmnd/${id}/MODE"
    private def getModeStatusTopic = s"tele/${id}/MODE"
  }


  case class MqttSwitchDiscoveryMessage(
    name: String,
    command_topic: String,
    state_topic: String,
    payload_on: String,
    payload_off: String,
    value_template: String,
    unique_id: String,
    availability_topic: String,
    payload_available: String,
    payload_not_available: String,
    device: Device,
  )

  case class MqttSelectDiscoveryMessage(
    name: String,
    command_topic: String,
    state_topic: String,
    options: List[String],
    value_template: String,
    unique_id: String,
    availability_topic: String,
    payload_available: String,
    payload_not_available: String,
    device: Device,
  )

  case class Device(identifiers: List[String])

  sealed trait Command
  final case class Test(test: String) extends Command
  object Command {
    val Exit = "exit"
    val ModeOff = "Off"
    val ModeAuto = "Auto"
    val ModeOneTime = "One Time"
  }
}
