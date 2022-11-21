package stryker4jvm.mutator.kotlin

import stryker4jvm.core.model.CollectedMutants
import stryker4jvm.core.model.Collector
import stryker4jvm.core.model.MutantMetaData
import stryker4jvm.core.model.MutatedCode
import stryker4jvm.mutator.kotlin.mutators.*;
import stryker4jvm.mutator.kotlin.utility.PsiUtility

class KotlinCollector : Collector<KotlinAST> {
    private val mutators = arrayOf(
        BooleanLiteralMutator,
        StringLiteralMutator,
        EqualityOperatorMutator,
        ConditionalExpressionMutator,
        LogicalOperatorMutator
    )

    override fun collect(tree: KotlinAST?): CollectedMutants<KotlinAST> {
        if (tree == null)
            return CollectedMutants()

        val res = CollectedMutants<KotlinAST>()

        mutators.forEach { mutator ->
            val originalToMutations = mutator.mutateFile(tree.tree)
            originalToMutations.map { originalToMutation ->
                val original = originalToMutation.key
                val mutations = originalToMutation.value

                val originalLocation = PsiUtility.getLocation(original)
                val originalAST = KotlinAST(original)
                val originalText = original.text

                val code = mutations.map { mutation ->
                    val metaData = MutantMetaData(originalText, mutation.text, mutator.name, originalLocation)
                    MutatedCode(originalAST, metaData)
                }
                val currentMutations = res.mutations.getOrDefault(originalAST, mutableListOf())
                currentMutations.addAll(code)
                res.mutations[originalAST] = currentMutations
            }
        }

        return res
    }
}