package org.utbot.engine

import org.utbot.engine.MemoryState.CURRENT
import org.utbot.engine.MemoryState.INITIAL
import org.utbot.engine.MemoryState.STATIC_INITIAL
import org.utbot.engine.overrides.strings.UtNativeString
import org.utbot.framework.plugin.api.ClassId
import org.utbot.framework.plugin.api.FieldId
import kotlinx.collections.immutable.ImmutableCollection
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import org.utbot.framework.plugin.api.classId
import org.utbot.framework.plugin.api.util.allDeclaredFieldIds


/**
 * Represents a memory associated with a certain method call. For now consists only of local variables mapping.
 * TODO: think on other fields later
 *
 * @param [locals] represents a mapping from [LocalVariable]s of a specific method call to [SymbolicValue]s.
 */
data class LocalVariableMemory(
    private val locals: PersistentMap<LocalVariable, SymbolicValue> = persistentHashMapOf()
) {
    fun memoryForNestedMethod(): LocalVariableMemory = this.copy(locals = persistentHashMapOf())

    fun update(update: LocalMemoryUpdate): LocalVariableMemory = this.copy(locals = locals.update(update.locals))

    /**
     * Returns local variable value.
     */
    fun local(variable: LocalVariable): SymbolicValue? = locals[variable]

    val localValues: Set<SymbolicValue>
        get() = locals.values.toSet()
}

/**
 * Class containing two states for [fieldId]: value of the first initialization and the last value of the [fieldId]
 */
data class FieldStates(val stateBefore: SymbolicValue, val stateAfter: SymbolicValue)

/**
 * A class that represents instance field read operations.
 *
 * Tracking read accesses is necessary to check whether the specific field of a parameter object
 * should be initialized. We don't need to initialize fields that are not accessed in the method being tested.
 */
data class InstanceFieldReadOperation(val addr: UtAddrExpression, val fieldId: FieldId)

/**
 * Local memory implementation based on arrays.
 *
 * Contains initial and current versions of arrays. Also collects memory updates (stores) and can return them.
 * Updates can be reset to collect them for particular piece of code.
 *
 * [touchedAddresses] is a field used to determine whether some address has been touched during the analysis or not.
 * It is important during the resolving in [Resolver.constructTypeOrNull]. At the beginning it contains only false
 * values, therefore at the end of the execution true will be only in cells corresponding to the touched addresses.
 *
 * Fields used for statics:
 * * [staticFieldsStates] is a map containing initial and current state for every touched static field;
 * * [meaningfulStaticFields] is a set containing id for the field that has been touched outside <clinit> blocks.
 *
 * Note: [staticInitial] contains mapping from [FieldId] to the memory state at the moment of the field initialization.
 *
 * @see memoryForNestedMethod
 * @see FieldStates
 */
