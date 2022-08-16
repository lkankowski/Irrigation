# Irrigation System

## Prerequisites
- MQTT Broker or local docker running:
```shell
cd docker
docker-compose up -d mosquitto
```

## MQTT protocol
First you need to enter into Mosquitto container:
```shell
docker-compose exec mosquitto bash
```
And now you can execute various commands.

* View all MQTT traffic on local MQTT Broker:
```shell
mosquitto_sub -h 127.0.0.1 -p 1883 -t '#' -v
```
* Send `Auto` message into command topic `cmnd/irrigation1/mode`:
```shell
mosquitto_pub -h 127.0.0.1 -p 1883 -t 'cmnd/irrigation1/mode' -m 'Auto'
```
Clear retained message:
```shell
mosquitto_pub -h 127.0.0.1 -p 1883 -t 'homeassistant/select/irrigation1_zone_thujas/config' -n -r
```
* Temporary exit command:
```shell
mosquitto_pub -h 127.0.0.1 -p 1883 -t 'cmnd/irrigation1/exit' -m 'exit'
```

## Inspirations & other sources
- Akka MQTT - https://doc.akka.io/docs/alpakka/current/mqtt.html
- Akka MQTT Example - https://github.com/pbernet/akka_streams_tutorial
- FS2-MQTT - https://index.scala-lang.org/user-signal/fs2-mqtt (pure functional client)
- circe-yaml - https://index.scala-lang.org/circe/circe-yaml, https://github.com/circe/circe-yaml

## Notes
- newer version of Mosquitto - https://registry.hub.docker.com/_/eclipse-mosquitto
- https://stackoverflow.com/questions/33462357/how-to-end-an-infinite-akka-stream
- https://scalameta.org/scalafmt/docs/configuration.html
