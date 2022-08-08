package lkankowski.irrigation

import akka.actor.{ActorContext, ActorSystem}
import akka.stream.alpakka.mqtt._
import akka.stream.alpakka.mqtt.scaladsl.{MqttSink, MqttSource}
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.Done
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Future

trait Mqtt {
  def subscribeToCommandTopic(commandTopicPrefix: String, fn: (String, String) => Unit): Unit
  def sendDiscoveryMessages(): Unit
  def publishIrrigationMode(payload: String): Unit
  def publishIrrigationInterval(payload: String): Unit
  def publishAllSwitchState(states: Map[String, String]): Unit
  def publishSwitchState(id: String, state: String): Unit
  def publishAllZoneMode(states: Map[String, String]): Unit
  def publishZoneMode(id: String, state: String): Unit
}

object Mqtt {
  def apply(config: Config, mqttDiscovery: MqttDiscovery)(implicit context: ActorContext): Mqtt = new Mqtt {

    private val logger: Logger = LoggerFactory.getLogger(this.getClass)
    private implicit val system: ActorSystem = context.system
    import system.dispatcher

    private val clientId = s"${config.general.id}-Client-cmd"

    private val lastWill: MqttMessage = MqttMessage(mqttDiscovery.getLWT, ByteString("Offline"))
      .withQos(MqttQoS.atLeastOnce)
      .withRetained(true)

    private val connectionSettings: MqttConnectionSettings =
      MqttConnectionSettings(s"tcp://${config.mqtt.host}:${config.mqtt.port}", "N/A", new MemoryPersistence)
        .withAuth(config.mqtt.username.getOrElse(""), config.mqtt.password.getOrElse(""))
        .withAutomaticReconnect(true)
        .withWill(lastWill)

    def subscribeToCommandTopic(commandTopicPrefix: String, commandCallback: (String, String) => Unit): Unit = {
      val subscriptions = Map(commandTopicPrefix + "/+" -> MqttQoS.atLeastOnce)  // multiple topics => MqttSubscriptions(Map(topic -> Qos))

      val source: Source[MqttMessage, Future[Done]] = MqttSource.atMostOnce(
        connectionSettings.withClientId(s"Sub: $clientId"),
        MqttSubscriptions(subscriptions),
        8
      )

      val (subscribed, streamCompletion) = source
        .wireTap(each => {
          logger.info(s"Sub: $clientId received payload: ${each.payload.utf8String}")
          commandCallback(each.topic, each.payload.utf8String)
        })
        //.via()
        .toMat(Sink.ignore)(Keep.both)
        .run()

      subscribed.onComplete {each =>
        logger.info(s"Sub: $clientId subscribed: $each")
        context.parent ! MainActor.InitialisationComplete
      }
      streamCompletion.recover { case ex => logger.error(s"Sub stream failed with: ${ex.getCause}") }
    }

    def sendDiscoveryMessages(): Unit = {
      val createIrrigationModeDiscoveryMessages = Seq(
        MqttMessage(mqttDiscovery.getDiscoveryTopic("select", s"mode"),
                    mqttDiscovery.createSelectMessage("Irrigation mode", "mode", MainActor.IrrigationMode.list))
          .withQos(MqttQoS.atLeastOnce)
        //          .withRetained(true) // for development
      )
      val createIrrigationIntervalDiscoveryMessages = Seq(
        MqttMessage(mqttDiscovery.getDiscoveryTopic("select", s"interval"),
                    mqttDiscovery.createSelectMessage("Irrigation interval", "interval", MainActor.IrrigationInterval.list))
          .withQos(MqttQoS.atLeastOnce)
        //          .withRetained(true) // for development
      )
      val createZoneModeDiscoveryMessages = config.zones.map { case (zoneId, zone) =>
        MqttMessage(mqttDiscovery.getDiscoveryTopic("select", s"zoneMode_${zoneId.id}"),
                    mqttDiscovery.createSelectMessage(s"Zone '${zoneId.id}' mode", s"zoneMode_${zoneId.id}", ZonesActor.ZoneMode.list))
          .withQos(MqttQoS.atLeastOnce)
        //          .withRetained(true) // for development
      }.toSeq
      val createZoneStateDiscoveryMessages = config.zones.map { case (zoneId, zone) =>
        MqttMessage(mqttDiscovery.getDiscoveryTopic("switch", s"zoneState_${zoneId.id}"),
                    mqttDiscovery.createSwitchMessage(s"Zone '${zoneId.id}' state", s"zoneState_${zoneId.id}"))
          .withQos(MqttQoS.atLeastOnce)
        //          .withRetained(true) // for development
      }.toSeq
      val createLwtMessage = MqttMessage(mqttDiscovery.getLWT, ByteString("Online"))
        .withQos(MqttQoS.atLeastOnce)
        .withRetained(true)

      val messages =
        createIrrigationModeDiscoveryMessages ++
        createIrrigationIntervalDiscoveryMessages ++
        createZoneModeDiscoveryMessages ++
        createZoneStateDiscoveryMessages ++
        Seq(createLwtMessage)
      val sink = MqttSink(connectionSettings.withClientId(s"Pub Startup: $clientId"), MqttQoS.AtLeastOnce)

      Source(messages).runWith(sink)
    }

    def publishIrrigationMode(payload: String): Unit = {
      val messages = Seq(
        MqttMessage(mqttDiscovery.getStateTopic("mode"), ByteString(s"{ \"mode\": \"${payload}\" }"))
          .withQos(MqttQoS.atLeastOnce)
        )
      Source(messages).runWith(MqttSink(connectionSettings.withClientId(s"publishIrrigationMode: $clientId"), MqttQoS.AtLeastOnce))
    }

    def publishIrrigationInterval(payload: String): Unit = {
      val messages = Seq(
        MqttMessage(mqttDiscovery.getStateTopic("interval"), ByteString(s"{ \"mode\": \"${payload}\" }"))
          .withQos(MqttQoS.atLeastOnce)
        )
      Source(messages).runWith(MqttSink(connectionSettings.withClientId(s"publishIrrigationInterval: $clientId"), MqttQoS.AtLeastOnce))
    }

    def publishAllSwitchState(states: Map[String, String]): Unit = {
      val messages = states.map { case (zoneId, payload) =>
        MqttMessage(mqttDiscovery.getStateTopic(s"zoneState_${zoneId}"), ByteString(s"{ \"state\": \"${payload}\" }"))
          .withQos(MqttQoS.atLeastOnce)
      }
      Source(messages).runWith(MqttSink(connectionSettings.withClientId(s"publishAllSwitchState: $clientId"), MqttQoS.AtLeastOnce))
    }

    def publishSwitchState(id: String, payload: String): Unit = {
      val messages = Seq(
        MqttMessage(mqttDiscovery.getStateTopic(s"zoneState_${id}"), ByteString(s"{ \"state\": \"${payload}\" }"))
          .withQos(MqttQoS.atLeastOnce)
        )
      Source(messages).runWith(MqttSink(connectionSettings.withClientId(s"publishSwitchState: $clientId"), MqttQoS.AtLeastOnce))
    }

    def publishAllZoneMode(states: Map[String, String]): Unit = {
      val messages = states.map { case (zoneId, mode) =>
        MqttMessage(mqttDiscovery.getStateTopic(s"zoneMode_${zoneId}"), ByteString(s"{ \"mode\": \"${mode}\" }"))
          .withQos(MqttQoS.atLeastOnce)
      }
      Source(messages).runWith(MqttSink(connectionSettings.withClientId(s"publishAllZoneMode: $clientId"), MqttQoS.AtLeastOnce))
    }

    def publishZoneMode(id: String, mode: String): Unit = {
      val messages = Seq(
        MqttMessage(mqttDiscovery.getStateTopic(s"zoneMode_${id}"), ByteString(s"{ \"mode\": \"${mode}\" }"))
          .withQos(MqttQoS.atLeastOnce)
        )
      Source(messages).runWith(MqttSink(connectionSettings.withClientId(s"publishZoneMode: $clientId"), MqttQoS.AtLeastOnce))
    }
  }
}
