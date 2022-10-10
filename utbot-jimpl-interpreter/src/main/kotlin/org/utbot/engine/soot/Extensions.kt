package org.utbot.engine.soot

import com.google.common.collect.BiMap
import org.utbot.api.mock.UtMock
import org.utbot.engine.UtAddrExpression
import org.utbot.engine.UtBoolExpression
import org.utbot.engine.UtBvConst
import org.utbot.engine.UtExpression
import org.utbot.engine.UtSolverStatusKind
import org.utbot.engine.UtSolverStatusSAT
import org.utbot.engine.UtSolverStatusUNDEFINED
import org.utbot.engine.LocalVariable
import org.utbot.engine.UtSort
import org.utbot.engine.UtByteSort
import org.utbot.engine.UtCharSort
import org.utbot.engine.UtShortSort
import org.utbot.engine.UtIntSort
import org.utbot.engine.UtLongSort
import org.utbot.engine.UtFp32Sort
import org.utbot.engine.UtFp64Sort
import org.utbot.engine.UtBoolSort
import org.utbot.engine.UtAddrSort
import org.utbot.engine.UtArraySort
import org.utbot.engine.UtSeqSort
import org.utbot.engine.defaultValue
import org.utbot.framework.UtSettings.checkNpeInNestedMethods
import org.utbot.framework.UtSettings.checkNpeInNestedNotPrivateMethods
import org.utbot.framework.plugin.api.FieldId
import org.utbot.framework.plugin.api.id
import soot.ByteType
import soot.CharType
import soot.ShortType
import soot.IntType
import soot.LongType
import soot.FloatType
import soot.DoubleType
import soot.BooleanType
import soot.ArrayType
import soot.PrimType
import soot.RefLikeType
import soot.RefType
import soot.Scene
import soot.SootClass
import soot.SootClass.BODIES
import soot.SootField
import soot.SootMethod
import soot.Type
import soot.Value
import soot.jimple.Expr
import soot.jimple.InvokeExpr
import soot.jimple.JimpleBody
import soot.jimple.StaticFieldRef
import soot.jimple.Stmt
import soot.jimple.internal.JDynamicInvokeExpr
import soot.jimple.internal.JIdentityStmt
import soot.jimple.internal.JInterfaceInvokeExpr
import soot.jimple.internal.JInvokeStmt
import soot.jimple.internal.JSpecialInvokeExpr
import soot.jimple.internal.JStaticInvokeExpr
import soot.jimple.internal.JVirtualInvokeExpr
import soot.jimple.internal.JimpleLocal
import soot.tagkit.ArtificialEntityTag
import java.lang.reflect.ParameterizedType
import java.util.ArrayDeque
import java.util.Deque
import java.util.LinkedList
import java.util.Queue
import kotlinx.collections.immutable.persistentHashMapOf

val JIdentityStmt.lines: String
    get() = tags.joinToString { "$it" }

fun SootClass.staticInitializerOrNull(): SootMethod? = methods.singleOrNull { it.isStaticInitializer }

fun insideStaticInitializer(value: StaticFieldRef, method: SootMethod, declaringClass: SootClass) =
    method.isStaticInitializer && declaringClass == value.field.declaringClass


fun Expr.isInvokeExpr() = this is JDynamicInvokeExpr
        || this is JStaticInvokeExpr
        || this is JInterfaceInvokeExpr
        || this is JVirtualInvokeExpr
        || this is JSpecialInvokeExpr

val SootMethod.pureJavaSignature
    get() = bytecodeSignature.substringAfter(' ').dropLast(1)

fun InvokeExpr.retrieveMethod(): SootMethod {
    methodRef.declaringClass.adjustLevel(BODIES)
    return method
}

/**
 * Checks safely if the method has body.
 */
fun SootMethod.canRetrieveBody() =
    runCatching { retrieveActiveBody() }.isSuccess

/**
 * Retrieves Jimple body for method.
 *
 * Note: we cannot use [SootMethod.activeBody], it returns null for not loaded method.
 * To handle that, we use [SootMethod.retrieveActiveBody] which loads active body if it's not set.
 * [SootMethod.retrieveActiveBody] requires BODIES resolving level for class.
 */
fun SootMethod.jimpleBody(): JimpleBody {
    declaringClass.adjustLevel(BODIES)
    return retrieveActiveBody() as JimpleBody
}

fun SootClass.adjustLevel(level: Int) {
    if (resolvingLevel() < level) {
        setResolvingLevel(level)
    }
}

fun Type.makeArrayType(dimensions: Int) =
    generateSequence(this) { it.makeArrayType() }.elementAt(dimensions) as ArrayType

val SootMethod.isLibraryMethod
    get() = !declaringClass.isApplicationClass

fun SootClass.superClassOrNull(): SootClass? = if (hasSuperclass()) superclass else null

/**
 * Checks whether the type is representing java.lang.Object or not
 */