open class Memory( // TODO: split purely symbolic memory and information about symbolic variables mapping
    protected val initial: PersistentMap<ChunkId, UtArrayExpressionBase> = persistentHashMapOf(),
    protected val current: PersistentMap<ChunkId, UtArrayExpressionBase> = persistentHashMapOf(),
    protected val staticInitial: PersistentMap<FieldId, PersistentMap<ChunkId, UtArrayExpressionBase>> = persistentHashMapOf(),
    protected val concrete: PersistentMap<UtAddrExpression, Concrete> = persistentHashMapOf(),
    protected val staticInstanceStorage: PersistentMap<ClassId, ObjectValue> = persistentHashMapOf(),
    protected val initializedStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
    protected val staticFieldsStates: PersistentMap<FieldId, FieldStates> = persistentHashMapOf(),
    protected val meaningfulStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
    protected val updates: MemoryUpdate = MemoryUpdate(), // TODO: refactor this later. Now we use it only for statics substitution
    protected val visitedValues: UtArrayExpressionBase = UtConstArrayExpression(
        mkInt(0),
        UtArraySort(UtAddrSort, UtIntSort)
    ),
    protected val touchedAddresses: UtArrayExpressionBase = UtConstArrayExpression(
        UtFalse,
        UtArraySort(UtAddrSort, UtBoolSort)
    ),
    protected val instanceFieldReadOperations: PersistentSet<InstanceFieldReadOperation> = persistentHashSetOf(),

    /**
     * Storage for addresses that we speculatively consider non-nullable (e.g., final fields of system classes).
     * See [org.utbot.engine.UtBotSymbolicEngine.createFieldOrMock] for usage,
     * and [docs/SpeculativeFieldNonNullability.md] for details.
     */
    protected val speculativelyNotNullAddresses: UtArrayExpressionBase = UtConstArrayExpression(
        UtFalse,
        UtArraySort(UtAddrSort, UtBoolSort)
    ),
    protected val symbolicEnumValues: PersistentList<ObjectValue> = persistentListOf()
) {
    val chunkIds: Set<ChunkId>
        get() = initial.keys

    fun staticFields(): Map<FieldId, FieldStates> = staticFieldsStates.filterKeys { it in meaningfulStaticFields }

    /**
     * Construct the mapping from addresses to sets of fields whose values are read during the code execution
     * and therefore should be initialized in a constructed model.
     *
     * @param transformAddress the function to transform the symbolic object addresses (e.g., to translate
     * symbolic addresses into concrete addresses).
     */
    fun <TAddress> initializedFields(transformAddress: (UtAddrExpression) -> TAddress): Map<TAddress, Set<FieldId>> =
        instanceFieldReadOperations
            .groupBy { transformAddress(it.addr) }
            .mapValues { it.value.map { read -> read.fieldId }.toSet() }

    fun isVisited(addr: UtAddrExpression): UtArraySelectExpression = visitedValues.select(addr)

    /**
     * Returns symbolic information about whether [addr] has been touched during the analysis or not.
     */
    fun isTouched(addr: UtAddrExpression): UtArraySelectExpression = touchedAddresses.select(addr)

    /**
     * Returns symbolic information about whether [addr] corresponds to a final field known to be not null.
     */
    fun isSpeculativelyNotNull(addr: UtAddrExpression): UtArraySelectExpression = speculativelyNotNullAddresses.select(addr)

    /**
     * @return ImmutableCollection of the initial values for all the arrays we touched during the execution
     */
    val initialArrays: ImmutableCollection<UtArrayExpressionBase>
        get() = initial.values

    /**
     * Finds the array by given [chunkDescriptor] and [state]. In case when [state] is [MemoryState.STATIC_INITIAL]
     * [staticFieldId] must be not null, because it means that we want to get arrays existed in the initial moment
     * for specified field (the moment of its initialization).
     *
     * Note: do not use this method directly during resolving results, use [Resolver.findArray] instead.
     * Otherwise, might occur a situation when we try to find array in [STATIC_INITIAL] state without
     * specified [staticFieldId] (i.e., during the wrappers resolving).
     *
     * @see Resolver.findArray
     */
    fun findArray(
        chunkDescriptor: MemoryChunkDescriptor,
        state: MemoryState = CURRENT,
        staticFieldId: FieldId? = null
    ): UtArrayExpressionBase =
        when (state) {
            INITIAL -> initial[chunkDescriptor.id]
            CURRENT -> current[chunkDescriptor.id]
            STATIC_INITIAL -> staticInitial[staticFieldId!!]?.get(chunkDescriptor.id)
        } ?: initialArray(chunkDescriptor)

    open fun copy(initial: PersistentMap<ChunkId, UtArrayExpressionBase> = persistentHashMapOf(),
                  current: PersistentMap<ChunkId, UtArrayExpressionBase> = persistentHashMapOf(),
                  staticInitial: PersistentMap<FieldId, PersistentMap<ChunkId, UtArrayExpressionBase>> = persistentHashMapOf(),
                  concrete: PersistentMap<UtAddrExpression, Concrete> = persistentHashMapOf(),
                  staticInstanceStorage: PersistentMap<ClassId, ObjectValue> = persistentHashMapOf(),
                  initializedStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
                  staticFieldsStates: PersistentMap<FieldId, FieldStates> = persistentHashMapOf(),
                  meaningfulStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
                  updates: MemoryUpdate = MemoryUpdate(),
                  visitedValues: UtArrayExpressionBase = UtConstArrayExpression(
                      mkInt(0),
                      UtArraySort(UtAddrSort, UtIntSort)
                  ),
                  touchedAddresses: UtArrayExpressionBase = UtConstArrayExpression(
                      UtFalse,
                      UtArraySort(UtAddrSort, UtBoolSort)
                  ),
                  instanceFieldReadOperations: PersistentSet<InstanceFieldReadOperation> = persistentHashSetOf(),
                  speculativelyNotNullAddresses: UtArrayExpressionBase = UtConstArrayExpression(
                      UtFalse,
                      UtArraySort(UtAddrSort, UtBoolSort)
                  ),
                  symbolicEnumValues: PersistentList<ObjectValue> = persistentListOf()) =
        Memory(initial, current, staticInitial, concrete, staticInstanceStorage, initializedStaticFields,
               staticFieldsStates, meaningfulStaticFields, updates, visitedValues, touchedAddresses,
               instanceFieldReadOperations, speculativelyNotNullAddresses, symbolicEnumValues)

    /**
     * Returns copy of memory without local variables and updates.
     * Execution can continue to collect updates for particular piece of code.
     */
    fun memoryForNestedMethod(): Memory = this.copy(updates = MemoryUpdate())

    /**
     * Returns copy of queued [updates] which consists only of updates of static fields.
     * This is necessary for substituting unbounded symbolic variables into the static fields.
     */
    fun queuedStaticMemoryUpdates(): MemoryUpdate = MemoryUpdate(
        staticInstanceStorage = updates.staticInstanceStorage,
        staticFieldsUpdates = updates.staticFieldsUpdates
    )

    /**
     * Creates UtArraySelect for array length with particular array address. Addresses are unique for all objects.
     * No need to track updates on arraysLength array, cause we use selects only with unique ids.
     */
    fun findArrayLength(addr: UtAddrExpression) = arraysLength.select(addr).toIntValue()

    private val arraysLength: UtMkArrayExpression by lazy {
        mkArrayConst("arraysLength", UtAddrSort, UtInt32Sort)
    }

    /**
     * Makes the lengths for all the arrays in the program equal to zero by default
     */
    fun softZeroArraysLengths() = UtMkTermArrayExpression(arraysLength)

    /**
     * Returns concrete value for address.
     *
     * Note: for initial state returns null.
     */
    fun takeConcrete(addr: UtAddrExpression, state: MemoryState): Concrete? =
        when (state) {
            INITIAL, STATIC_INITIAL -> null // no values in the beginning
            CURRENT -> concrete[addr]
        }

    fun isInitialized(id: ClassId): Boolean = id in staticInstanceStorage

    fun isInitialized(fieldId: FieldId): Boolean = fieldId in initializedStaticFields

    fun findStaticInstanceOrNull(id: ClassId): ObjectValue? = staticInstanceStorage[id]

    fun findTypeForArrayOrNull(addr: UtAddrExpression): ArrayType? = addrToArrayType[addr]

    fun getSymbolicEnumValues(classId: ClassId): List<ObjectValue> =
        symbolicEnumValues.filter { it.type.classId == classId }
}

