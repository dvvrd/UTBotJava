package org.utbot.engine.typed

import org.utbot.engine.*
import soot.ArrayType
import soot.RefType
import java.util.*

/**
 * Memory cell contains ordinal objects (not arrays) decorated with subtyping information.
 *
 * Note: if you create an object, be sure you add constraints for its type using [TypeConstraint],
 * otherwise it is possible for an object to have inappropriate or incorrect typeId and dimensionNum.
 *
 * @see TypeRegistry.typeConstraint
 * @see Traverser.createObject
 */
data class TypedObjectValue(
    val typeStorage: TypeStorage,
    override val addr: UtAddrExpression,
    override val concrete: Concrete? = null
) : ObjectValue(addr, concrete) {

    val type: RefType get() = typeStorage.leastCommonType as RefType

    val possibleConcreteTypes get() = typeStorage.possibleConcreteTypes

    override val hashCode = Objects.hash(typeStorage, addr, concrete)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TypedObjectValue

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
 * Memory cell contains java arrays decorated with subtyping information.
 *
 * Note: if you create an array, be sure you add constraints for its type using [TypeConstraint],
 * otherwise it is possible for an object to have inappropriate or incorrect typeId and dimensionNum.
 *
 * @see TypeRegistry.typeConstraint
 * @see Traverser.createObject
 */
data class TypedArrayValue(
    val typeStorage: TypeStorage,
    override val addr: UtAddrExpression,
    override val concrete: Concrete? = null
) : ArrayValue(addr, concrete) {
    val type get() = typeStorage.leastCommonType as ArrayType

    val possibleConcreteTypes get() = typeStorage.possibleConcreteTypes

    override val hashCode = Objects.hash(typeStorage, addr, concrete)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TypedArrayValue

        if (typeStorage != other.typeStorage) return false
        if (addr != other.addr) return false
        if (concrete != other.concrete) return false

        return true
    }

    override fun hashCode() = hashCode
}

// TODO: one more constructor?
fun objectValue(type: RefType, addr: UtAddrExpression, implementation: WrapperInterface) =
    TypedObjectValue(TypeStorage(type), addr, Concrete(implementation))
