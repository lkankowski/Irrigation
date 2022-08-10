package lkankowski.irrigation

import akka.actor.Actor
import akka.event.Logging
import com.typesafe.akka.extension.quartz.QuartzSchedulerExtension

import java.time.LocalTime
import java.util.TimeZone
import java.util.TimeZone.getTimeZone
import scala.concurrent.duration.Duration

final class SchedulerActor(cron: List[LocalTime], timeZoneConfig: Option[String]) extends Actor {

  import SchedulerActor._

//  implicit val executionContext: ExecutionContext = context.system.getDispatcher
  private val logger = Logging(context.system, this)

  logger.info("SchedulerActor: started")

  private val scheduler = QuartzSchedulerExtension(context.system)

  private val timeZone = timeZoneConfig match {
    case None => getTimeZone("Europe/Warsaw")
    case Some(timeZoneString) =>
      if (TimeZone.getAvailableIDs.toList.contains(timeZoneString)) getTimeZone(timeZoneString)
      else getTimeZone("Europe/Warsaw")
  }

  try {
    cron.map(time => s"0 ${time.getMinute} ${time.getHour} ? * *").foreach { scheduleCronExpression =>
      scheduler.createJobSchedule(
        name = s"Irrigation time: ${scheduleCronExpression}",
        receiver = context.parent,
        msg = MainActor.IrrigationScheduleOccurred,
        cronExpression = scheduleCronExpression,
        timezone = timeZone,
      )
    }
  } catch {
    case iae: IllegalArgumentException => logger.error(s"Invalid time scheduled: $iae")
  }

  override def receive: Receive = {
    case scheduleZoneCommand(zoneCommand, delay, duration) =>
      logger.info(s"SchedulerActor: scheduleZoneCommand: $zoneCommand, $delay, $duration")
      scheduler.createJobSchedule(
        name = s"Zone command: ${zoneCommand.toString}",
        receiver = sender(),
        msg = zoneCommand,
        cronExpression = "???", //TODO:
        timezone = timeZone,
      )
  }
}

object SchedulerActor {
  val Name = "Scheduler-Actor"

  sealed trait In
  final case class scheduleZoneCommand(zonesCommand: ZonesActor.In, delay: Duration, duration: Duration) extends In
}
