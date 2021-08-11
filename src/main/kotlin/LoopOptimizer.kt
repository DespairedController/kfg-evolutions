import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kfg.analysis.IRVerifier
import org.jetbrains.research.kfg.analysis.Loop
import org.jetbrains.research.kfg.analysis.LoopManager
import org.jetbrains.research.kfg.analysis.LoopVisitor
import org.jetbrains.research.kfg.ir.BasicBlock
import org.jetbrains.research.kfg.ir.BodyBlock
import org.jetbrains.research.kfg.ir.Method
import org.jetbrains.research.kfg.ir.value.Value
import org.jetbrains.research.kfg.ir.value.instruction.BinaryInst
import org.jetbrains.research.kfg.ir.value.instruction.BinaryOpcode
import org.jetbrains.research.kfg.ir.value.instruction.Instruction
import org.jetbrains.research.kfg.ir.value.instruction.PhiInst
import org.jetbrains.research.kfg.type.IntType
import ru.spbstu.*

class LoopOptimizer(cm: ClassManager) : Evolutions(cm), LoopVisitor {
    private lateinit var current: Method
    private val phiToEvo = mutableMapOf<PhiInst, Symbolic>()
    private val freshVars = mutableMapOf<Loop, Var>()
    private val freshValues = mutableMapOf<Var, Value>()
    override val preservesLoopInfo get() = false
    override fun cleanup() {}

    override fun visit(method: Method) {
        cleanup()
        if (!method.hasBody) return
        current = method
        walkLoops(method).forEach { loop ->
            loop
                .header
                .takeWhile { it is PhiInst }
                .filterIsInstance<PhiInst>()
                .forEach {
                    loopPhis[it] = loop
                }
            loop.body.forEach { basicBlock ->
                basicBlock.forEach {
                    inst2loop.getOrPut(it) { loop }
                }
            }
        }

        for (b in method) for (i in b) {
            transform(i)
        }

        freshVars.putAll(loopPhis.values.toSet().map { it to Var.fresh("iteration") })
        loopPhis.keys.forEach {
            phiToEvo[it] = evaluateEvolutions(buildPhiEquation(it), freshVars)
            println(phiToEvo[it])
        }


        val loops = LoopManager.getMethodLoopInfo(method)
        loops.forEach {
            visit(it)
        }
        updateLoopInfo(method)
        IRVerifier(cm).visit(method)
    }

    override fun visit(loop: Loop) {
        super<LoopVisitor>.visit(loop)
        if (loop.allEntries.size != 1 || loop !in freshVars.keys) {
            return
        }
        insertInductive(loop)
        rebuild(loop)
        clearUnused(loop)
    }

    private fun clearUnused(loop: Loop) {
        val unused = mutableListOf<Instruction>()
        for (b in loop.body) {
            for (i in b) {
                if (i is BinaryInst || i is PhiInst)
                if (i.users.isEmpty()) {
                    unused += i
                }
            }
            unused.forEach {
                it.clearUses()
                b -= it
            }
            unused.clear()
        }
    }

    private fun insertBefore(block: BasicBlock, e: BasicBlock, loop: Loop) {
        e.add(instructions.getJump(block))
        val preBlock = block.predecessors.first()
        e.addPredecessor(preBlock)
        e.addSuccessor(block)

        block.removePredecessor(preBlock)
        block.addPredecessor(e)

        preBlock.removeSuccessor(block)
        preBlock.addSuccessor(e)

        loop.addBlock(e)

        preBlock.remove(preBlock.instructions.last())
        preBlock.add(instructions.getJump(e))

        current.addBefore(block, e)
    }

    private fun insertInductive(loop: Loop) {
        val one = values.getInt(1)
        val tmpPhi = instructions.getPhi(IntType, mapOf())

        val newInstruction = instructions.getBinary(BinaryOpcode.ADD, one, tmpPhi.get())
        val newPhi = instructions.getPhi(
            IntType,
            mapOf(Pair(loop.preheader, one), Pair(loop.latch, newInstruction.get()))
        )
        freshValues[freshVars[loop]!!] = newPhi.get()
        tmpPhi.replaceAllUsesWith(newPhi)
        tmpPhi.clearUses()
        loop.header.insertBefore(loop.header.first(), newPhi)

        val updater = BodyBlock("loop.updater")
        updater += newInstruction
        insertBefore(loop.latch, updater, loop)
    }

    private fun reconstructPhi(phi: PhiInst, collector: MutableList<Instruction>): Value? {
        println(phi.print())
        val evo = phiToEvo[phi] ?: return null
        println(evo)
        println()
        return evo.generateCode(collector)
    }

