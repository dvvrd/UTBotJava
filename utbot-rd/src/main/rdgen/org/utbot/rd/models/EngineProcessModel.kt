package org.utbot.rd.models

import com.jetbrains.rd.generator.nova.*

object EngineProcessProtocolRoot : Root()

object RdSourceFindingStrategy : Ext(EngineProcessProtocolRoot) {
    val sourceStrategeMethodArgs = structdef {
        field("classFqn", PredefinedType.string)
        field("extension", PredefinedType.string.nullable)
    }

    init {
        call("testsRelativePath", PredefinedType.void, PredefinedType.string).async
        call("getSourceRelativePath", sourceStrategeMethodArgs, PredefinedType.string).async
        call("getSourceFile", sourceStrategeMethodArgs, PredefinedType.string.nullable).async
    }
}

object EngineProcessModel : Ext(EngineProcessProtocolRoot) {
    val jdkInfo = structdef {
        field("path", PredefinedType.string)
        field("version", PredefinedType.int)
    }

    val testGeneratorParams = structdef {
        field("buildDir", array(PredefinedType.string))
        field("classpath", PredefinedType.string.nullable)
        field("dependencyPaths", PredefinedType.string)
        field("jdkInfo", jdkInfo)
    }
    val generateParams = structdef {
        // mocks
        field("mockInstalled", PredefinedType.bool)
        field("staticsMockingIsConfigureda", PredefinedType.bool)
        field("conflictTriggers", array(PredefinedType.byte))
        // generate
        field("methods", array(PredefinedType.byte))
        field("mockStrategy", PredefinedType.string)
        field("chosenClassesToMockAlways", array(PredefinedType.byte))
        field("timeout", PredefinedType.long)
        // testflow
        field("generationTimeout", PredefinedType.long)
        field("isSymbolicEngineEnabled", PredefinedType.bool)
        field("isFuzzingEnabled", PredefinedType.bool)
        field("fuzzingValue", PredefinedType.double)
        // method filters
        field("searchDirectory", PredefinedType.string)
    }
    val generateResult = structdef {
        field("notEmptyCases", PredefinedType.int)
        field("testSetsId", PredefinedType.long)
    }
    val renderParams = structdef {
        field("testSetsId", PredefinedType.long)
        field("classUnderTest", array(PredefinedType.byte))
        field("paramNames", array(PredefinedType.byte))
        field("generateUtilClassFile", PredefinedType.bool)
        field("testFramework", PredefinedType.string)
        field("mockFramework", PredefinedType.string)
        field("codegenLanguage", PredefinedType.string)
        field("parameterizedTestSource", PredefinedType.string)
        field("staticsMocking", PredefinedType.string)
        field("forceStaticMocking", array(PredefinedType.byte))
        field("generateWarningsForStaticMocking", PredefinedType.bool)
        field("runtimeExceptionTestsBehaviour", PredefinedType.string)
        field("hangingTestsTimeout", PredefinedType.long)
        field("enableTestsTimeout", PredefinedType.bool)
        field("testClassPackageName", PredefinedType.string)
    }
    val renderResult = structdef {
        field("generatedCode", PredefinedType.string)
        field("utilClassKind", array(PredefinedType.byte))
    }
    val setupContextParams = structdef {
        field("classpathForUrlsClassloader", immutableList(PredefinedType.string))
    }
    val signature = structdef {
        field("name", PredefinedType.string)
        field("parametersTypes", immutableList(PredefinedType.string.nullable))
    }
    val findMethodsInClassMatchingSelectedArguments = structdef {
        field("classId", array(PredefinedType.byte))
        field("signatures", immutableList(signature))
    }
    val findMethodsInClassMatchingSelectedResult = structdef {
        field("executableIds", array(PredefinedType.byte))
    }
    val findMethodParamNamesArguments = structdef {
        field("classId", array(PredefinedType.byte))
        field("bySignature", array(PredefinedType.byte))
    }
    val findMethodParamNamesResult = structdef {
        field("paramNames", array(PredefinedType.byte))
    }
    val writeSarifReportArguments = structdef {
        field("testSetsId", PredefinedType.long)
        field("reportFilePath", PredefinedType.string)
        field("generatedTestsCode", PredefinedType.string)
    }
    val generateTestReportArgs = structdef {
        field("eventLogMessage", PredefinedType.string.nullable)
        field("testPackageName", PredefinedType.string.nullable)
        field("isMultiPackage", PredefinedType.bool)
        field("forceMockWarning", PredefinedType.string.nullable)
        field("forceStaticMockWarnings", PredefinedType.string.nullable)
        field("testFrameworkWarning", PredefinedType.string.nullable)
        field("hasInitialWarnings", PredefinedType.bool)
    }
    val generateTestReportResult = structdef {
        field("notifyMessage", PredefinedType.string)
        field("statistics", PredefinedType.string.nullable)
        field("hasWarnings", PredefinedType.bool)
    }
    init {
        call("setupUtContext", setupContextParams, PredefinedType.void).async
        call("createTestGenerator", testGeneratorParams, PredefinedType.void).async
        call("isCancelled", PredefinedType.void, PredefinedType.bool).async
        call("generate", generateParams, generateResult).async
        call("render", renderParams, renderResult).async
        call("stopProcess", PredefinedType.void, PredefinedType.void).async
        call("obtainClassId", PredefinedType.string, array(PredefinedType.byte)).async
        call("findMethodsInClassMatchingSelected", findMethodsInClassMatchingSelectedArguments, findMethodsInClassMatchingSelectedResult).async
        call("findMethodParamNames", findMethodParamNamesArguments, findMethodParamNamesResult).async
        call("writeSarifReport", writeSarifReportArguments, PredefinedType.void).async
        call("generateTestReport", generateTestReportArgs, generateTestReportResult).async
    }
}