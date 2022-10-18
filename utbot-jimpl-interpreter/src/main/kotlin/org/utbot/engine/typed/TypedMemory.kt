package org.utbot.engine.typed

import kotlinx.collections.immutable.*
import org.utbot.engine.*
import org.utbot.framework.plugin.api.ClassId
import org.utbot.framework.plugin.api.FieldId
import org.utbot.framework.plugin.api.util.allDeclaredFieldIds
import soot.ArrayType
import soot.RefLikeType
import soot.Type

class TypedMemory(
    initial: PersistentMap<ChunkId, UtArrayExpressionBase> = persistentHashMapOf(),
    current: PersistentMap<ChunkId, UtArrayExpressionBase> = persistentHashMapOf(),
    staticInitial: PersistentMap<FieldId, PersistentMap<ChunkId, UtArrayExpressionBase>> = persistentHashMapOf(),
    concrete: PersistentMap<UtAddrExpression, Concrete> = persistentHashMapOf(),
    staticInstanceStorage: PersistentMap<ClassId, ObjectValue> = persistentHashMapOf(),
    initializedStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
    staticFieldsStates: PersistentMap<FieldId, FieldStates> = persistentHashMapOf(),
    meaningfulStaticFields: PersistentSet<FieldId> = persistentHashSetOf(),
    updates: MemoryUpdate = MemoryUpdate(),
    visitedValues: UtArrayExpressionBase = UtConstArrayExpression(mkInt(0), UtArraySort(UtAddrSort, UtIntSort)),
    touchedAddresses: UtArrayExpressionBase = UtConstArrayExpression(UtFalse, UtArraySort(UtAddrSort, UtBoolSort)),
    instanceFieldReadOperations: PersistentSet<InstanceFieldReadOperation> = persistentHashSetOf(),
    speculativelyNotNullAddresses: UtArrayExpressionBase = UtConstArrayExpression(UtFalse, UtArraySort(UtAddrSort, UtBoolSort)),
    symbolicEnumValues: PersistentList<ObjectValue> = persistentListOf(),
    // Typed fields
    private val addrToMockInfo: PersistentMap<UtAddrExpression, UtMockInfo> = persistentHashMapOf(),
    private val mockInfos: PersistentList<MockInfoEnriched> = persistentListOf(),
    private val addrToArrayType: PersistentMap<UtAddrExpression, ArrayType> = persistentHashMapOf()
)  : Memory(initial, current, staticInitial, concrete, staticInstanceStorage,
        initializedStaticFields, staticFieldsStates, meaningfulStaticFields,
        updates, visitedValues, touchedAddresses, instanceFieldReadOperations,
        speculativelyNotNullAddresses, symbolicEnumValues) {

    fun mockInfoByAddr(addr: UtAddrExpression): UtMockInfo? = addrToMockInfo[addr]

    fun mocks(): List<MockInfoEnriched> = mockInfos

    override fun copy(
            initial: PersistentMap<ChunkId, UtArrayExpressionBase>,
            current: PersistentMap<ChunkId, UtArrayExpressionBase>,
            staticInitial: PersistentMap<FieldId, PersistentMap<ChunkId, UtArrayExpressionBase>>,
            concrete: PersistentMap<UtAddrExpression, Concrete>,
            staticInstanceStorage: PersistentMap<ClassId, ObjectValue>,
            initializedStaticFields: PersistentSet<FieldId>,
            staticFieldsStates: PersistentMap<FieldId, FieldStates>,
            meaningfulStaticFields: PersistentSet<FieldId>,
            updates: MemoryUpdate,
            visitedValues: UtArrayExpressionBase,
            touchedAddresses: UtArrayExpressionBase,
            instanceFieldReadOperations: PersistentSet<InstanceFieldReadOperation>,
            speculativelyNotNullAddresses: UtArrayExpressionBase,
            symbolicEnumValues: PersistentList<ObjectValue>) =
        TypedMemory(initial, current, staticInitial, concrete, staticInstanceStorage, initializedStaticFields,
            staticFieldsStates, meaningfulStaticFields, updates, visitedValues, touchedAddresses,
            instanceFieldReadOperations, speculativelyNotNullAddresses, symbolicEnumValues,
            addrToMockInfo, mockInfos, addrToArrayType)

    fun update(update: TypedMemoryUpdate): Memory {
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
}

class TypedMemoryUpdate(
    stores: PersistentList<UtNamedStore> = persistentListOf(),
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
    symbolicEnumValues: PersistentList<ObjectValue> = persistentListOf(),
    val mockInfos: PersistentList<MockInfoEnriched> = persistentListOf(),
    val addrToArrayType: PersistentMap<UtAddrExpression, ArrayType> = persistentHashMapOf(),
    val addrToMockInfo: PersistentMap<UtAddrExpression, UtMockInfo> = persistentHashMapOf(),
) : MemoryUpdate(stores, touchedChunkDescriptors, concrete, staticInstanceStorage, initializedStaticFields,
        staticFieldsUpdates, meaningfulStaticFields, visitedValues, touchedAddresses, classIdToClearStatics,
        instanceFieldReads, speculativelyNotNullAddresses, symbolicEnumValues)
{
    operator fun plus(other: TypedMemoryUpdate) =
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
            mockInfos = mockInfos.mergeWithUpdate(other.mockInfos),
            addrToArrayType = addrToArrayType.putAll(other.addrToArrayType),
            addrToMockInfo = addrToMockInfo.putAll(other.addrToMockInfo),
        )

    fun copy(stores: PersistentList<UtNamedStore> = persistentListOf(),
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
             symbolicEnumValues: PersistentList<ObjectValue> = persistentListOf(),
             mockInfos: PersistentList<MockInfoEnriched> = persistentListOf(),
             addrToArrayType: PersistentMap<UtAddrExpression, ArrayType> = persistentHashMapOf(),
             addrToMockInfo: PersistentMap<UtAddrExpression, UtMockInfo> = persistentHashMapOf()) =
        TypedMemoryUpdate(stores, touchedChunkDescriptors, concrete, staticInstanceStorage,
            initializedStaticFields, staticFieldsUpdates, meaningfulStaticFields, visitedValues, touchedAddresses,
            classIdToClearStatics, instanceFieldReads, speculativelyNotNullAddresses, symbolicEnumValues,
            mockInfos, addrToArrayType, addrToMockInfo)

    override fun copy(stores: PersistentList<UtNamedStore>,
                      touchedChunkDescriptors: PersistentSet<MemoryChunkDescriptor>,
                      concrete: PersistentMap<UtAddrExpression, Concrete>,
                      staticInstanceStorage: PersistentMap<ClassId, ObjectValue>,
                      initializedStaticFields: PersistentSet<FieldId>,
                      staticFieldsUpdates: PersistentList<StaticFieldMemoryUpdateInfo>,
                      meaningfulStaticFields: PersistentSet<FieldId>,
                      visitedValues: PersistentList<UtAddrExpression>,
                      touchedAddresses: PersistentList<UtAddrExpression>,
                      classIdToClearStatics: ClassId?,
                      instanceFieldReads: PersistentSet<InstanceFieldReadOperation>,
                      speculativelyNotNullAddresses: PersistentList<UtAddrExpression>,
                      symbolicEnumValues: PersistentList<ObjectValue>) =
        TypedMemoryUpdate(stores, touchedChunkDescriptors, concrete, staticInstanceStorage,
            initializedStaticFields, staticFieldsUpdates, meaningfulStaticFields, visitedValues, touchedAddresses,
            classIdToClearStatics, instanceFieldReads, speculativelyNotNullAddresses, symbolicEnumValues)
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
    val type: RefLikeType,
    val elementType: Type
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MemoryChunkDescriptor

        return id == other.id
    }

    override fun hashCode() = id.hashCode()
}

/**
 *
 */
private fun List<MockInfoEnriched>.mergeWithUpdate(other: List<MockInfoEnriched>): PersistentList<MockInfoEnriched> {
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
private operator fun MockInfoEnriched.plus(update: MockInfoEnriched?): MockInfoEnriched {
    if (update == null || update.executables.isEmpty()) return this

    require(mockInfo == update.mockInfo)

    return this.copy(executables = executables.toMutableMap().mergeValues(update.executables))
}

private fun <K, V> MutableMap<K, List<V>>.mergeValues(other: Map<K, List<V>>): Map<K, List<V>> = apply {
    other.forEach { (key, values) -> merge(key, values) { v1, v2 -> v1 + v2 } }
}
