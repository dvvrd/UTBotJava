package org.utbot.engine.soot

import com.microsoft.z3.*
import org.utbot.common.WorkaroundReason
import org.utbot.common.workaround
import org.utbot.engine.MemoryChunkDescriptor
import org.utbot.engine.UtNamedStore
import org.utbot.engine.UtExpressionVisitor
import org.utbot.engine.RewritingVisitor
import org.utbot.engine.Z3TranslatorVisitor
import org.utbot.engine.Z3EvaluatorVisitor
import org.utbot.engine.UtExpression
import org.utbot.engine.UtAddrExpression
import org.utbot.engine.UtBoolExpression
import org.utbot.engine.asHardConstraint
import org.utbot.engine.nullObjectAddr
import soot.PrimType
import soot.RefType
import soot.Type

interface JimpleExpressionVisitor<TResult> : UtExpressionVisitor<TResult> {
    fun visit(expr: UtIsExpression): TResult
    fun visit(expr: UtGenericExpression): TResult
    fun visit(expr: UtInstanceOfExpression): TResult
    fun visit(expr: UtEqGenericTypeParametersExpression): TResult
    fun visit(expr: UtIsGenericTypeExpression): TResult
}

class JimpleRewritingVisitor(
    constraints: Set<UtBoolExpression> = emptySet(),
    eqs: Map<UtExpression, UtExpression> = emptyMap(),
    lts: Map<UtExpression, Long> = emptyMap(),
    gts: Map<UtExpression, Long> = emptyMap()
) : RewritingVisitor(constraints, eqs, lts, gts), JimpleExpressionVisitor<UtExpression> {

    override fun visit(expr: UtIsExpression): UtExpression = applySimplification(expr, false) {
        UtIsExpression(expr.addr.accept(this) as UtAddrExpression, expr.typeStorage, expr.numberOfTypes)
    }

    override fun visit(expr: UtGenericExpression): UtExpression = applySimplification(expr, false) {
        UtGenericExpression(expr.addr.accept(this) as UtAddrExpression, expr.types, expr.numberOfTypes)
    }

    override fun visit(expr: UtIsGenericTypeExpression): UtExpression = applySimplification(expr, false) {
        val addr = expr.addr.accept(this) as UtAddrExpression
        val baseAddr = expr.baseAddr.accept(this) as UtAddrExpression
        (constraints.singleOrNull { it is UtGenericExpression && it.addr == baseAddr } as? UtGenericExpression)?.let { generic ->
            UtIsExpression(
                addr,
                generic.types[expr.parameterTypeIndex],
                generic.numberOfTypes
            )
        } ?: UtIsGenericTypeExpression(addr, baseAddr, expr.parameterTypeIndex)
    }

    override fun visit(expr: UtEqGenericTypeParametersExpression): UtExpression =
        applySimplification(expr, false) {
            val firstAddr = expr.firstAddr.accept(this) as UtAddrExpression
            val secondAddr = expr.secondAddr.accept(this) as UtAddrExpression
            (constraints.singleOrNull { it is UtGenericExpression && it.addr == secondAddr } as? UtGenericExpression)?.let { generic ->
                UtGenericExpression(
                    firstAddr,
                    List(expr.indexMapping.size) { generic.types[expr.indexMapping[it]!!] },
                    generic.numberOfTypes
                )
            } ?:
            UtEqGenericTypeParametersExpression(
                firstAddr,
                secondAddr,
                expr.indexMapping
            )
        }

    override fun visit(expr: UtInstanceOfExpression): UtExpression = applySimplification(expr, false) {
        val simplifiedHard = (expr.constraint.accept(this) as UtBoolExpression).asHardConstraint()
        UtInstanceOfExpression(expr.symbolicStateUpdate.copy(hardConstraints = simplifiedHard))
    }

}

/**
 * Create [UtNamedStore] with simplified [index] and [value] expressions.
 *
 * @see RewritingVisitor, JimpleRewritingVisitor
 */
