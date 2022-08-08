package lkankowski.irrigation

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.event.Logging

import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext

final class MainActor(config: Config) extends Actor {

  import MainActor._

  private val mqttRef: ActorRef = context.actorOf(Props(new MqttActor(config)), MqttActor.Name)
  private val zonesRef: ActorRef = context.actorOf(Props(new ZonesActor(config, mqttRef)), ZonesActor.Name)
  implicit val executionContext: ExecutionContext = context.system.getDispatcher
  val logger = Logging(context.system, this)

  logger.info("MainActor started")

  // temporary
  var currentMode: IrrigationMode = IrrigationModeAuto
  var currentInterval: IrrigationInterval = IrrigationInterval.defaultInterval

  override def receive: Receive = {

    case SetMode(payload)          =>
      logger.info(s"MainActor: SetMode($payload)")
      currentMode = IrrigationMode(payload)
      mqttRef ! MqttActor.PublishIrrigationMode(currentMode.payload)
    case SetInterval(payload)      =>
      logger.info(s"MainActor: SetInterval($payload)")
      currentInterval = IrrigationInterval(payload)
      mqttRef ! MqttActor.PublishIrrigationInterval(currentInterval.getPayload)
    case SetZoneState(id, payload) =>
      logger.info(s"MainActor: SetZoneState($id, $payload)")
      zonesRef ! ZonesActor.SetZoneState(id, payload)
    case SetZoneMode(id, payload)  =>
      logger.info(s"MainActor: SetZoneMode($id, $payload)")
      zonesRef ! ZonesActor.SetZoneMode(id, payload)
    case Exit                      =>
      mqttRef ! PoisonPill
      context.stop(self)
      context.system.terminate()
    case InitialisationComplete    =>
      logger.info("MainActor: InitialisationComplete")
      mqttRef ! MqttActor.PublishDiscoveryMessages
      context.system.scheduler.scheduleOnce(1.second) {
        logger.info("MainActor: sending delayed status messages")
        mqttRef ! MqttActor.PublishIrrigationMode(currentMode.payload)
        mqttRef ! MqttActor.PublishIrrigationInterval(currentInterval.getPayload)
        zonesRef ! ZonesActor.PublishAllState
        zonesRef ! ZonesActor.PublishAllZoneMode
      }
  }
}

object MainActor {
  sealed trait In
  case object Exit extends In
  final case class SetMode(payload: String) extends In
  final case class SetInterval(payload: String) extends In
  final case class SetZoneState(id: String, payload: String) extends In
  final case class SetZoneMode(id: String, payload: String) extends In
  case object InitialisationComplete extends In

  sealed abstract class IrrigationMode(val payload: String)
  case object IrrigationModeOff extends IrrigationMode(IrrigationMode.Off)
  case object IrrigationModeAuto extends IrrigationMode(IrrigationMode.Auto)
  case object IrrigationModeOneTime extends IrrigationMode(IrrigationMode.OneTime)
  object IrrigationMode {
    val Off = "Off"
    val Auto = "Auto"
    val OneTime = "One Time"
    val Now = "Now"
    val list = List(Off, Auto, OneTime, Now)

    def apply(payload: String): IrrigationMode = payload match {
      case Off     => IrrigationModeOff
      case Auto    => IrrigationModeAuto
      case OneTime => IrrigationModeOneTime
      case Now     => IrrigationModeOneTime
    }
  }

  case class IrrigationInterval(interval: Int) {
    def getPayload: String = s"${interval}min"
  }

  case object IrrigationInterval {
    val list = List("3min", "5min", "7min", "10min", "15min")
    val defaultInterval = IrrigationInterval(10)

    def apply(payload: String): IrrigationInterval = {
      if (list.contains(payload)) {
        IrrigationInterval(payload.replace("min", "").toInt)
      } else {
        defaultInterval
      }
    }
  }
}
