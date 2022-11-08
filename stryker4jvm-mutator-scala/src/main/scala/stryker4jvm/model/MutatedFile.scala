package stryker4jvm.model

import fs2.io.file.Path
import stryker4jvm.mutants.tree.MutantsWithId

import scala.meta.Tree

final case class MutatedFile(fileOrigin: Path, mutatedSource: Tree, mutants: MutantsWithId)