fun Type.isJavaLangObject() = this == OBJECT_TYPE

/**
 * Filters given collection with [isAppropriate] property as a predicate.
 *
 * @receiver Collection for filtering
 *
 * @return List of the given elements containing only primitive types, RefTypes that satisfy the predicate and
 * ArrayType with primitive baseType or RefType baseType satisfying the predicate
 * @see isAppropriate
 * @see isInappropriate
 *
 */
fun Collection<Type>.appropriateClasses() = filter { type ->
    val baseType = if (type is ArrayType) type.baseType else type
    if (baseType is RefType) baseType.sootClass.isAppropriate else baseType is PrimType
}

/**
 * Returns true if the class can be instantiated and false otherwise.
 * @see isInappropriate
 */
val SootClass.isAppropriate
    get() = !isInappropriate

/**
 * Returns true if the class is abstract, interface, local or if the class has UtClassMock annotation, false otherwise.
 */
val SootClass.isInappropriate
    get() = isAbstract || isInterface || isLocal || findMockAnnotationOrNull != null

private val isLocalRegex = ".*\\$\\d+[\\p{L}\\p{M}0-9][\\p{L}\\p{M}0-9]*".toRegex()

val SootClass.isLocal
    get() = name matches isLocalRegex

private val isAnonymousRegex = ".*\\$\\d+$".toRegex()

val SootClass.isAnonymous
    get() = name matches isAnonymousRegex

private val isLambdaRegex = ".*(\\$)lambda_.*".toRegex()

val SootClass.isLambda: Boolean
    get() = this.isArtificialEntity && this.name matches isLambdaRegex

val Type.numDimensions get() = if (this is ArrayType) numDimensions else 0

/**
 * Invocation. Can generate multiple targets.
 *
 * @see Traverser.virtualAndInterfaceInvoke
 */
data class Invocation(
    val instance: ReferenceValue?,
    val method: SootMethod,
    val parameters: List<SymbolicValue>,
    val generator: () -> List<InvocationTarget>
) {
    constructor(
        instance: ReferenceValue?,
        method: SootMethod,
        parameters: List<SymbolicValue>,
        target: InvocationTarget
    ) : this(instance, method, parameters, { listOf(target) })
}

/**
 * Invocation target. Contains constraints to be satisfied for this instance class (related to virtual invoke).
 *
 * @see Traverser.virtualAndInterfaceInvoke
 */
data class InvocationTarget(
    val instance: ReferenceValue?,
    val method: SootMethod,
    val constraints: Set<UtBoolExpression> = emptySet()
)

/**
 * Method invocation target. Contains method, method' implementation class and possible types (class and its subclasses).
 *
 * Used by Engine to clarify types in virtual/interface invocation.
 *
 * Note: possibleTypes can contain less than full set of class and its subclasses because of previously applied
 * class related instructions like cast or instanceof.
 */
data class MethodInvocationTarget(
    val method: SootMethod,
    val implementationClass: RefType,
    val possibleTypes: List<Type>
)

/**
 * Used in the [Traverser.findLibraryTargets] to substitute common types
 * like [Iterable] with the types that have corresponding wrappers.
 *
 * @see Traverser.findLibraryTargets
 * @see Traverser.findInvocationTargets
 */
val libraryTargets: Map<String, List<String>> = mapOf(
    Iterable::class.java.name to listOf(ArrayList::class.java.name, HashSet::class.java.name),
    Collection::class.java.name to listOf(ArrayList::class.java.name, HashSet::class.java.name),
    List::class.java.name to listOf(ArrayList::class.java.name),
    Set::class.java.name to listOf(HashSet::class.java.name),
    Map::class.java.name to listOf(HashMap::class.java.name),
    Queue::class.java.name to listOf(LinkedList::class.java.name, ArrayDeque::class.java.name),
    Deque::class.java.name to listOf(LinkedList::class.java.name, ArrayDeque::class.java.name)
)

/**
 * Extracts fqn for the class by its [signature].
 *
 * Example:
 * "Lmake/symbolic/ClassWithComplicatedMethods;" -> "make.symbolic.ClassWithComplicatedMethods"
 */
fun classBytecodeSignatureToClassNameOrNull(signature: String?) =
    signature
        ?.replace("/", ".")
        ?.replace("$", ".")
        ?.let { it.substring(1, it.lastIndex) }

val JimpleLocal.variable: LocalVariable
    get() = LocalVariable(this.name)

fun Type.toSort(): UtSort = when (this) {
    is ByteType     -> UtByteSort
    is CharType     -> UtCharSort
    is ShortType    -> UtShortSort
    is IntType      -> UtIntSort
    is LongType     -> UtLongSort

    is FloatType    -> UtFp32Sort
    is DoubleType   -> UtFp64Sort

    is BooleanType  -> UtBoolSort

    is SeqType      -> UtSeqSort

    is ArrayType -> if (numDimensions == 1) UtArraySort(UtAddrSort, elementType.toSort()) else UtArraySort(UtAddrSort, UtAddrSort)
    is RefType -> UtAddrSort

    else -> error("${this::class} sort is not implemented")
}

