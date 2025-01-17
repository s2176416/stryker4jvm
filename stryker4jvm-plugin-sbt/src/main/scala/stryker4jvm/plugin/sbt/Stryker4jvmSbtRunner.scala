package stryker4jvm.plugin.sbt

import cats.data.NonEmptyList
import cats.effect.{Deferred, IO, Resource}
import cats.syntax.either.*
import com.comcast.ip4s.Port
import fs2.io.file.Path
import sbt.*
import sbt.Keys.*
import sbt.internal.LogManager
import stryker4jvm.config.{Config, TestFilter}
import stryker4jvm.core.model.InstrumenterOptions
import stryker4jvm.exception.TestSetupException
import stryker4jvm.extensions.FileExtensions.*
import stryker4jvm.files.{FilesFileResolver, MutatesFileResolver}
import stryker4jvm.logging.FansiLogger
import stryker4jvm.model.CompilerErrMsg
import stryker4jvm.plugin.sbt.Stryker4jvmMain.autoImport.stryker
import stryker4jvm.plugin.sbt.files.{SbtFilesResolver, SbtMutatesResolver}
import stryker4jvm.plugin.sbt.runner.{LegacySbtTestRunner, SbtTestRunner}
import stryker4jvm.run.{Stryker4jvmRunner, TestRunner}

import java.io.{File as JFile, PrintStream}
import scala.concurrent.duration.FiniteDuration

/** This Runner run Stryker mutations in a single SBT session
  *
  * @param state
  *   SBT project state (contains all the settings about the project)
  */
