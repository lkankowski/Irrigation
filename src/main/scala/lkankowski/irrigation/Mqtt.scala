package lkankowski.irrigation

import akka.actor.{ActorContext, ActorSystem}
import akka.stream.alpakka.mqtt._
import akka.stream.alpakka.mqtt.scaladsl.{MqttSink, MqttSource}
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.Done
import akka.stream.{BoundedSourceQueue, QueueCompletionResult, QueueOfferResult}
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.{Logger, LoggerFactory}
import Config._

import scala.concurrent.Future

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

    val bufferSize = 1000

    val queue: BoundedSourceQueue[MqttMessage] = Source
      .queue[MqttMessage](bufferSize)
      .to(MqttSink(connectionSettings.withClientId(s"Permanent publisher: $clientId"), MqttQoS.AtLeastOnce))
      .run()

    def subscribeToCommandTopic(commandTopicPrefix: String, commandCallback: (String, String) => Unit): Unit = {
      val subscriptions = Map(commandTopicPrefix + "/+" -> MqttQoS.atLeastOnce)  // multiple topics => MqttSubscriptions(Map(topic -> Qos))

      val source: Source[MqttMessage, Future[Done]] = MqttSource.atMostOnce(
        connectionSettings.withClientId(s"Sub: $clientId"),
        MqttSubscriptions(subscriptions),
        8
      )

      val mqttGraph = source
        .wireTap(each => {
          logger.info(s"Sub: $clientId received payload: ${each.payload.utf8String}")
          commandCallback(each.topic, each.payload.utf8String)
        })
//        .map(each => each.topic -> each.payload.utf8String)
//        .viaMat(KillSwitches.single)(Keep.right) // Allows to stop stream externally
        //.via()
        .toMat(Sink.ignore)(Keep.both)

//        : ((Future[Done], Future[Done]), Future[MqttMessage])
      val (subscribed, streamCompletion) = mqttGraph.run()
//      val ((subscriptionInitialized, listener), streamCompletion) = mqttGraph.run()

      subscribed.onComplete {each =>
        logger.info(s"Sub: $clientId subscribed: $each")
        context.parent ! MainActor.InitialisationComplete
      }
      streamCompletion.recover { case ex => logger.error(s"Sub stream failed with: ${ex.getCause}") }
    }

    def sendCommands(messages: Seq[(String, String, Boolean)]): Unit = {
      messages.map { case (topic, payload, retain) =>
        MqttMessage(topic, ByteString(payload))
          .withQos(MqttQoS.atLeastOnce)
          .withRetained(retain)
      }.foreach { message =>
        queue.offer(message) match {
          case result: QueueCompletionResult => println(s"QueueCompletionResult: $result")
          case QueueOfferResult.Enqueued     => ()
          case QueueOfferResult.Dropped      => println(s"QueueOfferResult.Dropped")
        }
      }
    }

    def sendCommand(topic: String, payload: String, retain: Boolean = false): Unit = {
      sendCommands(Seq((topic, payload, retain)))
    }
  }
}
