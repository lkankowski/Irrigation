package lkankowski.irrigation

import akka.actor.{Actor, Cancellable}
import akka.event.Logging
import com.typesafe.akka.extension.quartz.QuartzSchedulerExtension
import lkankowski.irrigation.Config._

import java.time.LocalTime
import java.util.TimeZone
import java.util.TimeZone.getTimeZone
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext

final class SchedulerActor(timeZoneConfig: Option[String]) extends Actor {

  import SchedulerActor._

  implicit val executionContext: ExecutionContext = context.system.getDispatcher
  private val logger = Logging(context.system, this)

  logger.info("SchedulerActor: started")

  private val scheduler = QuartzSchedulerExtension(context.system)

  private var zoneScheduledCommands: Seq[(String, Cancellable)] = Seq.empty

  private val timeZone = timeZoneConfig match {
    case None => getTimeZone("Europe/Warsaw")
    case Some(timeZoneString) =>
      if (TimeZone.getAvailableIDs.toList.contains(timeZoneString)) getTimeZone(timeZoneString)
      else getTimeZone("Europe/Warsaw")
  }

  override def receive: Receive = {
    case scheduleIrrigation(configSchedule) =>
      configSchedule.map(time => s"0 ${time.getMinute} ${time.getHour} ? * *").foreach { scheduleCronExpression =>
        try {
          scheduler.createJobSchedule(
            name = s"Irrigation time: ${scheduleCronExpression}",
            receiver = context.parent,
            msg = MainActor.IrrigationScheduleOccurred,
            cronExpression = scheduleCronExpression,
            timezone = timeZone,
            )
        } catch {
          case iae: IllegalArgumentException => logger.error(s"Invalid time scheduled: $iae")
        }
      }

    case scheduleZoneCommands(commands, delay, duration) =>
      logger.info(s"SchedulerActor: scheduleZoneCommand: $commands, $delay, $duration")
      println(s"scheduleZoneCommands: Sender 1: ${sender()}")

      zoneScheduledCommands.foreach { case (id, job) =>
        val result = job.cancel()
        logger.info(s"scheduleZoneCommands: canceling job for id=$id ($result)")
      }

      zoneScheduledCommands = commands.zipWithIndex.map { case ((id, command), idx) =>
        val senderRef = sender()
        val cancellableJob = context.system.scheduler.scheduleOnce(delay + (duration * idx)) {
          senderRef ! command
        }
        (id, cancellableJob)
      }

    // cancel all previous irrigation jobs
    // run sequentially:
    // zone 1: start now (0x duration time) for duration time
    // zone 2: start after 1x duration time for duration time
    // zone 2: start after 2x duration time for duration time
  }
}

object SchedulerActor {
  val Name = "Scheduler-Actor"

  sealed trait In
  final case class scheduleIrrigation(configSchedule: List[LocalTime]) extends In
  final case class scheduleZoneCommands(commands: Seq[(String, ZonesActor.In)], delay: FiniteDuration, duration: FiniteDuration) extends In
}
