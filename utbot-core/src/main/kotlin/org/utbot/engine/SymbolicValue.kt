package org.utbot.engine

import org.utbot.framework.plugin.api.SYMBOLIC_NULL_ADDR
import java.util.Objects

/**
 * Base class for all symbolic memory cells: primitive, reference, arrays
 */
sealed class SymbolicValue {
    abstract val concrete: Concrete?
    abstract val sort: UtSort
    abstract val hashCode: Int
}

/**
 * Wrapper that contains concrete value in given memory cells. Could be used for optimizations or wrappers (when you
 * do not do symbolic execution honestly but invoke tweaked behavior).
 */
data class Concrete(val value: Any?)

/**
 * Memory cell that contains primitive value as the result
 */
data class PrimitiveValue(
    override val sort: UtPrimitiveSort,
    val expr: UtExpression,
    override val concrete: Concrete? = null
) : SymbolicValue() {

    override val hashCode = Objects.hash(sort, expr, concrete)

    override fun toString() = "($sort $expr)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PrimitiveValue

        if (sort != other.sort) return false
        if (expr != other.expr) return false
        if (concrete != other.concrete) return false

        return true
    }

    override fun hashCode() = hashCode
}

/**
 * Memory cell that can contain any reference value: array or object
 */
sealed class ReferenceValue(open val addr: UtAddrExpression) : SymbolicValue()


/**
 * Memory cell contains ordinal objects (not arrays).
 */
open class ObjectValue(
    final override val addr: UtAddrExpression,
    final override val concrete: Concrete? = null
) : ReferenceValue(addr) {
    override val hashCode = Objects.hash(addr, concrete)

    override val sort: UtSort = UtAddrSort

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ObjectValue

        if (addr != other.addr) return false
        if (concrete != other.concrete) return false

        return true
    }

    override fun hashCode() = hashCode

    override fun toString() = "ObjectValue(addr=$addr${concretePart()})"

    private fun concretePart() = concrete?.let { ", concrete=$concrete" } ?: ""
}


/**
 * Memory cell contains java arrays.
 */
open class ArrayValue(
    final override val addr: UtAddrExpression,
    final override val concrete: Concrete? = null
) : ReferenceValue(addr) {
    override val sort get() = UtAddrSort

    override val hashCode = Objects.hash(addr, concrete)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ArrayValue

        if (addr != other.addr) return false
        if (concrete != other.concrete) return false

        return true
    }

    override fun hashCode() = hashCode
}

val SymbolicValue.asPrimitiveValueOrError
    get() = (this as? PrimitiveValue)?.expr ?: error("${this::class} is not a primitive")

val SymbolicValue.addr
    get() = when (this) {
        is ReferenceValue -> addr
        is PrimitiveValue -> error("PrimitiveValue $this doesn't have an address")
    }

val SymbolicValue.isConcrete: Boolean
    get() = when (this) {
        is PrimitiveValue -> this.expr.isConcrete
        is ArrayValue, is ObjectValue -> false
    }

fun SymbolicValue.toConcrete(): Any = when (this) {
        is PrimitiveValue -> this.expr.toConcrete()
        is ArrayValue, is ObjectValue -> error("Can't get concrete value for $this")
    }

fun UtExpression.toPrimitiveValue(sort: UtPrimitiveSort) = PrimitiveValue(sort, this)
fun UtExpression.toByteValue() = this.toPrimitiveValue(UtByteSort)
fun UtExpression.toShortValue() = this.toPrimitiveValue(UtShortSort)
fun UtExpression.toCharValue() = this.toPrimitiveValue(UtCharSort)
fun UtExpression.toLongValue() = this.toPrimitiveValue(UtLongSort)
fun UtExpression.toIntValue() = this.toPrimitiveValue(UtInt32Sort)
fun UtExpression.toFloatValue() = this.toPrimitiveValue(UtFloatSort)
fun UtExpression.toDoubleValue() = this.toPrimitiveValue(UtDoubleSort)
fun UtExpression.toBoolValue() = this.toPrimitiveValue(UtBoolSort)

fun Byte.toPrimitiveValue() = mkByte(this).toByteValue()
fun Short.toPrimitiveValue() = mkShort(this).toShortValue()
fun Char.toPrimitiveValue() = mkChar(this).toCharValue()
fun Int.toPrimitiveValue() = mkInt(this).toIntValue()
fun Long.toPrimitiveValue() = mkLong(this).toLongValue()
fun Float.toPrimitiveValue() = mkFloat(this).toFloatValue()
fun Double.toPrimitiveValue() = mkDouble(this).toDoubleValue()
fun Boolean.toPrimitiveValue() = mkBool(this).toBoolValue()


val nullObjectAddr = UtAddrExpression(mkInt(SYMBOLIC_NULL_ADDR))

val voidValue
    get() = PrimitiveValue(UtVoidSort, nullObjectAddr)

fun Any?.primitiveToLiteral() = when (this) {
    null -> nullObjectAddr
    is Byte -> mkByte(this)
    is Short -> mkShort(this)
    is Char -> mkChar(this)
    is Int -> mkInt(this)
    is Long -> mkLong(this)
    is Float -> mkFloat(this)
    is Double -> mkDouble(this)
    is Boolean -> mkBool(this)
    else -> error("Unknown class: ${this::class} to convert to Literal")
}

fun Any?.primitiveToSymbolic() = when (this) {
    null -> nullObjectAddr.toIntValue()
    is Byte -> this.toPrimitiveValue()
    is Short -> this.toPrimitiveValue()
    is Char -> this.toPrimitiveValue()
    is Int -> this.toPrimitiveValue()
    is Long -> this.toPrimitiveValue()
    is Float -> this.toPrimitiveValue()
    is Double -> this.toPrimitiveValue()
    is Boolean -> this.toPrimitiveValue()
    is Unit -> voidValue
    else -> error("Unknown class: ${this::class} to convert to PrimitiveValue")
}
