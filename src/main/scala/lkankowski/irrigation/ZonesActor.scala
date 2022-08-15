package lkankowski.irrigation

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.event.Logging
import lkankowski.irrigation.Config._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext

final class ZonesActor(config: Config, mqttActor: ActorRef, schedulerActor: ActorRef) extends Actor {

  import ZonesActor._

  implicit val executionContext: ExecutionContext = context.system.getDispatcher

  private val zones = Zones(config.zones)

  private val logger = Logging(context.system, this)
  implicit val system: ActorSystem = context.system

  logger.info(s"${ZonesActor.Name}: starting")

  override def receive: Receive = {
    case PublishAllState  =>
      println("PublishAllState")
      mqttActor ! MqttActor.PublishAllState(zones.getAllStates.map { case (key, state) => (key.id, state.payload) })

    case PublishAllZoneMode =>
      mqttActor ! MqttActor.PublishAllMode(zones.getAllModes.map { case (key, mode) => (key.id, mode.payload) })

    case ToggleZoneIrrigation(id, payload) =>
      //TODO: duration!
      logger.info(s"ToggleZoneIrrigation: id=${id}, payload=${payload}")
      val newState = ZoneState(payload)
      val zoneId = ZoneId(id)

      zones.getAllStates.map { // create tuple: (id, oldState, newState)
        case (id, oldState) =>  (id, oldState, if (id == zoneId) newState else (if (newState.isOn) ZoneOff else oldState))
      }.filter { // filter out zones without state change
        case (_, oldState, newState) => oldState != newState
      }.toSeq.sortWith { (s1, s2) => s1._3.isOn < s2._3.isOn } // first turn off, then turn on
        .foreach {
          case (id, _, newState) => config.zones(id).valveMqtt.foreach { mqttValve =>
            logger.info(s"ToggleZoneIrrigation: Zone=${id.id}, state=${newState.payload}")
            toggleValve(mqttValve, newState)
            zones.changeZoneState(id, newState)
            mqttActor ! MqttActor.PublishState(id.id, newState.payload)
          }
        }

    case SetZoneMode(id, payload) =>
      val zoneId = ZoneId(id)
      zones.changeZoneMode(zoneId, ZoneMode(payload))
      mqttActor ! MqttActor.PublishMode(id, payload)

    case StartAllZonesIrrigation(duration) =>
      context.self ! ToggleZoneIrrigation(config.zones.head._1.id, ZoneState.PayloadOn)

      val zonesToBeScheduled = config.zones.tail.toSeq.map {
        case (id, _) => (id.id, ToggleZoneIrrigation(id.id, ZoneState.PayloadOn))
      }
      val turnOffLastCommand = Seq((zonesToBeScheduled.last._1, ToggleZoneIrrigation(zonesToBeScheduled.last._1, ZoneState.PayloadOff)))

      schedulerActor ! SchedulerActor.scheduleZoneCommands(
        zonesToBeScheduled ++ turnOffLastCommand,
        duration,
        duration,
      )
  }

  private def toggleValve(valveCommand: MqttCommand, state: ZoneState): Unit = {
    val commandPayload = if (state.isOn) valveCommand.commandOn.getOrElse(MqttDiscovery.payloadOn) else valveCommand.commandOff.getOrElse(MqttDiscovery.payloadOff)
    mqttActor ! MqttActor.SendCommand(valveCommand.commandTopic, commandPayload)
  }
}

object ZonesActor {
  val Name = "Zones-actor"
//  def props = Props[ZonesActor]()

  sealed trait In
  case object PublishAllState extends In
  case object PublishAllZoneMode extends In
  final case class ToggleZoneIrrigation(id: String, payload: String) extends In
  final case class SetZoneMode(id: String, payload: String) extends In
  final case class StartAllZonesIrrigation(duration: FiniteDuration) extends In

  trait Zones {
    def changeZoneState(id: ZoneId, newState: ZoneState): Unit
    def changeZoneMode(id: ZoneId, newMode: ZoneMode): Unit
    def getAllStates: Map[ZoneId, ZoneState]
    def getAllModes: Map[ZoneId, ZoneMode]
  }

  object Zones {
    import lkankowski.irrigation._

    def apply(configZones: Map[ZoneId, Config.Zone]): Zones = new Zones {

      // mutable state - temporary?
      var zones = configZones.map { case (id, _) => (id, Zone(id, ZoneOff, ZoneModeAuto)) }

      def changeZoneState(id: ZoneId, newState: ZoneState): Unit =
        zones = zones.map { case (key, zone) =>
          if (id == key) (key, zone.copy(state = newState))
          else (key, zone)
        }

      def changeZoneMode(id: ZoneId, newMode: ZoneMode): Unit =
        zones = zones.map { case (key, zone) =>
          if (id == key) (key, zone.copy(mode = newMode))
          else (key, zone)
        }

      def getAllStates: Map[ZoneId, ZoneState] = zones.map { case (key, zone) => (key, zone.state) }

      def getAllModes: Map[ZoneId, ZoneMode] = zones.map { case (key, zone) => (key, zone.mode) }
    }
  }

  case class Zone(id: ZoneId, state: ZoneState, mode: ZoneMode)

  sealed abstract class ZoneState(val payload: String, val isOn: Boolean)
  case object ZoneOn extends ZoneState(ZoneState.PayloadOn, true)
  case object ZoneOff extends ZoneState(ZoneState.PayloadOff, false)
  object ZoneState {
    val PayloadOn = MqttDiscovery.payloadOn
    val PayloadOff = MqttDiscovery.payloadOff

    def apply(payload: String): ZoneState = if (payload == PayloadOn) ZoneOn else ZoneOff
    def negate(zoneState: ZoneState): ZoneState = if (zoneState.isOn) ZoneOff else ZoneOn
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
