import LoopTracker.clean
import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kfg.ir.Method
import org.jetbrains.research.kfg.ir.value.instruction.*
import ru.spbstu.*

class LoggingEvolutions(private val logger: MutableSet<String>, cm: ClassManager) : Evolutions(cm) {
    /**
     * Collects all loops in method and performs their analysis.
     * Corresponds to ComputeLoopPhiEvolution from paper.
     */
    override fun visit(method: Method) {
        walkLoops(method).forEach { loop ->
            loop
                .header
                .takeWhile { it is PhiInst }
                .filterIsInstance<PhiInst>()
                .forEach {
                    loopPhis[it] = loop
                }
            loop.body.forEach {
                it.forEach {
                    inst2loop.getOrPut(it) { loop }
                }
            }
        }

        for (b in method) for (i in b) {
            val t = transform(i)
            if (t != Undefined) {
                println(i.print() + " -> " + t)
            }
        }

        val freshVars = loopPhis.values.toSet().map { it to Var.fresh("iteration") }.toMap()
        for ((phi) in loopPhis) {
            val eq = buildPhiEquation(phi)
            logger.add(eq.toString())
            val evo = evaluateEvolutions(eq, freshVars)
            println(phi.print())
            println(eq)
            println(evo)
            (1..10).forEach { iter ->
                print("[$iter]")
                println(evo.subst(freshVars.values.first() to Const(iter)))
            }
        }

    }

    override fun cleanup() {
        clean()
    }
}