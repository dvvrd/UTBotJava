package org.utbot.engine.selectors.strategies

import org.utbot.engine.soot.ExecutionState
import org.utbot.engine.soot.InterProceduralUnitGraph

class GeneratedTestCountingStatistics(
    graph: InterProceduralUnitGraph
) : TraverseGraphStatistics(graph) {
    var generatedTestsCount = 0
        private set

    override fun onTraversed(executionState: ExecutionState) {
        generatedTestsCount++
    }
}