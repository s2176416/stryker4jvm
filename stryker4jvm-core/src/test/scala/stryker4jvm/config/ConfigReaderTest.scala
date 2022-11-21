package stryker4jvm.config

import fansi.Color.Yellow
import fansi.Underlined
import fs2.io.file.Path
import pureconfig.error.*
import pureconfig.generic.auto.*
import pureconfig.{ConfigObjectSource, ConfigSource}
import stryker4jvm.config.Config.*
import stryker4jvm.scalatest.LogMatchers
import stryker4jvm.testutil.{ExampleConfigs, Stryker4jvmSuite}
import sttp.client3.UriContext

import scala.concurrent.duration.*
import scala.meta.dialects.*

class ConfigReaderTest extends Stryker4jvmSuite with LogMatchers {
  describe("loadConfig") {
//    TODO: Fails on NotImplementedError
    it("should load stryker4s by type") {
      val configSource = ExampleConfigs.filled

      ConfigReader.readConfigOfType[Config](configSource) match {
        case Left(errors) => fail(errors.toList.mkString(","))
        case Right(config) =>
          config.baseDir shouldBe Path("/tmp/project").absolute
          config.mutate shouldBe Seq("bar/src/main/**/*.scala", "foo/src/main/**/*.scala", "!excluded/file.scala")
          config.reporters.loneElement shouldBe Html
          config.excludedMutations shouldBe Set("BooleanLiteral")
          config.thresholds shouldBe Thresholds(high = 85, low = 65, break = 10)
          config.timeoutFactor shouldBe 2.5
          config.timeout shouldBe 5.5.seconds
          config.maxTestRunnerReuse.value shouldBe 15
          config.legacyTestRunner shouldBe true
          config.scalaDialect shouldBe Scala212
          config.concurrency shouldBe 3
          config.debug shouldBe DebugOptions(logTestRunnerStdout = true, debugTestRunner = true)
      }
    }

    it("should not be able to load a empty config") {
      val configSource = ExampleConfigs.empty

      ConfigReader.readConfigOfType[Config](configSource) match {
        case Left(error) => error.toList.map(a => a.description) shouldBe List("Key not found: 'stryker4s'.")
        case Right(_)    => fail("Config was read successfully which should not be the case.")
      }
    }

    it("should load default config with a nonexistent conf file") {
      val configSource = ExampleConfigs.nonExistentFile

      val result = ConfigReader.readConfig(configSource)

      result.baseDir shouldBe Path("").absolute
      result.mutate shouldBe empty
      result.files shouldBe empty
      result.reporters should (contain.only(Html, Console))
      result.thresholds shouldBe Thresholds()
      result.maxTestRunnerReuse shouldBe None
      result.dashboard shouldBe DashboardOptions(
        baseUrl = uri"https://dashboard.stryker-mutator.io",
        reportType = Full,
        project = None,
        version = None,
        module = None
      )
      result.scalaDialect shouldBe Scala3
      result.debug shouldBe DebugOptions(logTestRunnerStdout = false, debugTestRunner = false)
    }

    it("should fail on an empty config file") {
      val configSource = ExampleConfigs.empty

      lazy val result = ConfigReader.readConfig(configSource)
      val exc = the[ConfigReaderException[?]] thrownBy result

      "Failures in reading config:" shouldBe loggedAsError
      exc.getMessage() should include("Key not found: 'stryker4s'.")
    }

    it("should fail on an unknown reporter") {
      val configSource = ExampleConfigs.wrongReporter

      lazy val result = ConfigReader.readConfig(configSource)
      val exc = the[ConfigReaderException[?]] thrownBy result

      exc.getMessage() should include("Cannot convert configuration")
    }

    //    TODO: Fails on NotImplementedError
    it("should load a config with unknown keys") {
      val configSource = ExampleConfigs.overfilled

      lazy val config = ConfigReader.readConfig(configSource)

      config.baseDir shouldBe Path("/tmp/project").absolute
      config.mutate shouldBe Seq("bar/src/main/**/*.scala", "foo/src/main/**/*.scala", "!excluded/file.scala")
      config.reporters.loneElement shouldBe Html
      config.excludedMutations shouldBe Set("BooleanLiteral")
    }

    //    TODO: Fails on NotImplementedError
    it("should load a config with customized properties") {
      val configSource = ExampleConfigs.filled

      val result = ConfigReader.readConfig(configSource)

      result.baseDir shouldBe Path("/tmp/project").absolute
      result.mutate shouldBe Seq("bar/src/main/**/*.scala", "foo/src/main/**/*.scala", "!excluded/file.scala")
      result.reporters.loneElement shouldBe Html
      result.excludedMutations shouldBe Set("BooleanLiteral")
      result.dashboard shouldBe DashboardOptions(
        baseUrl = uri"https://fakeurl.com",
        reportType = MutationScoreOnly,
        project = Some("someProject"),
        version = Some("someVersion"),
        module = Some("someModule")
      )
    }

    it("should filter out duplicate keys") {
      val configSource = ExampleConfigs.duplicateKeys

      val result = ConfigReader.readConfig(configSource)

      result.reporters.loneElement shouldBe Html
    }

    //    TODO: Fails on NotImplementedError
    it("should return a failure on a misshapen excluded-mutations") {
      val configSource = ExampleConfigs.invalidExcludedMutation

      lazy val result = ConfigReader.readConfig(configSource)
      val exc = the[ConfigReaderException[?]] thrownBy result

      val head = exc.failures.head
      head shouldBe a[ConvertFailure]
      val errorMessage =
        "Cannot convert 'Invalid, StillInvalid, BooleanLiteral' to excluded-mutations: invalid option(s) 'Invalid, StillInvalid'. Valid exclusions are 'EqualityOperator, BooleanLiteral, ConditionalExpression, LogicalOperator, StringLiteral, MethodExpression, RegularExpression'."
      errorMessage shouldBe loggedAsError
    }

    it("should parse duration expressions") {
      val configSource = ExampleConfigs.timeoutDuration

      val result = ConfigReader.readConfig(configSource)

      result.timeout shouldBe 6.seconds
    }
  }

