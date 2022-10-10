package org.utbot.engine

import org.utbot.engine.MemoryState.CURRENT
import org.utbot.engine.MemoryState.INITIAL
import org.utbot.engine.MemoryState.STATIC_INITIAL
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
data class LocalVariableMemory<Type>(
    private val locals: PersistentMap<LocalVariable, SymbolicValue<Type>> = persistentHashMapOf()
) {
    fun memoryForNestedMethod(): LocalVariableMemory<Type> = this.copy(locals = persistentHashMapOf())

    fun update(update: LocalMemoryUpdate<Type>): LocalVariableMemory<Type> = this.copy(locals = locals.update(update.locals))

    /**
     * Returns local variable value.
     */
    fun local(variable: LocalVariable): SymbolicValue<Type>? = locals[variable]

    val localValues: Set<SymbolicValue<Type>>
        get() = locals.values.toSet()
}

/**
 * Class containing two states for [fieldId]: value of the first initialization and the last value of the [fieldId]
 */
data class FieldStates<Type>(val stateBefore: SymbolicValue<Type>, val stateAfter: SymbolicValue<Type>)

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
data class Memory<Type>( // TODO: split purely symbolic memory and information about symbolic variables mapping
    private val initial: PersistentMap<ChunkId, UtArrayExpressionBase> = persistentHashMapOf(),
    private val current: PersistentMap<ChunkId, UtArrayExpressionBase> = persistentHashMapOf(),
    private val staticInitial: PersistentMap<FieldId, PersistentMap<ChunkId, UtArrayExpressionBase>> = persistentHashMapOf(),
    private val concrete: PersistentMap<UtAddrExpression, Concrete> = persistentHashMapOf(),
    private val mockInfos: PersistentList<MockInfoEnriched<Type>> = persistentListOf(),
    private val staticInstanceStorage: PersistentMap<ClassId, ObjectValue<Type>> = persistentHashMapOf(),
    private val initializedStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
    private val staticFieldsStates: PersistentMap<FieldId, FieldStates<Type>> = persistentHashMapOf(),
    private val meaningfulStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
    private val addrToArrayType: PersistentMap<UtAddrExpression, Type> = persistentHashMapOf(),
    private val addrToMockInfo: PersistentMap<UtAddrExpression, UtMockInfo> = persistentHashMapOf(),
    // TODO: refactor this later. Now we use MemoryUpdate only for statics substitution
    private val updates: MemoryUpdate<Type> = MemoryUpdate(),
    private val visitedValues: UtArrayExpressionBase = UtConstArrayExpression(
        mkInt(0),
        UtArraySort(UtAddrSort, UtIntSort)
    ),
    private val touchedAddresses: UtArrayExpressionBase = UtConstArrayExpression(
        UtFalse,
        UtArraySort(UtAddrSort, UtBoolSort)
    ),
    private val instanceFieldReadOperations: PersistentSet<InstanceFieldReadOperation> = persistentHashSetOf(),

    /**
     * Storage for addresses that we speculatively consider non-nullable (e.g., final fields of system classes).
     * See [org.utbot.engine.UtBotSymbolicEngine.createFieldOrMock] for usage,
     * and [docs/SpeculativeFieldNonNullability.md] for details.
     */
    private val speculativelyNotNullAddresses: UtArrayExpressionBase = UtConstArrayExpression(
        UtFalse,
        UtArraySort(UtAddrSort, UtBoolSort)
    ),
    private val symbolicEnumValues: PersistentList<ObjectValue<Type>> = persistentListOf()
) {
    val chunkIds: Set<ChunkId>
        get() = initial.keys

    fun mockInfoByAddr(addr: UtAddrExpression): UtMockInfo? = addrToMockInfo[addr]

    fun mocks(): List<MockInfoEnriched<Type>> = mockInfos

    fun staticFields(): Map<FieldId, FieldStates<Type>> = staticFieldsStates.filterKeys { it in meaningfulStaticFields }

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

    fun update(update: MemoryUpdate<Type>): Memory<Type> {
        var updInitial = initial
        var updCurrent = current
        update.touchedChunkDescriptors
            .filterNot { it.id in updCurrent }
            .forEach { chunk ->
                val array = initialArray(chunk)
                updInitial = updInitial.put(chunk.id, array)
                updCurrent = updCurrent.put(chunk.id, array)
            }
        // TODO: collect updates for one array
        update.stores.forEach { (descriptor, index, value) ->
            val array = updCurrent[descriptor.id] ?: initialArray(descriptor)
            val nextArray = array.store(index, value)
            updCurrent = updCurrent.put(descriptor.id, nextArray)
        }

        val initialMemoryStates = mutableMapOf<FieldId, PersistentMap<ChunkId, UtArrayExpressionBase>>()

        // sometimes we might have several updates for the same fieldId. It means that we updated it in the nested
        // calls. In this case we should care only about the first and the last one value in the updates.
        val staticFieldUpdates = update.staticFieldsUpdates
            .groupBy { it.fieldId }
            .mapValues { (_, values) -> values.first().value to values.last().value }

        val previousMemoryStates = staticFieldsStates.toMutableMap()


        /**
         * sometimes we want to change initial memory states of fields of a certain class, so we erase all the
         * information about previous states and update it with the current state. For now, this processing only takes
         * place after receiving MethodResult from [STATIC_INITIALIZER] method call at the end of
         * [Traverser.processStaticInitializer]. The value of `update.classIdToClearStatics` is equal to the
         * class for which the static initialization has performed.
         * TODO: JIRA:1610 -- refactor working with statics later
         */
//        update.classIdToClearStatics?.let { classId ->
//            Scene.v().getSootClass(classId.name).fields.forEach { sootField ->
//                previousMemoryStates.remove(sootField.fieldId)
//            }
//        }
        update.classIdToClearStatics?.let { classId ->
            classId.allDeclaredFieldIds.forEach { fieldId ->
                previousMemoryStates.remove(fieldId)
            }
        }

        val updatedStaticFields = staticFieldUpdates
            .map { (fieldId, values) ->
                val (initialValue, currentValue) = values

                val previousValues = previousMemoryStates[fieldId]
                var updatedValue = previousValues?.copy(stateAfter = currentValue)

                if (updatedValue == null) {
                    require(fieldId !in initialMemoryStates)
                    initialMemoryStates[fieldId] = updCurrent
                    updatedValue = FieldStates(stateBefore = initialValue, stateAfter = currentValue)
                }

                fieldId to updatedValue
            }
            .toMap()

        val updVisitedValues = update.visitedValues.fold(visitedValues) { acc, addr ->
            acc.store(addr, mkInt(1))
        }

        val updTouchedAddresses = update.touchedAddresses.fold(touchedAddresses) { acc, addr ->
            acc.store(addr, UtTrue)
        }

        val updSpeculativelyNotNullAddresses = update.speculativelyNotNullAddresses.fold(speculativelyNotNullAddresses) { acc, addr ->
            acc.store(addr, UtTrue)
        }

        return this.copy(
            initial = updInitial,
            current = updCurrent,
            staticInitial = staticInitial.putAll(initialMemoryStates),
            concrete = concrete.putAll(update.concrete),
            mockInfos = mockInfos.mergeWithUpdate(update.mockInfos),
            staticInstanceStorage = staticInstanceStorage.putAll(update.staticInstanceStorage),
            initializedStaticFields = initializedStaticFields.addAll(update.initializedStaticFields),
            staticFieldsStates = previousMemoryStates.toPersistentMap().putAll(updatedStaticFields),
            meaningfulStaticFields = meaningfulStaticFields.addAll(update.meaningfulStaticFields),
            addrToArrayType = addrToArrayType.putAll(update.addrToArrayType),
            addrToMockInfo = addrToMockInfo.putAll(update.addrToMockInfo),
            updates = updates + update,
            visitedValues = updVisitedValues,
            touchedAddresses = updTouchedAddresses,
            instanceFieldReadOperations = instanceFieldReadOperations.addAll(update.instanceFieldReads),
            speculativelyNotNullAddresses = updSpeculativelyNotNullAddresses,
            symbolicEnumValues = symbolicEnumValues.addAll(update.symbolicEnumValues)
        )
    }

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

    /**
     * Returns copy of memory without local variables and updates.
     * Execution can continue to collect updates for particular piece of code.
     */
    fun memoryForNestedMethod(): Memory<Type> =
        this.copy(updates = MemoryUpdate())

    /**
     * Returns copy of queued [updates] which consists only of updates of static fields.
     * This is necessary for substituting unbounded symbolic variables into the static fields.
     */
    fun queuedStaticMemoryUpdates(): MemoryUpdate<Type> =
        MemoryUpdate(
            staticInstanceStorage = updates.staticInstanceStorage,
            staticFieldsUpdates = updates.staticFieldsUpdates
        )

    /**
     * Creates UtArraySelect for array length with particular array address. Addresses are unique for all objects.
     * No need to track updates on arraysLength array, cause we use selects only with unique ids.
     */
    fun findArrayLengthExpression(addr: UtAddrExpression) = arraysLength.select(addr)

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

    fun findStaticInstanceOrNull(id: ClassId): ObjectValue<Type>? = staticInstanceStorage[id]

    fun findTypeForArrayOrNull(addr: UtAddrExpression): Type? = addrToArrayType[addr]

    fun getSymbolicEnumValues(classId: ClassId): List<ObjectValue<Type>> =
        symbolicEnumValues.filter { it.type.classId == classId }
}

