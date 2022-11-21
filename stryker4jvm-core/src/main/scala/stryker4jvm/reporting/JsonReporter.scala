package stryker4jvm.reporting

import cats.effect.IO
import fs2.io.file.Path
import mutationtesting.MutationTestResult
import stryker4jvm.config.Config
import stryker4jvm.files.FileIO
import stryker4jvm.logging.Logger

class JsonReporter(fileIO: FileIO)(implicit log: Logger) extends Reporter {

  def writeReportJsonTo(file: Path, report: MutationTestResult[Config]): IO[Unit] = {
    import io.circe.syntax.*
    import mutationtesting.circe.*
    val json = report.asJson.noSpaces
    fileIO.createAndWrite(file, json)
  }

  override def onRunFinished(runReport: FinishedRunEvent): IO[Unit] = {
    val resultLocation = runReport.reportsLocation / "report.json"

    writeReportJsonTo(resultLocation, runReport.report) *>
      IO(log.info(s"Written JSON report to $resultLocation"))
  }
}