  describe("logs") {

    //    TODO: Fails on NotImplementedError
    it("should log where the config is read from") {
      val configSource = ExampleConfigs.filled

      ConfigReader.readConfig(configSource)

      s"Attempting to read config from ${Underlined.On("stryker4s.conf")}" shouldBe loggedAsInfo
    }

    it("should log warnings when no config file is found") {
      val configSource = ExampleConfigs.nonExistentFile

      ConfigReader.readConfig(configSource)

      val absolutePath = Path("nonExistentFile.conf").absolute
      s"Could not find config file $absolutePath" shouldBe loggedAsWarning
      "Using default config instead..." shouldBe loggedAsWarning
      s"Config used: ${Config.default}" shouldBe loggedAsDebug
    }

    //    TODO: Fails on NotImplementedError
    it("should log warnings when unknown keys are used") {
      val configSource = ExampleConfigs.overfilled

      ConfigReader.readConfig(configSource)

      s"""|The following configuration key(s) are not used, they could stem from an older stryker4s version: '${Yellow(
           "other-unknown-key"
         )}, ${Yellow("unknown-key")}'.
          |Please check the documentation at https://stryker-mutator.io/docs/stryker4s/configuration for available options.""".stripMargin shouldBe loggedAsWarning
    }
  }

  describe("ConfigReaderImplicits") {
    describe("Thresholds") {
      val testValues = List(
        "empty=true" -> Thresholds(),
        "high=85, low=65, break=10" -> Thresholds(high = 85, low = 65, break = 10),
        "high=30, low=30" -> Thresholds(high = 30, low = 30),
        "low=30, break=29" -> Thresholds(low = 30, break = 29),
        "high=100" -> Thresholds(high = 100),
        "high=-1" -> CannotConvert("-1", "thresholds.high", "must be a percentage 0-100"),
        "low=-1" -> CannotConvert("-1", "thresholds.low", "must be a percentage 0-100"),
        "break=-1" -> CannotConvert("-1", "thresholds.break", "must be a percentage 0-100"),
        "high=101" -> CannotConvert("101", "thresholds.high", "must be a percentage 0-100"),
        "low=101" -> CannotConvert("101", "thresholds.low", "must be a percentage 0-100"),
        "break=101" -> CannotConvert("101", "thresholds.break", "must be a percentage 0-100"),
        "high=50,low=51" -> CannotConvert(
          "50",
          "thresholds.high",
          "'high' (50) must be greater than or equal to 'low' (51)"
        ),
        "low=50,break=51" -> CannotConvert("50", "thresholds.low", "'low' (50) must be greater than 'break' (51)"),
        "low=50,break=50" -> CannotConvert("50", "thresholds.low", "'low' (50) must be greater than 'break' (50)")
      )

      testValues.foreach { case (config, expected) =>
        it(s"should load $config to expected result") {
          val result = ConfigSource.string(config).load[Thresholds]

          result match {
            case Right(value) => value shouldBe expected
            case Left(ConfigReaderFailures(ConvertFailure(reason, _, _), _*)) =>
              reason shouldBe expected
            case Left(other) => fail(s"unexpected value $other")
          }
        }
      }
    }

    describe("ScalaDialect") {
      val validVersions = List(
        "scala212" -> Scala212,
        "scala2.12" -> Scala212,
        "2.12" -> Scala212,
        "212" -> Scala212,
        "scala213" -> Scala213,
        "scala2.13" -> Scala213,
        "2.13" -> Scala213,
        "213" -> Scala213,
        "scala3" -> Scala3,
        "dotty" -> Scala3,
        "3" -> Scala3,
        "3.0" -> Scala3,
        "scala212source3" -> Scala212Source3,
        "scala213source3" -> Scala213Source3,
        "source3" -> Scala213Source3
      )

      validVersions.foreach { case (input, expected) =>
        it(s"should parse $input to $expected") {
          ExampleConfigs.scalaDialect(input).at("stryker4s").load[Config] match {
            case Right(value) => value.scalaDialect shouldBe expected
            case Left(value)  => fail(s"Expected valid parsing, got $value")
          }

        }
      }

      it("should not parse invalid scala-dialects") {
        expectConfigFailure(
          ExampleConfigs.scalaDialect("foobar"),
          CannotConvert(
            "foobar",
            "scala-dialect",
            "Unsupported dialect. Leaving this configuration empty defaults to scala3 which might also work for you. Valid scalaDialects are: 'scala212', 'scala2.12', '2.12', '212', 'scala212source3', 'scala213', 'scala2.13', '2.13', '213', '2', 'scala213source3', 'source3', 'scala3', 'scala3.0', '3.0', '3', 'dotty'"
          )
        )
      }

      val deprecatedVersions = List("scala211", "scala2.11", "2.11", "211")

      deprecatedVersions.foreach { version =>
        it(s"should error deprecated scala-dialect $version") {
          expectConfigFailure(
            ExampleConfigs.scalaDialect(version),
            CannotConvert(
              version,
              "scala-dialect",
              s"Deprecated dialect. Leaving this configuration empty defaults to scala3 which might also work for you. Valid scalaDialects are: 'scala212', 'scala2.12', '2.12', '212', 'scala212source3', 'scala213', 'scala2.13', '2.13', '213', '2', 'scala213source3', 'source3', 'scala3', 'scala3.0', '3.0', '3', 'dotty'"
            )
          )
        }
      }
    }

    def expectConfigFailure(config: ConfigObjectSource, failure: FailureReason) =
      config.at("stryker4s").load[Config] match {
        case Left(ConfigReaderFailures(ConvertFailure(reason, _, _), _*)) => reason shouldBe failure
        case value => fail(s"Expected parsing failure but got $value")
      }
  }
}