package lkankowski.irrigation

import akka.actor.{ActorSystem, Props}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success, Try}

object IrrigationMain extends App {

  val logger: Logger = LoggerFactory.getLogger(this.getClass)

  val parseConfig: Try[Config] = Try(Config.loadConfig)
  val config = parseConfig match {
    case Failure(exception) => logger.info(s"Config is invalid: $exception")
    case Success(config) =>
      println(config)
      val actorSystem: ActorSystem = ActorSystem("actor-system")
      actorSystem.actorOf(Props(new MainActor(config)), "main")
  }
}