fun simplifiedNamedStore(
    chunkDescriptor: MemoryChunkDescriptor,
    index: UtExpression,
    value: UtExpression
) = JimpleRewritingVisitor().let { visitor -> UtNamedStore(chunkDescriptor, index.accept(visitor), value.accept(visitor)) }

class JimpleZ3EvaluatorVisitor(private val model: Model, private val translator: JimpleZ3TranslatorVisitor) :
    Z3EvaluatorVisitor(model, translator), JimpleExpressionVisitor<Expr> {

    override fun visit(expr: UtIsExpression): Expr = translator.translate(expr)
    override fun visit(expr: UtGenericExpression): Expr = translator.visit(expr)

    override fun visit(expr: UtInstanceOfExpression): Expr =
        expr.run { eval(expr.constraint) }

    override fun visit(expr: UtEqGenericTypeParametersExpression): Expr = translator.visit(expr)

    override fun visit(expr: UtIsGenericTypeExpression): Expr = translator.visit(expr)

}

class JimpleZ3TranslatorVisitor(
    private val z3Context: Context,
    private val typeRegistry: TypeRegistry
) : Z3TranslatorVisitor(z3Context), JimpleExpressionVisitor<Expr> {

    /**
     * Translation method for an UtIsExpression. A way for depends on the amount of possible types for the given expr.
     *
     * If this number less than MAX_TYPE_NUMBER_FOR_ENUMERATION, we use enumeration:
     * we build an expression 'or (symType == t1) (symType == t2) (symType == t2) …'.
     *
     * But when this number greater than MAX_TYPE_NUMBER_FOR_ENUMERATION or equals to it, we use bit-vectors:
     * we build a bit-vector with length expr.numberOfTypes. One on some position k means
     * that object can be an instance of some type with id k (zero means it cannot be).
     * Thus, we create such bit-vector for given possibleTypes from the expr.typeStorage, let's call it typeVector.
     * Now we can add type condition for expr's typeId: (1 << typeId) & ~typeVector == 0
     *
     * The reason, why we cannot just translate everything in one way is performance. It is too expensive to
     * use bit-vector all the time, but enumeration can cause significant problems, for example, for Objects.
     *
     * @param expr  the type expression
     * @return  the type expression translated into z3 assertions
     * @see UtIsExpression
     * @see MAX_TYPE_NUMBER_FOR_ENUMERATION
     */
    override fun visit(expr: UtIsExpression): Expr = expr.run {
        val symNumDimensions = translate(typeRegistry.symNumDimensions(addr)) as BitVecExpr
        val symTypeId = translate(typeRegistry.symTypeId(addr)) as BitVecExpr

        val constraints = mutableListOf<BoolExpr>()

        // TODO remove it JIRA:1321
        val filteredPossibleTypes = workaround(WorkaroundReason.HACK) { typeStorage.filterInappropriateTypes() }

        // add constraints for typeId
        if (typeStorage.possibleConcreteTypes.size < MAX_TYPE_NUMBER_FOR_ENUMERATION) {
            val symType = translate(typeRegistry.symTypeId(addr))
            val possibleBaseTypes = filteredPossibleTypes.map { it.baseType }

            val typeConstraint = z3Context.mkOr(
                *possibleBaseTypes
                    .map { z3Context.mkEq(z3Context.mkBV(typeRegistry.findTypeId(it), Int.SIZE_BITS), symType) }
                    .toTypedArray()
            )

            constraints += typeConstraint
        } else {
            val shiftedExpression = z3Context.mkBVSHL(
                z3Context.mkZeroExt(numberOfTypes - 1, z3Context.mkBV(1, 1)),
                z3Context.mkZeroExt(numberOfTypes - Int.SIZE_BITS, symTypeId)
            )

            val bitVecString = typeRegistry.constructBitVecString(filteredPossibleTypes)
            val possibleTypesBitVector = z3Context.mkBV(bitVecString, numberOfTypes)

            val typeConstraint = z3Context.mkEq(
                z3Context.mkBVAND(shiftedExpression, z3Context.mkBVNot(possibleTypesBitVector)),
                z3Context.mkBV(0, numberOfTypes)
            )

            constraints += typeConstraint
        }

        val exprBaseType = expr.type.baseType
        val numDimensions = z3Context.mkBV(expr.type.numDimensions, Int.SIZE_BITS)

        constraints += if (exprBaseType.isJavaLangObject()) {
            z3Context.mkBVSGE(symNumDimensions, numDimensions)
        } else {
            z3Context.mkEq(symNumDimensions, numDimensions)
        }

        z3Context.mkAnd(*constraints.toTypedArray())
    }

    // TODO REMOVE IT JIRA:1321
    private fun TypeStorage.filterInappropriateTypes(): Collection<Type> {
        val filteredTypes = if (!leastCommonType.isJavaLangObject()) {
            possibleConcreteTypes
        } else {
            possibleConcreteTypes.filter {
                val baseType = it.baseType
                if (baseType is PrimType) return@filter true

                baseType as RefType
                "org.utbot.engine.overrides.collections" !in baseType.sootClass.packageName
            }
        }

        return filteredTypes.ifEmpty { possibleConcreteTypes }

    }

    override fun visit(expr: UtGenericExpression): Expr = expr.run {
        val constraints = mutableListOf<BoolExpr>()
        for (i in types.indices) {
            val symType = translate(typeRegistry.genericTypeId(addr, i))

            if (types[i].leastCommonType.isJavaLangObject()) {
                continue
            }

            val possibleBaseTypes = types[i].possibleConcreteTypes.map { it.baseType }

            val typeConstraint = z3Context.mkOr(
                *possibleBaseTypes.map {
                    z3Context.mkEq(
                        z3Context.mkBV(typeRegistry.findTypeId(it), Int.SIZE_BITS),
                        symType
                    )
                }.toTypedArray()
            )

            constraints += typeConstraint
        }

        z3Context.mkOr(
            z3Context.mkAnd(*constraints.toTypedArray()),
            z3Context.mkEq(translate(expr.addr), translate(nullObjectAddr))
        )
    }

    override fun visit(expr: UtIsGenericTypeExpression): Expr = expr.run {
        val symType = translate(typeRegistry.symTypeId(addr))
        val symNumDimensions = translate(typeRegistry.symNumDimensions(addr))

        val genericSymType = translate(typeRegistry.genericTypeId(baseAddr, parameterTypeIndex))
        val genericNumDimensions = translate(typeRegistry.genericNumDimensions(baseAddr, parameterTypeIndex))

        val dimensionsConstraint = z3Context.mkEq(symNumDimensions, genericNumDimensions)

        val equalTypeConstraint = z3Context.mkAnd(
            z3Context.mkEq(symType, genericSymType),
            dimensionsConstraint
        )

        val typeConstraint = z3Context.mkOr(
            equalTypeConstraint,
            z3Context.mkEq(translate(expr.addr), translate(nullObjectAddr))
        )

        z3Context.mkAnd(typeConstraint, dimensionsConstraint)
    }

    override fun visit(expr: UtEqGenericTypeParametersExpression): Expr = expr.run {
        val constraints = mutableListOf<BoolExpr>()
        for ((i, j) in indexMapping) {
            val firstSymType = translate(typeRegistry.genericTypeId(firstAddr, i))
            val secondSymType = translate(typeRegistry.genericTypeId(secondAddr, j))
            constraints += z3Context.mkEq(firstSymType, secondSymType)

            val firstSymNumDimensions = translate(typeRegistry.genericNumDimensions(firstAddr, i))
            val secondSymNumDimensions = translate(typeRegistry.genericNumDimensions(secondAddr, j))
            constraints += z3Context.mkEq(firstSymNumDimensions, secondSymNumDimensions)
        }
        z3Context.mkAnd(*constraints.toTypedArray())
    }

    override fun visit(expr: UtInstanceOfExpression): Expr =
        expr.run { translate(expr.constraint) }

}