private fun initialArray(descriptor: MemoryChunkDescriptor) =
    mkArrayConst(descriptor.id.toId(), UtAddrSort, descriptor.itemSort())

/**
 * Creates item sort for memory chunk descriptor.
 *
 * If the given type is ArrayType, i.e. int[][], we have to create sort for it.
 * Otherwise, there is two options: either type is a RefType and it's elementType is Array, so the corresponding
 * array stores addresses, or it stores primitive values themselves.
 */
private fun MemoryChunkDescriptor.itemSort(): UtSort = when (type) {
    is ArrayType -> type.toSort()
    else -> if (elementType is ArrayType) UtAddrSort else elementType.toSort()
}

enum class MemoryState { INITIAL, STATIC_INITIAL, CURRENT }

data class LocalMemoryUpdate(
    val locals: PersistentMap<LocalVariable, SymbolicValue?> = persistentHashMapOf(),
) {
    operator fun plus(other: LocalMemoryUpdate) =
        this.copy(
            locals = locals.putAll(other.locals),
        )
}

/**
 * Class containing information for memory update of the static field.
 */
data class StaticFieldMemoryUpdateInfo(
    val fieldId: FieldId,
    val value: SymbolicValue
)

open class MemoryUpdate(
    val stores: PersistentList<UtNamedStore> = persistentListOf(),
    val touchedChunkDescriptors: PersistentSet<MemoryChunkDescriptor> = persistentSetOf(),
    val concrete: PersistentMap<UtAddrExpression, Concrete> = persistentHashMapOf(),
    val staticInstanceStorage: PersistentMap<ClassId, ObjectValue> = persistentHashMapOf(),
    val initializedStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
    val staticFieldsUpdates: PersistentList<StaticFieldMemoryUpdateInfo> = persistentListOf(),
    val meaningfulStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
    val visitedValues: PersistentList<UtAddrExpression> = persistentListOf(),
    val touchedAddresses: PersistentList<UtAddrExpression> = persistentListOf(),
    val classIdToClearStatics: ClassId? = null,
    val instanceFieldReads: PersistentSet<InstanceFieldReadOperation> = persistentHashSetOf(),
    val speculativelyNotNullAddresses: PersistentList<UtAddrExpression> = persistentListOf(),
    val symbolicEnumValues: PersistentList<ObjectValue> = persistentListOf()
) {
    operator fun plus(other: MemoryUpdate) =
        this.copy(
            stores = stores.addAll(other.stores),
            touchedChunkDescriptors = touchedChunkDescriptors.addAll(other.touchedChunkDescriptors),
            concrete = concrete.putAll(other.concrete),
            staticInstanceStorage = staticInstanceStorage.putAll(other.staticInstanceStorage),
            initializedStaticFields = initializedStaticFields.addAll(other.initializedStaticFields),
            staticFieldsUpdates = staticFieldsUpdates.addAll(other.staticFieldsUpdates),
            meaningfulStaticFields = meaningfulStaticFields.addAll(other.meaningfulStaticFields),
            visitedValues = visitedValues.addAll(other.visitedValues),
            touchedAddresses = touchedAddresses.addAll(other.touchedAddresses),
            classIdToClearStatics = other.classIdToClearStatics,
            instanceFieldReads = instanceFieldReads.addAll(other.instanceFieldReads),
            speculativelyNotNullAddresses = speculativelyNotNullAddresses.addAll(other.speculativelyNotNullAddresses),
            symbolicEnumValues = symbolicEnumValues.addAll(other.symbolicEnumValues),
        )

    fun getSymbolicEnumValues(classId: ClassId): List<ObjectValue> =
        symbolicEnumValues.filter { it.type.classId == classId }

    open fun copy(stores: PersistentList<UtNamedStore> = persistentListOf(),
             touchedChunkDescriptors: PersistentSet<MemoryChunkDescriptor> = persistentSetOf(),
             concrete: PersistentMap<UtAddrExpression, Concrete> = persistentHashMapOf(),
             staticInstanceStorage: PersistentMap<ClassId, ObjectValue> = persistentHashMapOf(),
             initializedStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
             staticFieldsUpdates: PersistentList<StaticFieldMemoryUpdateInfo> = persistentListOf(),
             meaningfulStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
             visitedValues: PersistentList<UtAddrExpression> = persistentListOf(),
             touchedAddresses: PersistentList<UtAddrExpression> = persistentListOf(),
             classIdToClearStatics: ClassId? = null,
             instanceFieldReads: PersistentSet<InstanceFieldReadOperation> = persistentHashSetOf(),
             speculativelyNotNullAddresses: PersistentList<UtAddrExpression> = persistentListOf(),
             symbolicEnumValues: PersistentList<ObjectValue> = persistentListOf()) =
        MemoryUpdate(stores, touchedChunkDescriptors, concrete, staticInstanceStorage,
            initializedStaticFields, staticFieldsUpdates, meaningfulStaticFields, visitedValues, touchedAddresses,
            classIdToClearStatics, instanceFieldReads, speculativelyNotNullAddresses, symbolicEnumValues)
}

