package org.utbot.engine.soot

import com.github.curiousoddman.rgxgen.RgxGen
import org.utbot.common.unreachableBranch
import org.utbot.engine.soot.overrides.strings.UtNativeString
import org.utbot.engine.soot.overrides.strings.UtString
import org.utbot.engine.soot.overrides.strings.UtStringBuffer
import org.utbot.engine.soot.overrides.strings.UtStringBuilder
import org.utbot.engine.MemoryState
import org.utbot.engine.RewritingVisitor
import org.utbot.engine.UtExpression
import org.utbot.engine.UtAddrExpression
import org.utbot.engine.UtBoolExpression
import org.utbot.engine.UtConvertToString
import org.utbot.engine.UtFalse
import org.utbot.engine.UtIntSort
import org.utbot.engine.UtLongSort
import org.utbot.engine.UtAddrSort
import org.utbot.engine.UtSeqSort
import org.utbot.engine.UtStringCharAt
import org.utbot.engine.UtStringLength
import org.utbot.engine.UtStringToArray
import org.utbot.engine.UtStringToInt
import org.utbot.engine.UtTrue
import org.utbot.engine.ChunkId
import org.utbot.engine.MemoryChunkDescriptor
import org.utbot.engine.Lt
import org.utbot.engine.Le
import org.utbot.engine.isConcrete
import org.utbot.engine.mkAnd
import org.utbot.engine.mkChar
import org.utbot.engine.mkEq
import org.utbot.engine.mkInt
import org.utbot.engine.mkNot
import org.utbot.engine.mkString
import org.utbot.engine.mkArrayConst
import org.utbot.engine.select
import org.utbot.engine.toConcrete
import org.utbot.engine.asHardConstraint
import org.utbot.framework.plugin.api.UtArrayModel
import org.utbot.framework.plugin.api.UtAssembleModel
import org.utbot.framework.plugin.api.UtExecutableCallModel
import org.utbot.framework.plugin.api.UtModel
import org.utbot.framework.plugin.api.UtNullModel
import org.utbot.framework.plugin.api.UtPrimitiveModel
import org.utbot.framework.plugin.api.classId
import org.utbot.framework.plugin.api.id
import org.utbot.framework.plugin.api.util.charArrayClassId
import org.utbot.framework.plugin.api.util.charClassId
import org.utbot.framework.plugin.api.util.constructorId
import org.utbot.framework.plugin.api.util.defaultValueModel
import org.utbot.framework.util.nextModelName
import kotlin.math.max
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import soot.CharType
import soot.IntType
import soot.Scene
import soot.SootClass
import soot.SootField
import soot.SootMethod

val utStringClass: SootClass
    get() = Scene.v().getSootClass(UtString::class.qualifiedName)

class StringWrapper : BaseOverriddenWrapper(utStringClass.name) {
    private val toStringMethodSignature =
        overriddenClass.getMethodByName(UtString::toStringImpl.name).subSignature
    private val matchesMethodSignature =
        overriddenClass.getMethodByName(UtString::matchesImpl.name).subSignature
    private val charAtMethodSignature =
        overriddenClass.getMethodByName(UtString::charAtImpl.name).subSignature

    private fun Traverser.getValueArray(addr: UtAddrExpression) =
        getArrayField(addr, overriddenClass, STRING_VALUE)

