package stryker4jvm.reporting.reporters

import cats.effect.IO
import cats.syntax.parallel.*
import fs2.Pipe
import stryker4jvm.config.Config
import stryker4jvm.logging.FansiLogger
import stryker4jvm.reporting.{FinishedRunEvent, IOReporter}

class AggregateReporter(reporters: List[IOReporter])(implicit log: FansiLogger) extends IOReporter {

  override def mutantTested =
    reportAll(_.mutantTested)

  override def onRunFinished(runReport: FinishedRunEvent): IO[Unit] =
    reporters.parTraverse_(_.onRunFinished(runReport))

  /** Broadcast to all reporters in parallel
    */
  private def reportAll[T](toReporterPipe: IOReporter => Pipe[IO, T, Nothing]): Pipe[IO, T, Nothing] = {
    val pipes = reporters.map(toReporterPipe)
    if (pipes.isEmpty) _.drain
    else
      _.broadcastThrough(reporters.map(toReporterPipe)*).attempt
        .collect { case Left(f) => f }
        .evalMap { e =>
          IO(log.error(s"Reporter failed to report", e))
        }
        .drain
  }
}
