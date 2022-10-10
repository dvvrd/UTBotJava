package org.utbot.engine

/**
 * Represents an immutable symbolic state.
 *
 * @param [solver] stores [UtSolver] with symbolic constraints.
 * @param [memory] stores the current state of the symbolic memory.
 */
data class SymbolicState<Type>(
    val solver: UtSolver,
    val memory: Memory<Type> = Memory(),
) {
    operator fun plus(update: SymbolicStateUpdate<Type>): SymbolicState<Type> =
        with(update) {
            SymbolicState(
                solver.add(hard = hardConstraints, soft = softConstraints, assumption = assumptions),
                memory = memory.update(memoryUpdates),
            )
        }

    operator fun plus(update: HardConstraint): SymbolicState<Type> =
        plus(SymbolicStateUpdate<Type>(hardConstraints = update))

    operator fun plus(update: SoftConstraint): SymbolicState<Type> =
        plus(SymbolicStateUpdate<Type>(softConstraints = update))

    operator fun plus(update: MemoryUpdate<Type>): SymbolicState<Type> =
        plus(SymbolicStateUpdate(memoryUpdates = update))


    fun stateForNestedMethod() = copy(
        memory = memory.memoryForNestedMethod()
    )

}