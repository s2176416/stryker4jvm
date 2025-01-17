package stryker4jvm.mutator.kotlin.mutators

import io.mockk.clearAllMocks
import kotlin.test.*
import kotlin.test.Test

class EqualityOperatorMutatorTest {

  @Test
  fun testEqualityOperatorMutatorMutate() {
    // Arrange
    clearAllMocks()
    val target = MutatorTestUtil.newCollector(EqualityOperatorMutator)
    val testFile =
        MutatorTestUtil.parse(
            """
            fun dummy() { 
                if(0 < 1) print("a")
                if(0 <= 1) print("a")
                if(0 > 1) print("a")
                if(0 >= 1) print("a")
                if(0 == 1) print("a")
                if(0 != 1) print("a")
                if(0 === 1) print("a")
                if(0 !== 1) print("a")
            }
        """.trimIndent())

    // Act
    val result = target.collect(testFile)
    val ignored = result.ignoredMutations
    val mutations = result.mutations

    // Assert
    assertTrue(ignored.isEmpty())
    assertEquals(8, mutations.size)

    MutatorTestUtil.testName("EqualityOperator", result)
    MutatorTestUtil.testMutations(
        mapOf(
            Pair("0 < 1", mutableListOf("0 <= 1", "0 >= 1")),
            Pair("0 <= 1", mutableListOf("0 < 1", "0 > 1")),
            Pair("0 > 1", mutableListOf("0 >= 1", "0 <= 1")),
            Pair("0 >= 1", mutableListOf("0 > 1", "0 < 1")),
            Pair("0 == 1", mutableListOf("0 != 1")),
            Pair("0 != 1", mutableListOf("0 == 1")),
            Pair("0 === 1", mutableListOf("0 !== 1")),
            Pair("0 !== 1", mutableListOf("0 === 1"))),
        result)
  }
}
