package org.utbot.engine

import org.utbot.framework.plugin.api.*

/**
 * Generates mock with address provided.
 *
 * Sometimes we know mock information but don't know address yet. Or vice versa.
 */
class UtMockInfoGenerator(private val generator: (UtAddrExpression) -> UtMockInfo) {
    fun generate(mockAddr: UtAddrExpression) = generator(mockAddr)
}

/**
 * Information about mock instance.
 *
 * Mocks could be:
 * - static/non-static field,
 * - method under test parameter,
 * - object returned by another mock' method call,
 * - mock created by "new" instruction.
 *
 * Contains mock class id and mock address to work with object cache.
 *
 * Note: addr for static method mocks contains addr of the "host" object
 * received by [Traverser.locateStaticObject].
 *
 * @property classId classId of the object this mock represents.
 * @property addr address of the mock object.
 */
sealed class UtMockInfo(
    open val classId: ClassId,
    open val addr: UtAddrExpression
) {
    fun copyWithClassId(classId: ClassId = this.classId): UtMockInfo = when (this) {
        is UtFieldMockInfo -> this.copy(classId, addr)
        is UtNewInstanceMockInfo -> this.copy(classId, addr)
        is UtObjectMockInfo -> this.copy(classId, addr)
        is UtStaticMethodMockInfo -> error("Unsupported operation")
        is UtStaticObjectMockInfo -> this.copy(classId, addr)
    }
}

/**
 * Static and non-static field mock.
 * Contains field id and owner object address (null for static).
 *
 * @property fieldId fieldId of the field this MockInfo represents.
 * @property ownerAddr address of the object containing this field. Null if the field is static.
 */
data class UtFieldMockInfo(
    override val classId: ClassId,
    override val addr: UtAddrExpression,
    val fieldId: FieldId,
    val ownerAddr: UtAddrExpression?
) : UtMockInfo(classId, addr)

/**
 * Mock object. Represents:
 * - method under test' parameter
 * - "mock as result", when mock returns object Engine decides to mock too
 */
data class UtObjectMockInfo(
    override val classId: ClassId,
    override val addr: UtAddrExpression
) : UtMockInfo(classId, addr)

/**
 * Mock for the "host" object for static methods and fields with [classId] declaringClass.
 * [addr] is a value received by [Traverser.locateStaticObject].
 *
 * @see Traverser.locateStaticObject
 */
data class UtStaticObjectMockInfo(
    override val classId: ClassId,
    override val addr: UtAddrExpression
) : UtMockInfo(classId, addr)

/**
 * Represents mocks created by "new" instruction.
 * Contains call site (in which class creation takes place).
 *
 * Note: call site required by mock framework to know which classes to instrument.
 */
data class UtNewInstanceMockInfo(
    override val classId: ClassId,
    override val addr: UtAddrExpression,
    val callSite: ClassId
) : UtMockInfo(classId, addr)

/**
 * Represents mocks for static methods.
 * Contains the methodId.
 *
 * Used only in [Traverser.mockStaticMethod] method to pass information into [Mocker] about the method.
 * All the executables will be stored in executables of the object with [UtStaticObjectMockInfo] and the same addr.
 *
 * Note: we use non null addr here because of [createMockObject] method. We have to know address of the object
 * that we want to make. Although static method doesn't have "caller", we still can use address of the object
 * received by [Traverser.locateStaticObject].
 */
data class UtStaticMethodMockInfo(
    override val addr: UtAddrExpression,
    val methodId: MethodId
) : UtMockInfo(methodId.classId, addr)

/**
 * Wrapper to add order by [id] for elements of the [MockInfoEnriched.executables].
 *
 * It is important in situations like:
 *
 *     void foo(A fst, A snd) {
 *         int a = fst.generateInt();
 *         int b = snd.generateInt();
 *         if (a + b > 10) {
 *             doSomething()
 *         }
 *     }
 *
 * If 'fst' and 'snd' have the same address, we should merge their executables into one list. To set order for the
 * elements we add unique id corresponded to the time of the call.
 */
data class MockExecutableInstance<Type>(val id: Int, val value: SymbolicValue<Type>)

data class MockInfoEnriched<Type>(
    val mockInfo: UtMockInfo,
    val executables: Map<ExecutableId, List<MockExecutableInstance<Type>>> = emptyMap()
)

private const val MAKE_SYMBOLIC_NAME = "makeSymbolic"
private const val ASSUME_NAME = "assume"
private const val ASSUME_OR_EXECUTE_CONCRETELY_NAME = "assumeOrExecuteConcretely"
