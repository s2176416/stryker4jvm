package stryker4s.maven.runner

import java.{util => ju}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.meta._

import better.files.File
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.maven.model.Profile
import org.apache.maven.project.MavenProject
import org.apache.maven.shared.invoker.{InvocationRequest, InvocationResult, Invoker}
import org.mockito.captor.ArgCaptor
import org.mockito.scalatest.MockitoSugar
import stryker4s.config.Config
import stryker4s.extension.mutationtype.LesserThan
import stryker4s.model.{Killed, Mutant, Survived}
import stryker4s.testutil.Stryker4sSuite

class MavenTestRunnerTest extends Stryker4sSuite with MockitoSugar {
  implicit val config: Config = Config.default

  val tmpDir = File("/home/user/tmpDir")
  def properties = new ju.Properties()
  def goals = Seq("test")

  describe("runInitialTest") {

    it("should fail on exit-code 1 invoker") {
      val invokerMock = mock[Invoker]
      val mockResult = mock[InvocationResult]
      when(mockResult.getExitCode).thenReturn(1)
      when(invokerMock.execute(any[InvocationRequest])).thenReturn(mockResult)
      val sut = new MavenTestRunner(new MavenProject(), invokerMock, properties, goals)

      val result = sut.initialTestRun().unsafeRunSync()

      result should be(Left(false))
    }

    it("should not add the environment variable") {
      val invokerMock = mock[Invoker]
      val mockResult = mock[InvocationResult]
      when(mockResult.getExitCode).thenReturn(0)
      when(invokerMock.execute(any[InvocationRequest])).thenReturn(mockResult)
      val captor = ArgCaptor[InvocationRequest]
      val sut = new MavenTestRunner(new MavenProject(), invokerMock, properties, goals)

      val result = sut.initialTestRun().unsafeRunSync()

      result should be(Left(true))
      verify(invokerMock).execute(captor)
      val invokedRequest = captor.value
      invokedRequest.getShellEnvironments should be(empty)
    }

    it("should propagate active profiles") {
      val invokerMock = mock[Invoker]
      val mockResult = mock[InvocationResult]
      when(mockResult.getExitCode).thenReturn(0)
      when(invokerMock.execute(any[InvocationRequest])).thenReturn(mockResult)
      val captor = ArgCaptor[InvocationRequest]
      val mavenProject = new MavenProject()
      val profile = new Profile()
      profile.setId("best-profile-ever")
      mavenProject.getActiveProfiles.add(profile)
      val sut = new MavenTestRunner(mavenProject, invokerMock, properties, goals)

      sut.initialTestRun().unsafeRunSync()

      verify(invokerMock).execute(captor)
      val invokedRequest = captor.value
      invokedRequest.getProfiles.asScala should contain("best-profile-ever")
    }
  }

  describe("runMutants") {
    it("should have a Killed mutant on a exit-code 1") {
      val invokerMock = mock[Invoker]
      val mockResult = mock[InvocationResult]
      when(mockResult.getExitCode).thenReturn(1)
      when(invokerMock.execute(any[InvocationRequest])).thenReturn(mockResult)
      val sut = new MavenTestRunner(new MavenProject(), invokerMock, properties, goals)

      val result = sut.runMutant(Mutant(1, q">", q"<", LesserThan)).unsafeRunSync()

      result shouldBe a[Killed]
    }

    it("should have a Survived mutant on a exit-code 0") {
      val invokerMock = mock[Invoker]
      val mockResult = mock[InvocationResult]
      when(mockResult.getExitCode).thenReturn(0)
      when(invokerMock.execute(any[InvocationRequest])).thenReturn(mockResult)
      val sut = new MavenTestRunner(new MavenProject(), invokerMock, properties, goals)

      val result = sut.runMutant(Mutant(1, q">", q"<", LesserThan)).unsafeRunSync()

      result shouldBe a[Survived]
    }

    it("should add the environment variable to the request") {
      val invokerMock = mock[Invoker]
      val mockResult = mock[InvocationResult]
      when(mockResult.getExitCode).thenReturn(1)
      when(invokerMock.execute(any[InvocationRequest])).thenReturn(mockResult)
      val captor = ArgCaptor[InvocationRequest]
      val project = new MavenProject()
      project.getProperties().setProperty("surefire.skipAfterFailureCount", "1")

      val sut = new MavenTestRunner(project, invokerMock, project.getProperties(), goals)

      sut.runMutant(Mutant(1, q">", q"<", LesserThan)).unsafeRunSync()

      verify(invokerMock).execute(captor)
      val invokedRequest = captor.value
      invokedRequest.getShellEnvironments.asScala should equal(Map("ACTIVE_MUTATION" -> "1"))
      invokedRequest.getGoals should contain only "test"
      invokedRequest.isBatchMode should be(true)
      invokedRequest.getProperties.getProperty("surefire.skipAfterFailureCount") should equal("1")
      invokedRequest.getProperties.getProperty("test") shouldBe null
    }

    it("should propagate active profiles") {
      val invokerMock = mock[Invoker]
      val mockResult = mock[InvocationResult]
      when(mockResult.getExitCode).thenReturn(0)
      when(invokerMock.execute(any[InvocationRequest])).thenReturn(mockResult)
      val captor = ArgCaptor[InvocationRequest]
      val mavenProject = new MavenProject()
      val profile = new Profile()
      profile.setId("best-profile-ever")
      mavenProject.getActiveProfiles.add(profile)
      val sut = new MavenTestRunner(mavenProject, invokerMock, properties, goals)

      sut.runMutant(Mutant(1, q">", q"<", LesserThan)).unsafeRunSync()

      verify(invokerMock).execute(captor)
      val invokedRequest = captor.value
      invokedRequest.getProfiles.asScala should contain("best-profile-ever")
    }
  }
}