    override fun Traverser.overrideInvoke(
        wrapper: ObjectValue,
        method: SootMethod,
        parameters: List<SymbolicValue>
    ): List<InvokeResult>? {
        return when (method.subSignature) {
            toStringMethodSignature -> {
                listOf(MethodResult(wrapper.copy(typeStorage = TypeStorage(method.returnType))))
            }
            matchesMethodSignature -> {
                val arg = parameters[0] as ObjectValue
                val matchingLengthExpr = getIntFieldValue(arg, STRING_LENGTH).accept(RewritingVisitor())

                if (!matchingLengthExpr.isConcrete) return null

                val matchingValueExpr =
                    selectArrayExpressionFromMemory(getValueArray(arg.addr)).accept(RewritingVisitor())
                val matchingLength = matchingLengthExpr.toConcrete() as Int
                val matchingValue = CharArray(matchingLength)

                for (i in 0 until matchingLength) {
                    val charExpr = matchingValueExpr.select(mkInt(i)).accept(RewritingVisitor())

                    if (!charExpr.isConcrete) return null

                    matchingValue[i] = (charExpr.toConcrete() as Number).toChar()
                }

                val rgxGen = RgxGen(String(matchingValue))
                val matching = (rgxGen.generate())
                val notMatching = rgxGen.generateNotMatching()

                val thisLength = getIntFieldValue(wrapper, STRING_LENGTH)
                val thisValue = selectArrayExpressionFromMemory(getValueArray(wrapper.addr))

                val matchingConstraints = mutableSetOf<UtBoolExpression>()
                matchingConstraints += mkEq(thisLength, mkInt(matching.length))
                for (i in matching.indices) {
                    matchingConstraints += mkEq(thisValue.select(mkInt(i)), mkChar(matching[i]))
                }

                val notMatchingConstraints = mutableSetOf<UtBoolExpression>()
                notMatchingConstraints += mkEq(thisLength, mkInt(notMatching.length))
                for (i in notMatching.indices) {
                    notMatchingConstraints += mkEq(thisValue.select(mkInt(i)), mkChar(notMatching[i]))
                }

                return listOf(
                    MethodResult(UtTrue.toBoolValue(), matchingConstraints.asHardConstraint()),
                    MethodResult(UtFalse.toBoolValue(), notMatchingConstraints.asHardConstraint())
                )
            }
            charAtMethodSignature -> {
                val index = parameters[0] as PrimitiveValue
                val lengthExpr = getIntFieldValue(wrapper, STRING_LENGTH)
                val inBoundsCondition = mkAnd(Le(0.toPrimitiveValue(), index), Lt(index, lengthExpr.toIntValue()))
                val failMethodResult =
                    MethodResult(
                        explicitThrown(
                            StringIndexOutOfBoundsException(),
                            findNewAddr(),
                            environment.state.isInNestedMethod()
                        ),
                        hardConstraints = mkNot(inBoundsCondition).asHardConstraint()
                    )

                val valueExpr = selectArrayExpressionFromMemory(getValueArray(wrapper.addr))

                val returnResult = MethodResult(
                    valueExpr.select(index.expr).toCharValue(),
                    hardConstraints = inBoundsCondition.asHardConstraint()
                )
                return listOf(returnResult, failMethodResult)
            }
            else -> return null
        }
    }

    override fun value(resolver: Resolver, wrapper: ObjectValue): UtModel = resolver.run {
        val classId = STRING_TYPE.id
        val addr = holder.concreteAddr(wrapper.addr)
        val modelName = nextModelName("string")

        val charType = CharType.v()
        val charArrayType = charType.arrayType

        val arrayValuesChunkId = resolver.typeRegistry.arrayChunkId(charArrayType)

        val valuesFieldChunkId = resolver.hierarchy.chunkIdForField(utStringClass.type, STRING_VALUE)
        val valuesFieldChunkDescriptor = memoryChunkDescriptor(valuesFieldChunkId, wrapper.refType, charArrayType)
        val valuesArrayAddr = resolver.findArray(valuesFieldChunkDescriptor, MemoryState.CURRENT).select(wrapper.addr)

        val valuesArrayDescriptor = memoryChunkDescriptor(arrayValuesChunkId, charArrayType, charType)
        val valuesArray = resolver.findArray(valuesArrayDescriptor, resolver.state)
        val valuesArrayExpression = valuesArray.select(valuesArrayAddr)

        val length = max(0, resolveIntField(wrapper, STRING_LENGTH))

        val values = UtArrayModel(
            holder.concreteAddr(wrapper.addr),
            charArrayClassId,
            length,
            charClassId.defaultValueModel(),
            stores = (0 until length).associateWithTo(mutableMapOf()) { i ->
                resolver.resolveModel(
                    valuesArrayExpression.select(mkInt(i)).toCharValue()
                )
            })

        val charValues = CharArray(length) { (values.stores[it] as UtPrimitiveModel).value as Char }
        val stringModel = UtPrimitiveModel(String(charValues))

        val instantiationCall = UtExecutableCallModel(
            instance = null,
            constructorId(classId, STRING_TYPE.classId),
            listOf(stringModel)
        )
        return UtAssembleModel(addr, classId, modelName, instantiationCall)
    }
}

internal val utNativeStringClass = Scene.v().getSootClass(UtNativeString::class.qualifiedName)

private var stringNameIndex = 0
private fun nextStringName() = "\$string${stringNameIndex++}"

