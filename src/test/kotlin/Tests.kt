import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kfg.KfgConfig
import org.jetbrains.research.kfg.analysis.LoopAnalysis
import org.jetbrains.research.kfg.analysis.LoopSimplifier
import org.jetbrains.research.kfg.container.JarContainer
import org.jetbrains.research.kfg.util.Flags
import org.jetbrains.research.kfg.visitor.executePipeline
import org.junit.Test
import kotlin.test.assertEquals

class Tests {
    private val jarName = "src/main/resources/tests.jar"

    private fun test(p: String, expected: List<String>) {
        val jar = JarContainer(jarName, p)
        val classManager = ClassManager(KfgConfig(Flags.readAll, true))
        classManager.initialize(jar)
        val actual = mutableSetOf<String>()
        executePipeline(classManager, jar.pkg) {
            +LoopAnalysis(classManager)
            +LoopSimplifier(classManager)
            +LoggingEvolutions(actual, classManager)
        }
        assertEquals(expected.sorted(), actual.toList().sorted())
    }

    @Test
    fun testEmpty() {
        test("empty", emptyList())
    }

    @Test
    fun testInc1() {
        test("inc1", listOf("{1, +, 1}[%loop.1]"))
    }

    @Test
    fun testInc2() {
        test("inc2", listOf("{1, +, 2}[%loop.1]"))
    }

    @Test
    fun testPoly() {
        test("poly", listOf("{1, +, 5}[%loop.1]", "{3, +, 7 + {1, +, 5}[%loop.1]}[%loop.1]"))
    }

    @Test
    fun testFactorial() {
        test("factorial", listOf("{1, +, 1}[%loop.1]", "{1, *, 1, +, 1}[%loop.1]"))
    }

    @Test
    fun testPowerOfTwo() {
        test("powerOfTwo", listOf("{1, *, 2}[%loop.1]"))
    }

    @Test
    fun testDiv2() {
        test("div2", listOf("{2048, *, 1/2}[%loop.1]"))
    }

    @Test
    fun testShl1() {
        test("shl1", listOf("shl(1, {0, +, 1}[%loop.1])", "{0, +, 1}[%loop.1]"))
    }

    @Test
    fun testShlTwice(){
        test("shlTwice", listOf("shl(1, {0, +, 2}[%loop.1])"))
    }

    @Test
    fun testShr1() {
        test("shr1", listOf("shr(2048, {0, +, 1}[%loop.1])"))
    }

    @Test
    fun testShrTwice(){
        test("shrTwice", listOf("shr(1, {0, +, 2}[%loop.1])"))
    }
}