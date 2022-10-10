package org.utbot.engine

import org.utbot.framework.plugin.api.SYMBOLIC_NULL_ADDR
import java.util.Objects

/**
 * Keeps most common type and possible types, to resolve types in uncertain situations, like virtual invokes.
 *
 * Note: [leastCommonType] might be an interface or abstract type in opposite to the [possibleConcreteTypes]
 * that **usually** contains only concrete types (so-called appropriate). The only way to create [TypeStorage] with
 * inappropriate possibleType is to create it using constructor with the only type.
 *
 * @see isAppropriate
 */
data class TypeStorage<out Type>(val leastCommonType: Type, val possibleConcreteTypes: Set<Type>) {
    private val hashCode = Objects.hash(leastCommonType, possibleConcreteTypes)

/**
     * Construct a type storage with some type. In this case [possibleConcreteTypes] might contains
     * abstract class or interface. Usually it means such typeStorage represents wrapper object type.
     */
    constructor(concreteType: Type) : this(concreteType, setOf(concreteType))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TypeStorage<*>

        if (leastCommonType != other.leastCommonType) return false
        if (possibleConcreteTypes != other.possibleConcreteTypes) return false

        return true
    }

    override fun hashCode() = hashCode

    override fun toString() = if (possibleConcreteTypes.size == 1) {
        "$leastCommonType"
    } else {
        "(leastCommonType=$leastCommonType, ${possibleConcreteTypes.size} possibleTypes=${possibleConcreteTypes.take(10)})"
    }
}

/**
 * Base class for all symbolic memory cells: primitive, reference, arrays
 */
sealed class SymbolicValue<out Type> {
    abstract val concrete: Concrete?
    // TODO: replace with type
    abstract val type: Type
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
data class PrimitiveValue<out Type>(
    override val type: Type,
    val expr: UtExpression,
    override val concrete: Concrete? = null
) : SymbolicValue<Type>() {

    override val hashCode = Objects.hash(expr, concrete)

    override fun toString() = "($expr.sort $expr)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PrimitiveValue<*>

        if (type != other.type) return false
        if (expr != other.expr) return false
        if (concrete != other.concrete) return false

        return true
    }

    override fun hashCode() = hashCode
}

/**
 * Memory cell that can contain any reference value: array or object
 */
sealed class ReferenceValue<out Type>(
    open val typeStorage: TypeStorage<Type>,
    open val addr: UtAddrExpression) : SymbolicValue<Type>() {

    override val type: Type get() = typeStorage.leastCommonType
    val possibleConcreteTypes get() = typeStorage.possibleConcreteTypes
}


/**
 * Memory cell contains ordinal objects (not arrays).
 *
 * Note: if you create an object, be sure you add constraints for its type using [TypeConstraint],
 * otherwise it is possible for an object to have inappropriate or incorrect typeId and dimensionNum.
 *
 * @see TypeRegistry.typeConstraint
 * @see Traverser.createObject
 */
data class ObjectValue<out Type>(
    override val typeStorage: TypeStorage<Type>,
    override val addr: UtAddrExpression,
    override val concrete: Concrete? = null
) : ReferenceValue<Type>(typeStorage, addr) {

    override val hashCode = Objects.hash(typeStorage, addr, concrete)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ObjectValue<*>

        if (typeStorage != other.typeStorage) return false
        if (addr != other.addr) return false
        if (concrete != other.concrete) return false

        return true
    }

    override fun hashCode() = hashCode

    override fun toString() = "ObjectValue(typeStorage=$typeStorage, addr=$addr${concretePart()})"

    private fun concretePart() = concrete?.let { ", concrete=$concrete" } ?: ""
}


/**
 * Memory cell contains java arrays.
 *
 * Note: if you create an array, be sure you add constraints for its type using [TypeConstraint],
 * otherwise it is possible for an object to have inappropriate or incorrect typeId and dimensionNum.
 *
 * @see TypeRegistry.typeConstraint
 * @see Traverser.createObject
 */
data class ArrayValue<out Type>(
    override val typeStorage: TypeStorage<Type>,
    override val addr: UtAddrExpression,
    override val concrete: Concrete? = null
) : ReferenceValue<Type>(typeStorage, addr) {

    override val hashCode = Objects.hash(typeStorage, addr, concrete)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ArrayValue<*>

        if (typeStorage != other.typeStorage) return false
        if (addr != other.addr) return false
        if (concrete != other.concrete) return false

        return true
    }

    override fun hashCode() = hashCode
}

val <Type> SymbolicValue<Type>.asPrimitiveValueOrError
    get() = (this as? PrimitiveValue<Type>)?.expr ?: error("${this::class} is not a primitive")

val <Type> SymbolicValue<Type>.addr
    get() = when (this) {
        is ReferenceValue<*> -> addr
        is PrimitiveValue<Type> -> error("PrimitiveValue $this doesn't have an address")
    }

val <Type> SymbolicValue<Type>.isConcrete: Boolean
    get() = when (this) {
        is PrimitiveValue<Type> -> this.expr.isConcrete
        else -> false
    }

fun <Type> SymbolicValue<Type>.toConcrete(): Any = when (this) {
        is PrimitiveValue<Type> -> this.expr.toConcrete()
        else -> error("Can't get concrete value for $this")
    }

val nullObjectAddr = UtAddrExpression(mkInt(SYMBOLIC_NULL_ADDR))

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

val UtSort.defaultValue: UtExpression
    get() = when (this) {
        UtByteSort -> mkByte(0)
        UtShortSort -> mkShort(0)
        UtCharSort -> mkChar(0)
        UtIntSort -> mkInt(0)
        UtLongSort -> mkLong(0L)
        UtFp32Sort -> mkFloat(0f)
        UtFp64Sort -> mkDouble(0.0)
        UtBoolSort -> mkBool(false)
        // empty string because we want to have a default value of the same sort as the items stored in the strings array
        UtSeqSort -> mkString("")
        is UtArraySort -> if (itemSort is UtArraySort) nullObjectAddr else mkArrayWithConst(this, itemSort.defaultValue)
        else -> nullObjectAddr
    }

internal const val MAX_STRING_LENGTH_SIZE_BITS = 8