data class ChunkId(val type: String, val field: String) {
    fun toId() = "${type}_$field"
}

data class LocalVariable(val name: String)

data class UtNamedStore(
    val chunkDescriptor: MemoryChunkDescriptor,
    val index: UtExpression,
    val value: UtExpression
)

/**
 * Create [UtNamedStore] with simplified [index] and [value] expressions.
 *
 * @see RewritingVisitor
 */
fun simplifiedNamedStore(
    chunkDescriptor: MemoryChunkDescriptor,
    index: UtExpression,
    value: UtExpression
) = RewritingVisitor().let { visitor -> UtNamedStore(chunkDescriptor, index.accept(visitor), value.accept(visitor)) }

/**
 * Updates persistent map where value = null in update means deletion of original key-value
 */
private fun <K, V> PersistentMap<K, V>.update(update: Map<K, V?>): PersistentMap<K, V> {
    if (update.isEmpty()) return this
    val deletions = mutableListOf<K>()
    val updates = mutableMapOf<K, V>()
    update.forEach { (name, value) ->
        if (value == null) {
            deletions.add(name)
        } else {
            updates[name] = value
        }
    }
    return this.mutate { map ->
        deletions.forEach { map.remove(it) }
        map.putAll(updates)
    }
}

fun localMemoryUpdate(vararg updates: Pair<LocalVariable, SymbolicValue?>) =
    LocalMemoryUpdate(
        locals = persistentHashMapOf(*updates)
    )

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
    get() = MemoryChunkDescriptor(STRING_INTERNAL, STRING_TYPE, SeqType)


internal val NATIVE_STRING_VALUE_DESCRIPTOR: MemoryChunkDescriptor
    get() = MemoryChunkDescriptor(NATIVE_STRING_VALUE, utNativeStringClass.type, SeqType)

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
