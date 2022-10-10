package org.utbot.engine

import org.utbot.engine.z3.BinOperator
import org.utbot.engine.z3.BoolOperator

sealed class UtOperator<T : UtExpression>(val delegate: BinOperator) {
    abstract operator fun invoke(left: UtExpression, right: UtExpression): T

    override fun toString(): String = this.javaClass.simpleName
}

sealed class UtBinOperator(
    delegate: BinOperator,
    private val sort: (UtExpression, UtExpression) -> UtSort = ::maxSort
) : UtOperator<UtOpExpression>(delegate) {
    override operator fun invoke(left: UtExpression, right: UtExpression): UtOpExpression =
        UtOpExpression(this, left, right, sort(left, right))

    operator fun <Type> invoke(left: PrimitiveValue<Type>, right: PrimitiveValue<Type>): UtOpExpression =
        UtOpExpression(this, left.expr, right.expr, sort(left.expr, right.expr))
}

sealed class UtBoolOperator(delegate: BoolOperator) : UtOperator<UtBoolOpExpression>(delegate) {
    override operator fun invoke(left: UtExpression, right: UtExpression): UtBoolOpExpression =
        UtBoolOpExpression(this, left, right)

    operator fun invoke(left: UtExpression, right: Int): UtBoolOpExpression =
        UtBoolOpExpression(this, left, mkInt(right))

    operator fun <Type> invoke(left: PrimitiveValue<Type>, right: PrimitiveValue<Type>): UtBoolOpExpression =
        UtBoolOpExpression(this, left.expr, right.expr)

    operator fun <Type> invoke(left: PrimitiveValue<Type>, right: Int): UtBoolOpExpression =
        UtBoolOpExpression(this, left.expr, mkInt(right))
}

object Le : UtBoolOperator(org.utbot.engine.z3.Le)
object Lt : UtBoolOperator(org.utbot.engine.z3.Lt)
object Ge : UtBoolOperator(org.utbot.engine.z3.Ge)
object Gt : UtBoolOperator(org.utbot.engine.z3.Gt)
object Eq : UtBoolOperator(org.utbot.engine.z3.Eq)
object Ne : UtBoolOperator(org.utbot.engine.z3.Ne)

object Rem : UtBinOperator(org.utbot.engine.z3.Rem)
object Div : UtBinOperator(org.utbot.engine.z3.Div)
object Mul : UtBinOperator(org.utbot.engine.z3.Mul)
object Add : UtBinOperator(org.utbot.engine.z3.Add)
object Sub : UtBinOperator(org.utbot.engine.z3.Sub)

object Shl : UtBinOperator(org.utbot.engine.z3.Shl, ::leftOperandType)
object Shr : UtBinOperator(org.utbot.engine.z3.Shr, ::leftOperandType)
object Ushr : UtBinOperator(org.utbot.engine.z3.Ushr, ::leftOperandType)

object Xor : UtBinOperator(org.utbot.engine.z3.Xor)
object Or : UtBinOperator(org.utbot.engine.z3.Or)
object And : UtBinOperator(org.utbot.engine.z3.And)

/**
 * NaN related logic - comparison anything with NaN gives false.
 * For that we have special instructions cmpl/cmpg.
 * If at least one of value1 or value2 is NaN, the result of the cmpg instruction is 1,
 * and the result of cmpl is -1.
 */
object Cmp : UtBinOperator(org.utbot.engine.z3.Cmp, ::intSort)
object Cmpl : UtBinOperator(org.utbot.engine.z3.Cmpl, ::intSort)
object Cmpg : UtBinOperator(org.utbot.engine.z3.Cmpg, ::intSort)

fun maxSort(left: UtExpression, right: UtExpression) =
    maxOf(left.sort, right.sort, UtIntSort, compareBy { it.rank() })

// Straight-forward maxSort when we don't want UtIntSort to be lesser limit
fun simpleMaxSort(left: UtExpression, right: UtExpression) =
    maxOf(left.sort, right.sort, compareBy { it.rank() })

@Suppress("UNUSED_PARAMETER")
private fun leftOperandType(left: UtExpression, right: UtExpression) = alignSort(left.sort)

@Suppress("UNUSED_PARAMETER")
private fun intSort(left: UtExpression, right: UtExpression) = UtIntSort

/**
 * Calculates a rank for UtSort to use as a comparator.
 * Ranks them by type and bit size.
 * FPSort is the highest type.
 * Note: BoolSort is higher than BitVecSort to cast 0/1 constants to boolean.
 */
private fun UtSort.rank(): Int = when (this) {
    UtFp64Sort -> 30000000 + 64
    UtFp32Sort -> 30000000 + 32
    UtBoolSort -> 20000000
    is UtBvSort -> 10000000 + this.size
    else -> error("Wrong sort $this")
}