private fun initialArray(descriptor: MemoryChunkDescriptor) =
    mkArrayConst(descriptor.id.toId(), UtAddrSort, descriptor.itemSort)

enum class MemoryState { INITIAL, STATIC_INITIAL, CURRENT }

data class LocalMemoryUpdate<Type>(
    val locals: PersistentMap<LocalVariable, SymbolicValue<Type>?> = persistentHashMapOf(),
) {
    operator fun plus(other: LocalMemoryUpdate<Type>) =
        this.copy(
            locals = locals.putAll(other.locals),
        )
}

/**
 * Class containing information for memory update of the static field.
 */
data class StaticFieldMemoryUpdateInfo<Type>(
    val fieldId: FieldId,
    val value: SymbolicValue<Type>
)

data class MemoryUpdate<Type>(
    val stores: PersistentList<UtNamedStore> = persistentListOf(),
    val touchedChunkDescriptors: PersistentSet<MemoryChunkDescriptor> = persistentSetOf(),
    val concrete: PersistentMap<UtAddrExpression, Concrete> = persistentHashMapOf(),
    val mockInfos: PersistentList<MockInfoEnriched<Type>> = persistentListOf(),
    val staticInstanceStorage: PersistentMap<ClassId, ObjectValue<Type>> = persistentHashMapOf(),
    val initializedStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
    val staticFieldsUpdates: PersistentList<StaticFieldMemoryUpdateInfo<Type>> = persistentListOf(),
    val meaningfulStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
    val addrToArrayType: PersistentMap<UtAddrExpression, Type> = persistentHashMapOf(),
    val addrToMockInfo: PersistentMap<UtAddrExpression, UtMockInfo> = persistentHashMapOf(),
    val visitedValues: PersistentList<UtAddrExpression> = persistentListOf(),
    val touchedAddresses: PersistentList<UtAddrExpression> = persistentListOf(),
    val classIdToClearStatics: ClassId? = null,
    val instanceFieldReads: PersistentSet<InstanceFieldReadOperation> = persistentHashSetOf(),
    val speculativelyNotNullAddresses: PersistentList<UtAddrExpression> = persistentListOf(),
    val symbolicEnumValues: PersistentList<ObjectValue<Type>> = persistentListOf()
) {
    operator fun plus(other: MemoryUpdate<Type>) =
        this.copy(
            stores = stores.addAll(other.stores),
            touchedChunkDescriptors = touchedChunkDescriptors.addAll(other.touchedChunkDescriptors),
            concrete = concrete.putAll(other.concrete),
            mockInfos = mockInfos.mergeWithUpdate(other.mockInfos),
            staticInstanceStorage = staticInstanceStorage.putAll(other.staticInstanceStorage),
            initializedStaticFields = initializedStaticFields.addAll(other.initializedStaticFields),
            staticFieldsUpdates = staticFieldsUpdates.addAll(other.staticFieldsUpdates),
            meaningfulStaticFields = meaningfulStaticFields.addAll(other.meaningfulStaticFields),
            addrToArrayType = addrToArrayType.putAll(other.addrToArrayType),
            addrToMockInfo = addrToMockInfo.putAll(other.addrToMockInfo),
            visitedValues = visitedValues.addAll(other.visitedValues),
            touchedAddresses = touchedAddresses.addAll(other.touchedAddresses),
            classIdToClearStatics = other.classIdToClearStatics,
            instanceFieldReads = instanceFieldReads.addAll(other.instanceFieldReads),
            speculativelyNotNullAddresses = speculativelyNotNullAddresses.addAll(other.speculativelyNotNullAddresses),
            symbolicEnumValues = symbolicEnumValues.addAll(other.symbolicEnumValues),
        )

    fun getSymbolicEnumValues(classId: ClassId): List<ObjectValue<Type>> =
        symbolicEnumValues.filter { it.type.classId == classId }
}

