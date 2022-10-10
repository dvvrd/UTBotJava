package org.utbot.engine.soot

import org.utbot.engine.*
import soot.Type
import soot.RefLikeType
import soot.RefType
import soot.ArrayType
import soot.VoidType
import soot.ByteType
import soot.ShortType
import soot.CharType
import soot.LongType
import soot.IntType
import soot.FloatType
import soot.DoubleType
import soot.BooleanType


typealias TypeStorage = org.utbot.engine.TypeStorage<Type>
typealias ArrayTypeStorage = org.utbot.engine.TypeStorage<Type>
typealias RefTypeStorage = org.utbot.engine.TypeStorage<Type>

typealias SymbolicValue = org.utbot.engine.SymbolicValue<Type>
typealias PrimitiveValue = org.utbot.engine.PrimitiveValue<Type>
typealias ReferenceValue = org.utbot.engine.ReferenceValue<Type>
typealias ObjectValue = org.utbot.engine.ObjectValue<Type>
typealias ArrayValue = org.utbot.engine.ArrayValue<Type>
typealias Concrete = org.utbot.engine.Concrete

typealias Memory = org.utbot.engine.Memory<Type>
typealias MemoryUpdate = org.utbot.engine.MemoryUpdate<Type>
typealias SymbolicState = org.utbot.engine.SymbolicState<Type>
typealias SymbolicStateUpdate = org.utbot.engine.SymbolicStateUpdate<Type>
typealias LocalVariableMemory = org.utbot.engine.LocalVariableMemory<Type>
typealias LocalMemoryUpdate = org.utbot.engine.LocalMemoryUpdate<Type>

typealias MockInfoEnriched = org.utbot.engine.MockInfoEnriched<Type>
typealias MockExecutableInstance = org.utbot.engine.MockExecutableInstance<Type>

/**
 * Creates memory chunk descriptor.
 *
 * If the given type is ArrayType, i.e. int[][], we have to create sort for it.
 * Otherwise, there is two options: either type is a RefType and it's elementType is Array, so the corresponding
 * array stores addresses, or it stores primitive values themselves.
 */
fun memoryChunkDescriptor(id: ChunkId, type: RefLikeType, elementType: Type): MemoryChunkDescriptor {
    val itemSort =
        when (type) {
            is ArrayType -> type.toSort()
            else -> if (elementType is ArrayType) UtAddrSort else elementType.toSort()
        }
    return MemoryChunkDescriptor(id, itemSort)
}

val voidValue
    get() = PrimitiveValue(VoidType.v(), nullObjectAddr)

fun UtExpression.toPrimitiveValue(type: Type) = PrimitiveValue(type, this)
fun UtExpression.toByteValue() = this.toPrimitiveValue(ByteType.v())
fun UtExpression.toShortValue() = this.toPrimitiveValue(ShortType.v())
fun UtExpression.toCharValue() = this.toPrimitiveValue(CharType.v())
fun UtExpression.toLongValue() = this.toPrimitiveValue(LongType.v())
fun UtExpression.toIntValue() = this.toPrimitiveValue(IntType.v())
fun UtExpression.toFloatValue() = this.toPrimitiveValue(FloatType.v())
fun UtExpression.toDoubleValue() = this.toPrimitiveValue(DoubleType.v())
fun UtExpression.toBoolValue() = this.toPrimitiveValue(BooleanType.v())

fun Byte.toPrimitiveValue() = mkByte(this).toByteValue()
fun Short.toPrimitiveValue() = mkShort(this).toShortValue()
fun Char.toPrimitiveValue() = mkChar(this).toCharValue()
fun Int.toPrimitiveValue() = mkInt(this).toIntValue()
fun Long.toPrimitiveValue() = mkLong(this).toLongValue()
fun Float.toPrimitiveValue() = mkFloat(this).toFloatValue()
fun Double.toPrimitiveValue() = mkDouble(this).toDoubleValue()
fun Boolean.toPrimitiveValue() = mkBool(this).toBoolValue()

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

fun mkEq(left: PrimitiveValue, right: PrimitiveValue): UtBoolExpression = Eq(left.expr, right.expr)

fun PrimitiveValue.cast(type: Type) = PrimitiveValue(type, UtCastExpression(expr, type.toSort()))

fun PrimitiveValue.align(): PrimitiveValue = when (type) {
    is ByteType, is ShortType, is CharType -> UtCastExpression(expr, UtIntSort).toIntValue()
    else -> this
}

val ObjectValue.refType: RefType
    get() = type as RefType

val ArrayValue.arrayType: ArrayType
    get() = type as ArrayType

fun Memory.findArrayLength(addr: UtAddrExpression) =
    findArrayLengthExpression(addr).toIntValue()