    private fun Symbolic.generateCode(collector: MutableList<Instruction>): Value? {
        return when (this) {
            is Sum -> this.generateCode(collector)
            is Const -> this.generateCode()
            is Var -> this.generateCode()
            is Shift -> this.generateCode(collector)
            is Apply -> null
            is Product -> this.generateCode(collector)
        }
    }

    private fun Sum.generateCode(collector: MutableList<Instruction>): Value? {
        val lcm = lcm(this.constant.den, this.parts.values.fold(1L) { acc, v -> lcm(acc, v.den) })
        val results = mutableListOf<Value>()
        this.parts.forEach {
            val res = it.key.generateCode(collector) ?: return null
            val newInstruction =
                instructions.getBinary(BinaryOpcode.MUL, res, values.getInt((it.value * lcm).wholePart.toInt()))
            collector.add(newInstruction)
            results.add(newInstruction.get())
        }
        val res = results.drop(1).fold(results[0]) { acc, v ->
            val newValue = instructions.getBinary(BinaryOpcode.ADD, acc, v)
            collector.add(newValue)
            newValue
        }
        if (constant.num == 0L) {
            if (lcm == 1L) {
                return res
            }
            val divLcm = instructions.getBinary(BinaryOpcode.DIV, res, values.getInt(lcm.toInt()))
            collector.add(divLcm)
            return divLcm
        }
        val addConst =
            instructions.getBinary(BinaryOpcode.ADD, res, values.getInt((this.constant * lcm).wholePart.toInt()))
        collector.add(addConst)
        if (lcm == 1L) {
            return addConst
        }
        val divLcm = instructions.getBinary(BinaryOpcode.DIV, addConst, values.getInt(lcm.toInt()))
        collector.add(divLcm)
        return divLcm
    }

    private fun Product.generateCode(collector: MutableList<Instruction>): Value? {
        val results = mutableListOf<Value>()
        this.parts.forEach {
            if (it.value.den != 1L) return null
            val base = it.key.generateCode(collector) ?: return null
            var pre = base
            for (i in 1 until it.value.wholePart) {
                val newInst =
                    instructions.getBinary(BinaryOpcode.MUL, pre, base)
                collector.add(newInst)
                pre = newInst

            }
            results.add(pre)
        }
        val res = results.drop(1).fold(results[0]) { acc, v ->
            val newValue = instructions.getBinary(BinaryOpcode.MUL, acc, v)
            collector.add(newValue)
            newValue
        }
        if (constant.isWhole() && constant.wholePart == 1L) {
            return res
        }
        val mulConst =
            instructions.getBinary(BinaryOpcode.MUL, res, values.getInt(constant.num.toInt()))
        collector.add(mulConst)

        if (constant.isWhole()) {
            return mulConst
        }
        val divLcm = instructions.getBinary(BinaryOpcode.DIV, mulConst, values.getInt(constant.den.toInt()))
        collector.add(divLcm)
        return divLcm
    }

    private fun Const.generateCode(): Value? {
        return values.getConstant(this.value.wholePart)
    }

    private fun Var.generateCode(): Value {
        return freshValues[this]!!
    }

    private fun Apply.generateCode(collector: MutableList<Instruction>): Value? {
        return when (this) {
            is ShiftRight -> generateCode(collector)
            is ShiftLeft -> generateCode(collector)
            else -> null
        }
    }

    private fun ShiftLeft.generateCode(collector: MutableList<Instruction>): Value? {
        collector.add(
            instructions.getBinary(
                BinaryOpcode.SHL,
                base.generateCode(collector) ?: return null,
                shift.generateCode(collector) ?: return null
            )
        )
        return collector.last()
    }

    private fun ShiftRight.generateCode(collector: MutableList<Instruction>): Value? {
        collector.add(
            instructions.getBinary(
                BinaryOpcode.SHR,
                base.generateCode(collector) ?: return null,
                shift.generateCode(collector) ?: return null
            )
        )
        return collector.last()
    }

    private fun lcm(a: Long, b: Long): Long = (a / gcd(a, b)) * b

    private fun rebuild(loop: Loop) {
        val phies = loop.body.flatMap { it.instructions }.mapNotNull { it as? PhiInst }
            .mapNotNull { if (phiToEvo.containsKey(it)) it else null }
        val phiBlock = loop.header
        phies.forEach {
            val li = mutableListOf<Instruction>()
            val res = reconstructPhi(it, li)
            if (res != null) {
                val newBlock = BodyBlock("loop.block")
                newBlock.addAll(li)
                insertBefore(phiBlock.successors.first(), newBlock, loop)
                it.replaceAllUsesWith(res)
            }
        }
    }
}
