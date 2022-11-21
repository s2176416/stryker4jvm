package stryker4jvm.exception

import cats.data.NonEmptyList
import cats.syntax.foldable.*
import fs2.io.file.Path
import stryker4jvm.model.CompilerErrMsg

import scala.util.control.NoStackTrace

abstract class Stryker4jvmException(message: String) extends Exception(message) {
  def this(message: String, cause: Throwable) = {
    this(message)
    initCause(cause)
  }

// TODO: share these exceptions between scala and kotlin?
//
//  final case class UnableToBuildPatternMatchException(file: Path, cause: Throwable)
//    extends Stryker4jvmException(
//      s"Failed to instrument mutants in `$file`.\nPlease open an issue on github and include the stacktrace and failed instrumentation code: https://github.com/stryker-mutator/stryker4s/issues/new",
//      cause
//    )
//
//  final case class InitialTestRunFailedException(message: String) extends Stryker4jvmException(message) with NoStackTrace
//
//  final case class TestSetupException(name: String)
//    extends Stryker4jvmException(
//      s"Could not setup mutation testing environment. Unable to resolve project $name. This could be due to compile errors or misconfiguration of Stryker4s. See debug logs for more information."
//    )
//
//  final case class MutationRunFailedException(message: String) extends Stryker4jvmException(message)
//
//  final case class UnableToFixCompilerErrorsException(errs: NonEmptyList[CompilerErrMsg])
//    extends Stryker4jvmException(
//      "Unable to remove non-compiling mutants in the mutated files. As a work-around you can exclude them in the stryker.conf. Please report this issue at https://github.com/stryker-mutator/stryker4s/issues\n"
//        + errs
//        .map(err => s"${err.path}: '${err.msg}'")
//        .mkString_("\n")
//    )
}