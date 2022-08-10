package lkankowski.irrigation

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.event.Logging
import cats.implicits._

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext

final class ZonesActor(config: Config, mqttActor: ActorRef, schedulerActor: ActorRef) extends Actor {

  import ZonesActor._

  implicit val executionContext: ExecutionContext = context.system.getDispatcher

  // mutable state - temporary?
  private val zonesMode: mutable.Map[ZoneId, ZoneMode] = mutable.Map(config.zones.map { case (id, _) => id -> ZoneModeAuto }.toSeq: _*)
  private val zonesState: mutable.Map[ZoneId, ZoneState] = mutable.Map(config.zones.map { case (id, _) => id -> ZoneOff }.toSeq: _*)

  private val logger = Logging(context.system, this)
  implicit val system: ActorSystem = context.system

  logger.info(s"${ZonesActor.Name}: starting")

  override def receive: Receive = {
    case PublishAllState  =>
      mqttActor ! MqttActor.PublishAllState(zonesState.map { case (zoneId, zoneState) => (zoneId.id, zoneState.payload) }.toMap)

    case SetZoneState(id, payload) =>
      val state = ZoneState(payload)
      val zoneId = ZoneId(id)
      zonesState(zoneId) = state
      mqttActor ! MqttActor.PublishState(id, state.payload)
      config.zones(zoneId).valveMqtt.foreach {
        case MqttCommand(topic, payloadOn, payloadOff) =>
          val commandPayload = if (state.isOn) payloadOn.getOrElse(MqttDiscovery.payloadOn) else payloadOff.getOrElse(MqttDiscovery.payloadOff)
          mqttActor ! MqttActor.SendCommand(topic, commandPayload)
      }

    case PublishAllZoneMode =>
      mqttActor ! MqttActor.PublishAllMode(zonesMode.map { case (zoneId, zoneMode) => (zoneId.id, zoneMode.payload) }.toMap)

    case SetZoneMode(id, payload) =>
      zonesMode(ZoneId(id)) = ZoneMode(payload)
      mqttActor ! MqttActor.PublishMode(id, payload)

    case StartAllZonesIrrigation(duration) =>
//      mqttActor ! MqttActor.SendCommand
//      zonesMode.toSeq
//        .filter { case (_, mode) => mode == ZoneModeOff }
//        .map { case (id, _) => id.id }
//        .zipWithIndex
//        .foreach { (id, index) =>
//          context.system.scheduler.scheduleOnce(duration * index) {
//            mqttActor ! MqttActor.SendCommand
//          }
//        }

      // cancel all previous irrigation jobs
      // run sequentially:
      // zone 1: start now (0x duration time) for duration time
      // zone 2: start after 1x duration time for duration time
      // zone 2: start after 2x duration time for duration time
  }
}

object ZonesActor {
  val Name = "Zones-actor"
//  def props = Props[ZonesActor]()

  sealed trait In
  case object PublishAllState extends In
  case object PublishAllZoneMode extends In
  final case class SetZoneState(id: String, payload: String) extends In
  final case class SetZoneMode(id: String, payload: String) extends In
  final case class StartAllZonesIrrigation(duration: Duration) extends In

  sealed abstract class ZoneState(val payload: String, val isOn: Boolean)
  case object ZoneOn extends ZoneState(ZoneState.PayloadOn, true)
  case object ZoneOff extends ZoneState(ZoneState.PayloadOff, false)
  object ZoneState {
    val PayloadOn = MqttDiscovery.payloadOn
    val PayloadOff = MqttDiscovery.payloadOff

    def apply(payload: String): ZoneState = if (payload == PayloadOn) ZoneOn else ZoneOff
  }

  sealed abstract class ZoneMode(val payload: String)
  case object ZoneModeOff extends ZoneMode(ZoneMode.Off)
  case object ZoneModeAuto extends ZoneMode(ZoneMode.Auto)
  case object ZoneModeOneTime extends ZoneMode(ZoneMode.OneTime)
  object ZoneMode {
    val Off = "Off"
    val Auto = "Auto"
    val OneTime = "OneTime"
    val list = List(Off, Auto, OneTime)

    def apply(payload: String): ZoneMode = payload match {
      case Off     => ZoneModeOff
      case Auto    => ZoneModeAuto
      case OneTime => ZoneModeOneTime
    }
  }
}
