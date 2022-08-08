package lkankowski.irrigation

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.event.Logging
import cats.implicits._

import scala.collection.mutable

final class ZonesActor(config: Config, mqttActor: ActorRef) extends Actor {

  import ZonesActor._

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
      zonesState(ZoneId(id)) = state
      mqttActor ! MqttActor.PublishState(id, state.payload)

    case PublishAllZoneMode =>
      mqttActor ! MqttActor.PublishAllMode(zonesMode.map { case (zoneId, zoneMode) => (zoneId.id, zoneMode.payload) }.toMap)

    case SetZoneMode(id, payload) =>
      zonesMode(ZoneId(id)) = ZoneMode(payload)
      mqttActor ! MqttActor.PublishMode(id, payload)
  }
}

object ZonesActor {
  val Name = "Zones-actor"
  def props = Props[ZonesActor]()

  sealed trait In
  case object PublishAllState extends In
  case object PublishAllZoneMode extends In
  final case class SetZoneState(id: String, payload: String) extends In
  final case class SetZoneMode(id: String, payload: String) extends In

  sealed abstract class ZoneState(val isOn: Boolean, val payload: String)
  case object ZoneOn extends ZoneState(true, ZoneState.PayloadOn)
  case object ZoneOff extends ZoneState(false, ZoneState.PayloadOff)
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
