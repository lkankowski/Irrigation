package lkankowski.irrigation

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.event.Logging

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.ExecutionContext
import Config._

final class MainActor(config: Config) extends Actor {

  import MainActor._

  private val mqttRef: ActorRef = context.actorOf(Props(new MqttActor(config)), MqttActor.Name)
  private val schedulerRef: ActorRef = context.actorOf(Props(new SchedulerActor(config.general.timeZone)), SchedulerActor.Name)
  private val zonesRef: ActorRef = context.actorOf(Props(new ZonesActor(config, mqttRef, schedulerRef)), ZonesActor.Name)
  implicit val executionContext: ExecutionContext = context.system.getDispatcher
  val logger = Logging(context.system, this)

  logger.info("MainActor started")

  // temporary
  var currentMode: IrrigationMode = IrrigationModeAuto
  var currentDuration: IrrigationDuration = IrrigationDuration.defaultDuration

  override def receive: Receive = {

    case SetMode(payload)          =>
      logger.info(s"MainActor: SetMode($payload)")
      IrrigationMode(payload) match {
        case IrrigationModeNow =>
          zonesRef ! ZonesActor.StartAllZonesIrrigation(currentDuration.duration)
        case _ =>
          currentMode = IrrigationMode(payload)
      }

      mqttRef ! MqttActor.PublishIrrigationMode(currentMode.payload)

    case SetDuration(payload) =>
      logger.info(s"MainActor: SetDuration($payload)")
      currentDuration = IrrigationDuration(payload)
      mqttRef ! MqttActor.PublishIrrigationDuration(currentDuration.getPayload)

    case ToggleZoneIrrigation(id, payload) =>
      logger.info(s"MainActor: SetZoneState($id, $payload)")
      zonesRef ! ZonesActor.ToggleZoneIrrigation(id, payload)

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
        mqttRef ! MqttActor.PublishIrrigationDuration(currentDuration.getPayload)
        zonesRef ! ZonesActor.PublishAllState
        zonesRef ! ZonesActor.PublishAllZoneMode
        schedulerRef ! SchedulerActor.scheduleIrrigation(config.scheduler)
      }

    case IrrigationScheduleOccurred =>
      logger.info("MainActor: IrrigationScheduleOccurred")
      if (currentMode != IrrigationModeOff) {
        zonesRef ! ZonesActor.StartAllZonesIrrigation(currentDuration.duration)
      }
  }
}

object MainActor {
  sealed trait In
  case object Exit extends In
  final case class SetMode(payload: String) extends In
  final case class SetDuration(payload: String) extends In
  final case class ToggleZoneIrrigation(id: String, payload: String) extends In
  final case class SetZoneMode(id: String, payload: String) extends In
  case object InitialisationComplete extends In
  case object IrrigationScheduleOccurred extends In

  sealed abstract class IrrigationMode(val payload: String)
  case object IrrigationModeOff extends IrrigationMode(IrrigationMode.Off)
  case object IrrigationModeAuto extends IrrigationMode(IrrigationMode.Auto)
  case object IrrigationModeOneTime extends IrrigationMode(IrrigationMode.OneTime)
  case object IrrigationModeNow extends IrrigationMode(IrrigationMode.Now)
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
      case Now     => IrrigationModeNow
    }
  }

  case class IrrigationDuration(duration: FiniteDuration) {
    def getPayload: String = s"${duration.toSeconds}min" //TODO: switch to minutes after demo
  }

  case object IrrigationDuration {
    val list: List[String] = List("3min", "5min", "7min", "10min", "15min")
    val defaultDuration: IrrigationDuration = IrrigationDuration(10.second) //TODO: switch to minutes after demo

    def apply(payload: String): IrrigationDuration = {
      if (list.contains(payload)) {
        IrrigationDuration(payload.replace("min", "").toInt.second) //TODO: switch to minutes after demo
      } else {
        defaultDuration
      }
    }
  }
}
