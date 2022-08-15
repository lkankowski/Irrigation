package lkankowski.irrigation


trait MqttDiscovery {
  def getCommandTopicPrefix: String
  def getStateTopic(suffix: String): String
  def getLWT: String
  def getDiscoveryTopic(deviceType: String, suffix: String): String
  def createSelectMessage(name: String, id: String, options: List[String]): String
  def createSwitchMessage(name: String, suffix: String): String
}

object MqttDiscovery {
  val payloadOn = "ON"
  val payloadOff = "OFF"

  def apply(generalId: String, discoveryPrefix: Option[String]): MqttDiscovery = new MqttDiscovery {

    def getCommandTopicPrefix = s"cmnd/${generalId}"
    def getStateTopicPrefix = s"tele/${generalId}"
    def getLWT = s"tele/${generalId}/LWT"
    def getStateTopic(suffix: String): String = s"${getStateTopicPrefix}/${suffix}"
    def getDiscoveryTopic(deviceType: String, suffix: String): String =
      s"${discoveryPrefix.getOrElse("homeassistant")}/${deviceType}/${generalId}_${suffix}/config"

    def createSelectMessage(name: String, prefix: String, options: List[String]): String = {
      createSelectDiscoveryMessageFactory(
        id = prefix,
        name = name,
        command_topic = s"${getCommandTopicPrefix}/${prefix}",
        state_topic = s"${getStateTopicPrefix}/${prefix}",
        options = options,
//        value_template = s"{{value_json.${prefix}}}"
      )
    }

    def createSwitchMessage(name: String, suffix: String): String = {
      createSwitchDiscoveryMessageFactory(
        id = suffix,
        name = name,
        command_topic = s"${getCommandTopicPrefix}/${suffix}",
        state_topic = s"${getStateTopicPrefix}/${suffix}",
        )
    }

    private def createSelectDiscoveryMessageFactory(
      id: String,
      name: String,
      command_topic: String,
      state_topic: String,
      options: List[String],
      value_template: String = "{{value_json.mode}}",
      unique_id: Option[String] = None,
      availability_topic: String = getLWT,
      payload_available: String = "Online",
      payload_not_available: String = "Offline",
      device: Option[Device] = None,
    ): String = {
      import io.circe.generic.auto._
      import io.circe.syntax.EncoderOps

      MqttSelectDiscoveryMessage(
        name = name,
        command_topic = command_topic,
        state_topic = state_topic,
        options = options,
        value_template = value_template,
        unique_id = unique_id.getOrElse(id),
        availability_topic = availability_topic,
        payload_available = payload_available,
        payload_not_available = payload_not_available,
        device = device.getOrElse(Device(List(generalId)))
        ).asJson.noSpaces
    }

    def createSwitchDiscoveryMessageFactory(
      id: String,
      name: String,
      command_topic: String,
      state_topic: String,
      payload_on: String = payloadOn,
      payload_off: String = payloadOff,
      value_template: String = "{{value_json.state}}",
      unique_id: Option[String] = None,
      availability_topic: String = getLWT,
      payload_available: String = "Online",
      payload_not_available: String = "Offline",
      device: Option[Device] = None,
    ): String = {
      import io.circe.generic.auto._
      import io.circe.syntax.EncoderOps

      MqttSwitchDiscoveryMessage(
        name = name,
        command_topic = command_topic,
        state_topic = state_topic,
        payload_on = payload_on,
        payload_off = payload_off,
        value_template = value_template,
        unique_id = unique_id.getOrElse(id),
        availability_topic = availability_topic,
        payload_available = payload_available,
        payload_not_available = payload_not_available,
        device = device.getOrElse(Device(List(generalId)))
        ).asJson.noSpaces
    }
  }
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
