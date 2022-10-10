package org.utbot.engine

import java.util.*

/**
 * A class representing type for an object
 * @param addr Object's symbolic address.
 * @param typeStorage Object's type holder.
 * @param numberOfTypes Number of types in the whole program.
 * @see org.utbot.engine.TypeStorage
 */
class UtIsExpression(
    val addr: UtAddrExpression,
    val typeStorage: TypeStorage,
    val numberOfTypes: Int
) : UtBoolExpression() {
    val type: Type get() = typeStorage.leastCommonType

    override val hashCode = Objects.hash(addr, typeStorage, numberOfTypes)

    override fun <TResult> accept(visitor: UtExpressionVisitor<TResult>): TResult = visitor.visit(this)

    override fun toString(): String {
        val possibleTypes = if (typeStorage.possibleConcreteTypes.size == 1) {
            ""
        } else {
            ". Possible types number: ${typeStorage.possibleConcreteTypes.size}"
        }

        return "(is $addr ${typeStorage.leastCommonType}$possibleTypes)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UtIsExpression

        if (addr != other.addr) return false
        if (typeStorage != other.typeStorage) return false
        if (numberOfTypes != other.numberOfTypes) return false

        return true
    }

    override fun hashCode() = hashCode
}

/**
 * [UtBoolExpression] that represents that an object with address [addr] is parameterized by [types]
 */
class UtGenericExpression(
    val addr: UtAddrExpression,
    val types: List<TypeStorage>,
    val numberOfTypes: Int
) : UtBoolExpression() {
    override val hashCode = Objects.hash(addr, types, numberOfTypes)
    override fun <TResult> accept(visitor: UtExpressionVisitor<TResult>): TResult = visitor.visit(this)

    override fun toString(): String {
        val postfix = types.joinToString(",", "<", ">")
        return "(generic $addr $postfix)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UtGenericExpression

        if (addr != other.addr) return false
        if (types != other.types) return false
        if (numberOfTypes != other.numberOfTypes) return false

        return true
    }

    override fun hashCode() = hashCode
}

/**
 * This class represents a result of the `instanceof` instruction.
 * DO NOT MIX IT UP WITH [UtIsExpression]. This one should be used in the result of the instanceof instruction ONLY.
 * It does NOT represent types for objects.
 *
 * [UtInstanceOfExpression.constraint] predicate that should be fulfilled in the branch where `(a instanceof type)` is true.
 * @param symbolicStateUpdate symbolic state update for the returning true branch of the instanceof expression. Used to
 * update type of array in the code like this: (Object instanceof int[]). The object should be updated to int[]
 * in the memory.
 *
 * @see UtIsExpression
 */
class UtInstanceOfExpression(
    val symbolicStateUpdate: SymbolicStateUpdate = SymbolicStateUpdate()
) : UtBoolExpression() {
    val constraint: UtBoolExpression get() = mkAnd(symbolicStateUpdate.hardConstraints.constraints.toList())
    override val hashCode = symbolicStateUpdate.hashCode()

    override fun <TResult> accept(visitor: UtExpressionVisitor<TResult>): TResult = visitor.visit(this)

    override fun toString() = "$constraint"
}


/**
 * [UtBoolExpression] class that represents that type parameters of an object with address [firstAddr] are equal to
 * type parameters of an object with address [secondAddr], corresponding to [indexMapping].
 *
 * For instance, if the second object is parametrized by <Int, Double> and indexMapping is (0 to 1, 1 to 0) then this
 * expression is true, when the first object is parametrized by <Double, Int>
 */
class UtEqGenericTypeParametersExpression(
    val firstAddr: UtAddrExpression,
    val secondAddr: UtAddrExpression,
    val indexMapping: Map<Int, Int>
) : UtBoolExpression() {
    override val hashCode = Objects.hash(firstAddr, secondAddr, indexMapping)
    override fun <TResult> accept(visitor: UtExpressionVisitor<TResult>): TResult = visitor.visit(this)

    override fun toString(): String {
        return "(generic-eq $firstAddr $secondAddr by <${indexMapping.toList()}>)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UtEqGenericTypeParametersExpression

        if (firstAddr != other.firstAddr) return false
        if (secondAddr != other.secondAddr) return false
        if (indexMapping != other.indexMapping) return false

        return true
    }

    override fun hashCode() = hashCode
}

/**
 * [UtBoolExpression] that represents that an object with address [addr] has the same type as the type parameter
 * with index [parameterTypeIndex] of an object with address [baseAddr]
 *
 * For instance, if the base object is parametrized by <Int, Double> and parameterTypeIndex = 1, then this expression is
 * true when the object with address addr is Double
 */
class UtIsGenericTypeExpression(
    val addr: UtAddrExpression,
    val baseAddr: UtAddrExpression,
    val parameterTypeIndex: Int
) : UtBoolExpression() {
    override val hashCode = Objects.hash(addr, baseAddr, parameterTypeIndex)
    override fun <TResult> accept(visitor: UtExpressionVisitor<TResult>): TResult = visitor.visit(this)

    override fun toString(): String {
        return "(generic-is $addr $baseAddr<\$$parameterTypeIndex>)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UtIsGenericTypeExpression

        if (addr != other.addr) return false
        if (baseAddr != other.baseAddr) return false
        if (parameterTypeIndex != other.parameterTypeIndex) return false

        return true
    }

    override fun hashCode() = hashCode
}
