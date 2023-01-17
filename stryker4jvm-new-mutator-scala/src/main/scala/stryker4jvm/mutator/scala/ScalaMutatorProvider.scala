package stryker4jvm.mutator.scala

import stryker4jvm.core.config.LanguageMutatorConfig
import stryker4jvm.core.model.{InstrumenterOptions, LanguageMutator}
import stryker4jvm.core.model.languagemutator.LanguageMutatorProvider

class ScalaMutatorProvider extends LanguageMutatorProvider {
  override def provideMutator(
      languageMutatorConfig: LanguageMutatorConfig,
      instrumenterOptions: InstrumenterOptions
  ): LanguageMutator[ScalaAST] = {
    new ScalaMutator(
      new ScalaParser(),
      new ScalaCollector(mutatorConfig = languageMutatorConfig),
      new ScalaInstrumenter(instrumenterOptions = instrumenterOptions)
    )
  }
}

// return KotlinMutator(
//         KotlinParser(),
//         KotlinCollector.apply(config),
//         KotlinInstrumenter(options)
// );