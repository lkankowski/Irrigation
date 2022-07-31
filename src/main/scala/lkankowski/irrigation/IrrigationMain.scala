package lkankowski.irrigation

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.event.Logging
import cats.implicits._
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success, Try}

object IrrigationMain extends App {
  final class MqttActor(config: Config) extends Actor {
    val logger = Logging(context.system, this)
    implicit val system: ActorSystem = context.system
//    import system.dispatcher

    logger.info(s"${MqttActor.Name}: starting")

    val mqtt = Mqtt(config.mqtt.host, config.mqtt.port, config.mqtt.username, config.mqtt.password, config.general.id)
    mqtt.initialize(config.mqtt.discoveryTopicPrefix, config.general.name)

    override def receive: Receive = {
      case MqttActor.ModeIs(mode) => mqtt.publishMode(mode)
    }
  }

  object MqttActor {
    val Name = "Mqtt-actor"
    def props = Props[MqttActor]()

    sealed trait In
    case class ModeIs(mode: Int) extends In
  }

  final class MainActor(config: Config) extends Actor {
    import MainActor._

    private val mqttRef: ActorRef = context.actorOf(Props(new MqttActor(config)), MqttActor.Name)
    val logger = Logging(context.system, this)

    logger.info("MainActor started")

    // temporary
    var currentMode: Int = 0

    override def receive: Receive = {

      case ModeOff  =>
        logger.info("MainActor: Mode OFF")
        currentMode = 0
        mqttRef ! MqttActor.ModeIs(currentMode)
      case ModeAuto =>
        logger.info("MainActor: Mode Auto - irrigation will run at specified time")
        currentMode = 1
        mqttRef ! MqttActor.ModeIs(currentMode)
      case ModeOneTime =>
        logger.info("MainActor: Mode OneTime - irrigation will run at specified time and then mode will be turned off")
        currentMode = 2
        mqttRef ! MqttActor.ModeIs(currentMode)
      case Exit =>
        mqttRef ! PoisonPill
        context.stop(self)
        context.system.terminate()
    }
  }

  object MainActor {
    sealed trait In
    case object Exit extends In
    case object ModeOff extends In
    case object ModeAuto extends In
    case object ModeOneTime extends In
  }

  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  val parseConfig: Try[Config] = Try(Config.loadConfig)
  // TODO: validate required parameters (i.e. mqtt.host)
  val config = parseConfig match {
    case Failure(exception) => logger.info(s"Config is invalid: $exception")
    case Success(config) =>
      val actorSystem: ActorSystem = ActorSystem("actor-system")
      actorSystem.actorOf(Props(new MainActor(config)), "main")
  }
}
