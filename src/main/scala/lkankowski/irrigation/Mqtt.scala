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
import scala.util.Random

trait Mqtt {
  def subscribeToCommandTopic(commandTopicPrefix: String, fn: (String, String) => Unit): Unit
  def sendCommands(messages: Seq[(String, String, Boolean)]): Unit
  def sendCommand(topic: String, payload: String, retain: Boolean = false): Unit
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

    def sendCommands(messages: Seq[(String, String, Boolean)]): Unit = {
      //TODO: get rid of random - use persistent connection and some queue
      val random = Random.alphanumeric.take(31).mkString
      Source(messages.map {
        case (topic, payload, retain) =>
          MqttMessage(topic, ByteString(payload))
            .withQos(MqttQoS.atLeastOnce)
            .withRetained(retain)
      })
        .runWith(MqttSink(connectionSettings.withClientId(s"sendCommand: $clientId-$random"), MqttQoS.AtLeastOnce))
    }

    def sendCommand(topic: String, payload: String, retain: Boolean = false): Unit = {
      sendCommands(Seq((topic, payload, retain)))
    }
  }
}
