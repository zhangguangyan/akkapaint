package org.akkapaint.history

import java.util.UUID

import akka.NotUsed
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{ EventEnvelope, Offset, PersistenceQuery, TimeBasedUUID }
import akka.persistence.{ PersistentActor, RecoveryCompleted }
import akka.stream.{ ActorMaterializer, KillSwitch, KillSwitches, UniqueKillSwitch }
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.util.Timeout
import com.typesafe.config.Config
import org.akkapaint.history.AkkaPaintDrawEventProjection.{ NewOffsetSaved, ResetProjection, SaveNewOffset, Start }
import org.akkapaint.proto.Messages.DrawEvent
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object AkkaPaintDrawEventProjection {

  case object Start
  case class SaveNewOffset(offset: String)
  case class NewOffsetSaved(offset: String)
  case class SavedAck()
  case class ResetProjection(offset: Option[String] = None)
}

case class AkkaPaintDrawEventProjection(
    persistenceId: String,
    akkaPaintHistoryConfig: Config,
    generateConsumer: (Offset => Unit) => Sink[(DateTime, DrawEvent, Offset), NotUsed]
) extends PersistentActor {

  private val logger = LoggerFactory.getLogger(getClass)

  private implicit val materializer = ActorMaterializer()

  implicit val ec = context.system.dispatcher
  implicit val timeout: Timeout = 30.seconds

  private val readJournal =
    PersistenceQuery(context.system).readJournalFor[CassandraReadJournal](
      CassandraReadJournal.Identifier
    )

  var lastOffset = readJournal.firstOffset

  def startProjection(offset: UUID = lastOffset) = {
    val (killSwitch, _) = originalEventStream(offset)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(generateConsumer({
        case TimeBasedUUID(uuid) => self ! SaveNewOffset(uuid.toString)
        case _ =>
      }))(Keep.both)
      .run()
    context.become(running(killSwitch))
  }

  override def receiveCommand: Receive = {
    case Start =>
      logger.info("Running history generation")
      startProjection(lastOffset)
  }

  def running(killSwitch: KillSwitch): Receive = {
    case SaveNewOffset(offset) => //persist(NewOffsetSaved(offset)){ e => //is commented as for akkapaint use case there is no need to save each new offset
      lastOffset = UUID.fromString(offset)
    //}
    case ResetProjection(offset) =>
      killSwitch.shutdown()
      startProjection(offset.map(UUID.fromString).getOrElse(readJournal.firstOffset))
  }

  override def receiveRecover: Receive = {
    case NewOffsetSaved(offset) => {
      lastOffset = UUID.fromString(offset)
    }
    case RecoveryCompleted => {
      val timestamp = new DateTime(UUIDToDate.getTimeFromUUID(lastOffset)).toString("MM/dd/yyyy HH:mm:ss")
      logger.info(s"Recovering AkkaPaintHistoryGenerator, last offset: $timestamp")
      context.system.scheduler.schedule(1.minutes, 1.minutes)(saveSnapshot(NewOffsetSaved(lastOffset.toString)))
      self ! Start
    }
  }

  def originalEventStream(firstOffset: UUID): Source[(DateTime, DrawEvent, TimeBasedUUID), NotUsed] = readJournal
    .eventsByTag("draw_event", TimeBasedUUID(firstOffset))
    .map {
      case EventEnvelope(offset @ TimeBasedUUID(time), _, _, d: DrawEvent) =>
        lastOffset = time
        val timestamp = new DateTime(UUIDToDate.getTimeFromUUID(time))
        (timestamp, d, offset)
    }
}

import java.util.UUID

object UUIDToDate {
  // This method comes from Hector's TimeUUIDUtils class:
  // https://github.com/rantav/hector/blob/master/core/src/main/java/me/prettyprint/cassandra/utils/TimeUUIDUtils.java
  val NUM_100NS_INTERVALS_SINCE_UUID_EPOCH = 0x01b21dd213814000L

  def getTimeFromUUID(uuid: UUID): Long = {
    (uuid.timestamp() - NUM_100NS_INTERVALS_SINCE_UUID_EPOCH) / 10000
  }
}

