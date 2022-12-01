package stryker4jvm.reporting

import cats.effect.IO
import fs2.io.file.Path
import mutationtesting.MutationTestResult
import stryker4jvm.config.Config
import stryker4jvm.core.files.FileIO
import stryker4jvm.core.logging.Logger
import stryker4jvm.core.reporting.events.FinishedRunEvent

class HtmlReporter(fileIO: FileIO)(implicit log: Logger) extends IOReporter[Config] {

  private val title = "stryker4jvm report"
  private val mutationTestElementsName = "mutation-test-elements.js"
  private val htmlReportResource = s"/elements/$mutationTestElementsName"
  private val reportFilename = "report.js"

  private val indexHtml: String =
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |  <meta charset="UTF-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1.0">
       |  <script src="mutation-test-elements.js"></script>
       |</head>
       |<body>
       |  <mutation-test-report-app title-postfix="$title">
       |    Your browser doesn't support <a href="https://caniuse.com/#search=custom%20elements">custom elements</a>.
       |    Please use a latest version of an evergreen browser (Firefox, Chrome, Safari, Opera, etc).
       |  </mutation-test-report-app>
       |  <script>
       |    const app = document.getElementsByTagName('mutation-test-report-app').item(0);
       |    function updateTheme() {
       |      document.body.style.backgroundColor = app.themeBackgroundColor;
       |    }
       |    app.addEventListener('theme-changed', updateTheme);
       |    updateTheme();
       |  </script>
       |  <script src="$reportFilename"></script>
       |</body>
       |</html>""".stripMargin

  def writeMutationTestElementsJsTo(file: Path): IO[Unit] =
    IO(fileIO.createAndWriteFromResource(file.toNioPath, htmlReportResource))

  def writeIndexHtmlTo(file: Path): IO[Unit] =
    IO(fileIO.createAndWrite(file.toNioPath, indexHtml))

  def writeReportJsTo(file: Path, report: MutationTestResult[Config]): IO[Unit] = {
    import io.circe.syntax.*
    import mutationtesting.circe.*
    val json = report.asJson.noSpaces
    val reportContent = s"document.querySelector('mutation-test-report-app').report = $json"
    IO(fileIO.createAndWrite(file.toNioPath, reportContent))
  }

  override def onRunFinished(runReport: FinishedRunEvent[Config]): IO[Unit] = {
    val path = fs2.io.file.Path.fromNioPath(runReport.reportsLocation)
    val mutationTestElementsLocation = path / mutationTestElementsName
    val indexLocation = path / "index.html"
    val reportLocation = path / reportFilename

    val reportsWriting = writeIndexHtmlTo(indexLocation) &>
      writeReportJsTo(reportLocation, runReport.report) &>
      writeMutationTestElementsJsTo(mutationTestElementsLocation)

    reportsWriting *>
      IO(log.info(s"Written HTML report to $indexLocation"))
  }
}