class Stryker4jvmSbtRunner(
    state: State,
    sharedTimeout: Deferred[IO, FiniteDuration],
    sources: Seq[Path],
    targetDir: Path
)(implicit
    log: FansiLogger
) extends Stryker4jvmRunner {

  def resolveTestRunners(
      tmpDir: Path
  )(implicit config: Config): Either[NonEmptyList[CompilerErrMsg], NonEmptyList[Resource[IO, TestRunner]]] = {
    def setupLegacySbtTestRunner(
        settings: Seq[Def.Setting[?]],
        extracted: Extracted
    ): NonEmptyList[Resource[IO, TestRunner]] = {
      log.info("Using the legacy sbt testrunner")

      val emptyLogManager =
        LogManager.defaultManager(ConsoleOut.printStreamOut(new PrintStream((_: Int) => {})))

      val fullSettings = settings ++ Seq(
        logManager := {
          if ((stryker / logLevel).value == Level.Debug) logManager.value
          else emptyLogManager
        }
      )
      val newState = extracted.appendWithSession(fullSettings, state)

      NonEmptyList.of(Resource.pure(new LegacySbtTestRunner(newState, fullSettings, extracted)))
    }

    def setupSbtTestRunner(
        settings: Seq[Def.Setting[?]],
        extracted: Extracted
    ): Either[NonEmptyList[CompilerErrMsg], NonEmptyList[Resource[IO, TestRunner]]] = {
      val stryker4jvmVersion = this.getClass().getPackage().getImplementationVersion()
      log.debug(s"Resolved stryker4jvm version $stryker4jvmVersion")

      val fullSettings = settings ++ Seq(
        libraryDependencies +=
          "io.stryker-mutator" %% "stryker4jvm-plugin-sbt-testrunner" % stryker4jvmVersion
      )
      val newState = extracted.appendWithSession(fullSettings, state)

      def extractTaskValue[T](task: TaskKey[T], name: String) = {

        Project.runTask(task, newState) match {
          case Some((_, Value(result))) => result
          case other =>
            log.debug(s"Expected $name but got $other")
            throw TestSetupException(name)
        }
      }

      // SBT returns any errors as a Incomplete case class, which can contain other Incomplete instances
      // You have to recursively search through them to get the real exception
      def getRootCause(i: Incomplete): Seq[Throwable] = {
        i.directCause match {
          case None =>
            i.causes.flatMap(getRootCause)
          case Some(cause) =>
            cause +: i.causes.flatMap(getRootCause)
        }
      }

      // See if the mutations compile, and if not extract the errors
      val compilerErrors = Project.runTask(Compile / compile, newState) match {
        case Some((_, Inc(cause))) =>
          val rootCauses = getRootCause(cause)
          rootCauses.foreach(t => log.debug(s"Compile failed with ${t.getClass().getName()} root cause: $t"))
          val compileErrors = rootCauses
            .collect { case e: xsbti.CompileFailed => e }
            .flatMap { exception =>
              exception.problems.flatMap { e =>
                for {
                  path <- e.position().sourceFile().asScala
                  pathStr = tmpDir.relativize(Path(path.absolutePath)).toString
                  line <- e.position().line().asScala
                } yield CompilerErrMsg(e.message(), pathStr, line)
              }
            }
            .toList

          NonEmptyList.fromList(compileErrors)
        case _ =>
          None
      }

      compilerErrors.toLeft {
        val classpath = extractTaskValue(Test / fullClasspath, "classpath").map(_.data.getPath())

        val javaOpts = extractTaskValue(Test / javaOptions, "javaOptions")

        val frameworks = extractTaskValue(Test / loadedTestFrameworks, "test frameworks").values.toSeq

        val testGroups = extractTaskValue(Test / testGrouping, "testGrouping").map { group =>
          if (config.testFilter.isEmpty) group
          else {
            val testFilter = new TestFilter()
            val filteredTests = group.tests.filter(t => testFilter.filter(t.name))
            new Tests.Group(name = group.name, tests = filteredTests, runPolicy = group.runPolicy)
          }
        }

        val concurrency = if (config.debug.debugTestRunner) {
          log.warn(
            "'debug.debug-test-runner' config is 'true', creating 1 test-runner with debug arguments enabled on port 8000."
          )
          1
        } else {
          log.info(s"Creating ${config.concurrency} test-runners")
          config.concurrency
        }

        val portStart = 13336
        val portRanges = NonEmptyList.fromListUnsafe(
          (1 to concurrency).map(p => Port.fromInt(p + portStart).get).toList
        )

        portRanges.map { port =>
          SbtTestRunner.create(classpath, javaOpts, frameworks, testGroups, port, sharedTimeout)
        }
      }
    }

    def extractSbtProject(tmpDir: Path)(implicit config: Config) = {
      // Remove scalacOptions that are very likely to cause errors with generated code
      // https://github.com/stryker-mutator/stryker4s/issues/321
      val blocklistedScalacOptions = Seq(
        "unused:patvars",
        "unused:locals",
        "unused:params",
        "unused:explicits"
        // -Ywarn for Scala 2.12, -W for Scala 2.13
      ).flatMap(opt => Seq(s"-Ywarn-$opt", s"-W$opt"))

      val filteredSystemProperties: Seq[String] = {
        // Matches strings that start with one of the options between brackets
        val regex = "^(java|sun|file|user|jna|os|sbt|jline|awt|graal|jdk).*"
        for {
          (key, value) <- sys.props.toList.filterNot { case (key, _) => key.matches(regex) }
          param = s"-D$key=$value"
        } yield param
      }
      log.debug(s"System properties added to the forked JVM: ${filteredSystemProperties.mkString(",")}")

      val settings: Seq[Def.Setting[?]] = Seq(
        scalacOptions --= blocklistedScalacOptions,
        Test / fork := true,
        Compile / scalaSource := tmpDirFor(Compile / scalaSource, tmpDir).value,
        // Java code is not mutated, but can point to the same directory as the Scala code
        // so we need to also change the directory for javaSource to the tmpDir to prevent this
        Compile / javaSource := tmpDirFor(Compile / javaSource, tmpDir).value,
        Test / javaOptions ++= filteredSystemProperties
      ) ++ {
        if (config.testFilter.nonEmpty) {
          val testFilter = new TestFilter()
          Seq(Test / testOptions := Seq(Tests.Filter(testFilter.filter)))
        } else
          Nil
      }

      (settings, Project.extract(state))
    }

    def tmpDirFor(langSource: SettingKey[File], tmpDir: Path): Def.Initialize[JFile] =
      langSource(source => (Path.fromNioPath(source.toPath()) inSubDir tmpDir).toNioPath.toFile())

    val (settings, extracted) = extractSbtProject(tmpDir)

    if (config.legacyTestRunner) {
      // No compiler error handling in the legacy runner
      setupLegacySbtTestRunner(settings, extracted).asRight
    } else
      setupSbtTestRunner(settings, extracted)
  }

  override def resolveMutatesFileSource(implicit config: Config): MutatesFileResolver =
    if (config.mutate.isEmpty) new SbtMutatesResolver(state, targetDir) else super.resolveMutatesFileSource

  override def resolveFilesFileSource(implicit config: Config): FilesFileResolver =
    if (config.files.isEmpty) new SbtFilesResolver(sources, targetDir) else super.resolveFilesFileSource

  override def instrumenterOptions(implicit config: Config): InstrumenterOptions =
    if (config.legacyTestRunner) {
      InstrumenterOptions.SysProp
    } else {
      InstrumenterOptions.TestRunner
    }

}