class UtNativeStringWrapper : WrapperInterface {
    private val valueDescriptor = NATIVE_STRING_VALUE_DESCRIPTOR
    override fun Traverser.invoke(
        wrapper: ObjectValue,
        method: SootMethod,
        parameters: List<SymbolicValue>
    ): List<InvokeResult> =
        when (method.subSignature) {
            "void <init>()" -> {
                val newString = mkString(nextStringName())

                val memoryUpdate = MemoryUpdate(
                    stores = persistentListOf(simplifiedNamedStore(valueDescriptor, wrapper.addr, newString)),
                    touchedChunkDescriptors = persistentSetOf(valueDescriptor)
                )
                listOf(
                    MethodResult(
                        SymbolicSuccess(voidValue),
                        memoryUpdates = memoryUpdate
                    )
                )
            }
            "void <init>(int)" -> {
                val newString = UtConvertToString((parameters[0] as PrimitiveValue).expr)
                val memoryUpdate = MemoryUpdate(
                    stores = persistentListOf(simplifiedNamedStore(valueDescriptor, wrapper.addr, newString)),
                    touchedChunkDescriptors = persistentSetOf(valueDescriptor)
                )
                listOf(
                    MethodResult(
                        SymbolicSuccess(voidValue),
                        memoryUpdates = memoryUpdate
                    )
                )
            }
            "void <init>(long)" -> {
                val newString = UtConvertToString((parameters[0] as PrimitiveValue).expr)
                val memoryUpdate = MemoryUpdate(
                    stores = persistentListOf(simplifiedNamedStore(valueDescriptor, wrapper.addr, newString)),
                    touchedChunkDescriptors = persistentSetOf(valueDescriptor)
                )
                listOf(
                    MethodResult(
                        SymbolicSuccess(voidValue),
                        memoryUpdates = memoryUpdate
                    )
                )
            }
            "int length()" -> {
                val result = UtStringLength(memory.nativeStringValue(wrapper.addr))
                listOf(MethodResult(SymbolicSuccess(result.toByteValue().cast(IntType.v()))))
            }
            "char charAt(int)" -> {
                val index = (parameters[0] as PrimitiveValue).expr
                val result = UtStringCharAt(memory.nativeStringValue(wrapper.addr), index)
                listOf(MethodResult(SymbolicSuccess(result.toCharValue())))
            }
            "int codePointAt(int)" -> {
                val index = (parameters[0] as PrimitiveValue).expr
                val result = UtStringCharAt(memory.nativeStringValue(wrapper.addr), index)
                listOf(MethodResult(SymbolicSuccess(result.toCharValue().cast(IntType.v()))))
            }
            "int toInteger()" -> {
                val result = UtStringToInt(memory.nativeStringValue(wrapper.addr), UtIntSort)
                listOf(MethodResult(SymbolicSuccess(result.toIntValue())))
            }
            "long toLong()" -> {
                val result = UtStringToInt(memory.nativeStringValue(wrapper.addr), UtLongSort)
                listOf(MethodResult(SymbolicSuccess(result.toLongValue())))
            }
            "char[] toCharArray(int)" -> {
                val stringExpression = memory.nativeStringValue(wrapper.addr)
                val result = UtStringToArray(stringExpression, (parameters[0] as PrimitiveValue).expr)
                val length = UtStringLength(stringExpression)
                val type = CharType.v()
                val arrayType = type.arrayType
                val arrayValue = createNewArray(length.toIntValue(), arrayType, type)
                listOf(
                    MethodResult(
                        SymbolicSuccess(arrayValue),
                        memoryUpdates = arrayUpdateWithValue(arrayValue.addr, arrayType, result)
                    )
                )
            }
            else -> unreachableBranch("Unknown signature at the NativeStringWrapper.invoke: ${method.signature}")
        }

    override fun value(resolver: Resolver, wrapper: ObjectValue): UtModel = UtNullModel(STRING_TYPE.classId)
}

sealed class UtAbstractStringBuilderWrapper(className: String) : BaseOverriddenWrapper(className) {
    private val asStringBuilderMethodSignature =
        overriddenClass.getMethodByName("asStringBuilder").subSignature

    override fun Traverser.overrideInvoke(
        wrapper: ObjectValue,
        method: SootMethod,
        parameters: List<SymbolicValue>
    ): List<InvokeResult>? {
        if (method.subSignature == asStringBuilderMethodSignature) {
            return listOf(MethodResult(wrapper.copy(typeStorage = TypeStorage(method.returnType))))
        }

        return null
    }