// array - Java Array
// chunk - Memory Model (convenient for Solver)
//       - solver's (get-model) results to parse

/**
 * In current implementation it references
 * to SMT solver's array used for storage of some
 *
 * [id] is typically corresponds to Solver's array name
 * //TODO docs for 3 cases: array of primitives, array of objects, object's fields (including static)
 */
data class MemoryChunkDescriptor(
    val id: ChunkId,
    val itemSort: UtSort
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MemoryChunkDescriptor

        return id == other.id
    }

    override fun hashCode() = id.hashCode()
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

fun <Type> localMemoryUpdate(vararg updates: Pair<LocalVariable, SymbolicValue<Type>?>) =
    LocalMemoryUpdate(
        locals = persistentHashMapOf(*updates)
    )

/**
 *
 */
private fun <Type> List<MockInfoEnriched<Type>>.mergeWithUpdate(other: List<MockInfoEnriched<Type>>): PersistentList<MockInfoEnriched<Type>> {
    // map from MockInfo to MockInfoEnriched
    val updates = other.associateByTo(mutableMapOf()) { it.mockInfo }

    // create list from original values, then merge executables from update
    val mergeResult = this.mapTo(mutableListOf()) { original ->
        original + updates.remove(original.mockInfo)
    }

    // add tail: values from updates for which their MockInfo didn't present in [this] yet.
    mergeResult += updates.values

    return mergeResult.toPersistentList()
}

/**
 * Copies executables from [update] to the executables of [this] object.
 */
private operator fun <Type> MockInfoEnriched<Type>.plus(update: MockInfoEnriched<Type>?): MockInfoEnriched<Type> {
    if (update == null || update.executables.isEmpty()) return this

    require(mockInfo == update.mockInfo)

    return this.copy(executables = executables.toMutableMap().mergeValues(update.executables))
}

private fun <K, V> MutableMap<K, List<V>>.mergeValues(other: Map<K, List<V>>): Map<K, List<V>> = apply {
    other.forEach { (key, values) -> merge(key, values) { v1, v2 -> v1 + v2 } }
}
