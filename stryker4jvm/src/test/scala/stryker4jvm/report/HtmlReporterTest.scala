package stryker4jvm.report

import cats.effect.IO
import fs2.*
import fs2.io.file.{Files, Path}
import java.nio.file.Path as JavaPath
import mutationtesting.{Metrics, MutationTestResult, Thresholds}
import org.mockito.captor.ArgCaptor
import stryker4jvm.core.files.{DiskFileIO, FileIO}
import stryker4jvm.reporting.FinishedRunEvent
import stryker4jvm.reporting.reporters.HtmlReporter
import stryker4jvm.scalatest.LogMatchers
import stryker4jvm.testutil.{MockitoIOSuite, Stryker4jvmIOSuite}

import scala.concurrent.duration.*

class HtmlReporterTest extends Stryker4jvmIOSuite with MockitoIOSuite with LogMatchers {

  private val elementsLocation = "/elements/mutation-test-elements.js"

  private val expectedHtml =
    """<!DOCTYPE html>
      |<html lang="en">
      |<head>
      |  <meta charset="UTF-8">
      |  <meta name="viewport" content="width=device-width, initial-scale=1.0">
      |  <script src="mutation-test-elements.js"></script>
      |</head>
      |<body>
      |  <mutation-test-report-app title-postfix="Stryker4s report">
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
      |  <script src="report.js"></script>
      |</body>
      |</html>""".stripMargin

  describe("indexHtml") {
    it("should contain title") {
      val mockFileIO = mock[FileIO]
      doNothing.when(mockFileIO).createAndWrite(any[JavaPath], any[String])
      val sut = new HtmlReporter(mockFileIO)
      val testFile = Path("foo.bar")

      sut
        .writeIndexHtmlTo(testFile)
        .map { _ =>
          verify(mockFileIO).createAndWrite(testFile.toNioPath, expectedHtml)
        }
        .assertNoException
    }
  }

  describe("reportJs") {
    it("should contain the report") {
      val mockFileIO = mock[FileIO]
      doNothing.when(mockFileIO).createAndWrite(any[JavaPath], any[String])
      val sut = new HtmlReporter(mockFileIO)
      val testFile = Path("foo.bar")
      val runResults = MutationTestResult(thresholds = Thresholds(100, 0), files = Map.empty)

      sut
        .writeReportJsTo(testFile, runResults)
        .map { _ =>
          val expectedJs =
            """document.querySelector('mutation-test-report-app').report = {"$schema":"https://git.io/mutation-testing-schema","schemaVersion":"1","thresholds":{"high":100,"low":0},"files":{}}"""
          verify(mockFileIO).createAndWrite(testFile.toNioPath, expectedJs)
        }
        .assertNoException
    }
  }

  describe("mutation-test-elements") {
    it("should write the resource") {
      // Arrange
      val fileIO = new DiskFileIO()

      Files[IO].tempDirectory.use { tmpDir =>
        val tempFile = tmpDir.resolve("mutation-test-elements.js")
        val sut = new HtmlReporter(fileIO)

        // Act
        sut
          .writeMutationTestElementsJsTo(tempFile) >> {
          // assert
          val atLeastSize: Long = 100 * 1024L // 100KB
          Files[IO].size(tempFile).asserting(_ should be > atLeastSize)
        } >> {
          val expectedHeader = "/*! For license information please see mutation-test-elements.js.LICENSE.txt */"
          // Read the first line
          Files[IO]
            .readRange(tempFile, 256, 0, expectedHeader.getBytes().length.toLong)
            .through(text.utf8.decode)
            .head
            .compile
            .lastOrError
            .asserting(
              _ shouldBe expectedHeader
            )
        }
      }
    }
  }

  describe("onRunFinished") {
    val fileLocation = Path("target") / "stryker4s-report"

    it("should write the report files to the report directory") {
      val mockFileIO = mock[FileIO]
      doNothing.when(mockFileIO).createAndWrite(any[JavaPath], any[String])
      doNothing.when(mockFileIO).createAndWriteFromResource(any[JavaPath], any[String])
//      when(mockFileIO.createAndWriteFromResource(any[Path].toNioPath, any[String])).thenReturn(())
      val sut = new HtmlReporter(mockFileIO)
      val report = MutationTestResult(thresholds = Thresholds(100, 0), files = Map.empty)
      val metrics = Metrics.calculateMetrics(report)

      sut
        .onRunFinished(FinishedRunEvent(report, metrics, 0.seconds, fileLocation.toNioPath))
        .asserting { _ =>
          val writtenFilesCaptor = ArgCaptor[JavaPath]
          verify(mockFileIO, times(2)).createAndWrite(writtenFilesCaptor.capture, any[String])
          verify(mockFileIO).createAndWriteFromResource(any[JavaPath], eqTo(elementsLocation))
          val paths = writtenFilesCaptor.values.map(_.toString)
          all(paths) should include(fileLocation.toString)
          writtenFilesCaptor.values.map(_.getFileName.toString) should contain.only("index.html", "report.js")
        }
    }

    it("should write the mutation-test-elements.js file to the report directory") {
      val mockFileIO = mock[FileIO]
      doNothing.when(mockFileIO).createAndWrite(any[JavaPath], any[String])
      doNothing.when(mockFileIO).createAndWriteFromResource(any[JavaPath], any[String])
      val sut = new HtmlReporter(mockFileIO)
      val report = MutationTestResult(thresholds = Thresholds(100, 0), files = Map.empty)
      val metrics = Metrics.calculateMetrics(report)

      sut
        .onRunFinished(FinishedRunEvent(report, metrics, 10.seconds, fileLocation.toNioPath))
        .asserting { _ =>
          val elementsCaptor = ArgCaptor[JavaPath]
          verify(mockFileIO, times(2)).createAndWrite(any[JavaPath], any[String])
          verify(mockFileIO).createAndWriteFromResource(elementsCaptor.capture, eqTo(elementsLocation))

          val expectedFileLocation = fileLocation / "mutation-test-elements.js"
          elementsCaptor.value.toString should endWith(expectedFileLocation.toString)
        }
    }

    it("should info log a message") {
      val mockFileIO = mock[FileIO]
      doNothing.when(mockFileIO).createAndWrite(any[JavaPath], any[String])
      doNothing.when(mockFileIO).createAndWriteFromResource(any[JavaPath], any[String])
      val sut = new HtmlReporter(mockFileIO)
      val report = MutationTestResult(thresholds = Thresholds(100, 0), files = Map.empty)
      val metrics = Metrics.calculateMetrics(report)

      sut
        .onRunFinished(FinishedRunEvent(report, metrics, 10.seconds, fileLocation.toNioPath))
        .asserting { _ =>
          val captor = ArgCaptor[JavaPath]
          verify(mockFileIO).createAndWrite(captor.capture, eqTo(expectedHtml))
          verify(mockFileIO, times(2)).createAndWrite(any[Path].toNioPath, any[String])
          verify(mockFileIO).createAndWriteFromResource(any[Path].toNioPath, any[String])
          s"Written HTML report to ${(fileLocation / "index.html").toString}" shouldBe loggedAsInfo
        }
    }
  }
}