val Type.defaultSymValue: UtExpression
    get() = toSort().defaultValue

val SootField.fieldId: FieldId
    get() = FieldId(declaringClass.id, name)

/**
 * Returns true if the statement is an invocation of the [caller]'s <init> method, false otherwise
 */
fun Stmt.isConstructorCall(caller: Value): Boolean {
    val currentInvokeStmt = this as? JInvokeStmt ?: return false
    val currentInvokeExpr = currentInvokeStmt.invokeExpr as? JSpecialInvokeExpr ?: return false
    val currentMethodName = currentInvokeExpr.methodRef.name

    return currentMethodName == "<init>" && currentInvokeExpr.base == caller
}

fun arrayTypeUpdate(addr: UtAddrExpression, type: ArrayType) =
    MemoryUpdate(addrToArrayType = persistentHashMapOf(addr to type))

/**
 * Returns true if the sootField might be null, false otherwise.
 */
fun SootField.shouldBeNotNull(): Boolean {
    require(type is RefLikeType)

    return hasNotNullAnnotation()
}

/**
 * Returns true if the sootMethod might throw NPE and should be checked, false otherwise.
 */
fun SootMethod.checkForNPE(nestingLevel: Int): Boolean {
    require(nestingLevel > 0)

    if (nestingLevel == 1) return true

    if (!checkNpeInNestedMethods) return false
    if (!checkNpeInNestedNotPrivateMethods && !isPrivate) return false

    return true
}

val Type.baseType: Type
    get() = if (this is ArrayType) this.baseType else this

val java.lang.reflect.Type.rawType: java.lang.reflect.Type
    get() = if (this is ParameterizedType) rawType else this

/**
 * Returns true if the addr belongs to “this” value, false otherwise.
 */
val UtAddrExpression.isThisAddr: Boolean
    get() = (this.internal as? UtBvConst)?.name == "p_this"

fun<K, V> BiMap<K, V>.getByValue(value: V): K? = inverse()[value]

/**
 * Returns true if the SootClass marked by [ArtificialEntityTag], false otherwise.
 */
val SootClass.isArtificialEntity: Boolean
    get() = this.tags.any { it is ArtificialEntityTag }

val SootClass.isUtMock: Boolean
    get() = this == Scene.v().getSootClass(UtMock::class.qualifiedName)

val SootClass.isOverridden: Boolean
    get() = packageName.startsWith(UTBOT_OVERRIDE_PACKAGE_NAME)

val SootClass.isLibraryNonOverriddenClass: Boolean
    get() = isLibraryClass && !isOverridden

/**
 * Returns a state from the list that has [UtSolverStatusSAT] status.
 * Inside it calls UtSolver.check if required.
 *
 * [processStatesWithUnknownStatus] is responsible for solver checks for states
 * with unknown status. Note that this calculation might take a long time.
 */
fun MutableList<ExecutionState>.pollUntilSat(processStatesWithUnknownStatus: Boolean): ExecutionState? {
    while (!isEmpty()) {
        val state = removeLast()

        with(state.solver) {
            if (lastStatus.statusKind == UtSolverStatusKind.UNSAT) return@with

            if (lastStatus.statusKind == UtSolverStatusKind.SAT) return state

            require(failedAssumptions.isEmpty()) { "There are failed requirements in the queue to execute concretely" }

            if (processStatesWithUnknownStatus) {
                if (lastStatus == UtSolverStatusUNDEFINED) {
                    val result = check(respectSoft = true)

                    if (result.statusKind == UtSolverStatusKind.SAT) return state
                }
            }
        }
    }

    return null
}

fun isOverriddenClass(type: RefType) = type.sootClass.isOverridden

val SootMethod.isSynthetic: Boolean
    get() = soot.Modifier.isSynthetic(modifiers)

/**
 * Returns true if the [SootMethod]'s signature is equal to [UtMock.assume]'s signature, false otherwise.
 */
val SootMethod.isUtMockAssume
    get() = signature == assumeMethod.signature

/**
 * Returns true if the [SootMethod]'s signature is equal to
 * [UtMock.assumeOrExecuteConcretely]'s signature, false otherwise.
 */
val SootMethod.isUtMockAssumeOrExecuteConcretely
    get() = signature == assumeOrExecuteConcretelyMethod.signature

/**
 * Returns true is the [SootMethod] is defined in a class from
 * [UTBOT_OVERRIDE_PACKAGE_NAME] package and its name is `preconditionCheck`.
 */
val SootMethod.isPreconditionCheckMethod
    get() = declaringClass.isOverridden && name == "preconditionCheck"