    override fun value(resolver: Resolver, wrapper: ObjectValue): UtModel = resolver.run {
        val addr = holder.concreteAddr(wrapper.addr)
        val modelName = nextModelName("stringBuilder")

        val charType = CharType.v()
        val charArrayType = charType.arrayType

        val arrayValuesChunkId = typeRegistry.arrayChunkId(charArrayType)

        val valuesFieldChunkId = hierarchy.chunkIdForField(overriddenClass.type, overriddenClass.valueField)
        val valuesArrayAddrDescriptor = memoryChunkDescriptor(valuesFieldChunkId, wrapper.refType, charType)
        val valuesArrayAddr = findArray(valuesArrayAddrDescriptor, MemoryState.CURRENT).select(wrapper.addr)

        val valuesArrayDescriptor = memoryChunkDescriptor(arrayValuesChunkId, charArrayType, charType)
        val valuesArrayExpression = findArray(valuesArrayDescriptor, state).select(valuesArrayAddr)

        val length = resolveIntField(wrapper, overriddenClass.countField)

        val values = UtArrayModel(
            holder.concreteAddr(wrapper.addr),
            charArrayClassId,
            length,
            charClassId.defaultValueModel(),
            stores = (0 until length).associateWithTo(mutableMapOf()) { i ->
                resolver.resolveModel(valuesArrayExpression.select(mkInt(i)).toCharValue())
            })

        val charValues = CharArray(length) { (values.stores[it] as UtPrimitiveModel).value as Char }
        val stringModel = UtPrimitiveModel(String(charValues))
        val constructorId = constructorId(wrapper.type.classId, STRING_TYPE.classId)
        val instantiationCall = UtExecutableCallModel(
            instance = null,
            constructorId,
            listOf(stringModel)
        )
        return UtAssembleModel(addr, wrapper.type.classId, modelName, instantiationCall)
    }

    private val SootClass.valueField: SootField
        get() = getField("value", CharType.v().arrayType)

    private val SootClass.countField: SootField
        get() = getField("count", IntType.v())
}

val utStringBuilderClass: SootClass
    get() = Scene.v().getSootClass(UtStringBuilder::class.qualifiedName)

class UtStringBuilderWrapper : UtAbstractStringBuilderWrapper(utStringBuilderClass.name)

val utStringBufferClass: SootClass
    get() = Scene.v().getSootClass(UtStringBuffer::class.qualifiedName)

class UtStringBufferWrapper : UtAbstractStringBuilderWrapper(utStringBufferClass.name)


private val STRING_INTERNAL = ChunkId(java.lang.String::class.qualifiedName!!, "internal")

private val NATIVE_STRING_VALUE = ChunkId(UtNativeString::class.qualifiedName!!, "value")
internal val STRING_LENGTH
    get() = utStringClass.getField("length", IntType.v())
internal val STRING_VALUE
    get() = utStringClass.getField("value", CharType.v().arrayType)

/**
 * Map to support internal string representation, addr -> String
 */
internal val STRING_INTERNAL_DESCRIPTOR: MemoryChunkDescriptor
    get() = memoryChunkDescriptor(STRING_INTERNAL, STRING_TYPE, SeqType)


internal val NATIVE_STRING_VALUE_DESCRIPTOR: MemoryChunkDescriptor
    get() = memoryChunkDescriptor(NATIVE_STRING_VALUE, utNativeStringClass.type, SeqType)

/**
 * Returns internal string representation by String object address, addr -> String
 */
fun Memory.nativeStringValue(addr: UtAddrExpression) =
    PrimitiveValue(SeqType, findArray(NATIVE_STRING_VALUE_DESCRIPTOR).select(addr)).expr

private const val STRING_INTERN_MAP_LABEL = "java.lang.String_intern_map"

/**
 * Map to support string internation process, String -> addr
 */
internal val STRING_INTERN_MAP = mkArrayConst(STRING_INTERN_MAP_LABEL, UtSeqSort, UtAddrSort)

/**
 * Returns interned string, using map String -> addr
 *
 * Note: working with this map requires additional assert on internal string maps
 *
 * @see NATIVE_STRING_VALUE_DESCRIPTOR
 */
fun internString(string: UtExpression): UtAddrExpression = UtAddrExpression(STRING_INTERN_MAP.select(string))