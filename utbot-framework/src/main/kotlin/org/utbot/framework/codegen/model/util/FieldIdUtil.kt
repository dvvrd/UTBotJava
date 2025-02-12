package org.utbot.framework.codegen.model.util

import org.utbot.framework.codegen.model.constructor.context.CgContext
import org.utbot.framework.plugin.api.CodegenLanguage
import org.utbot.framework.plugin.api.FieldId
import org.utbot.framework.plugin.api.MethodId
import org.utbot.framework.plugin.api.util.voidClassId

/**
 * For now we will count field accessible if it is not private and its class is also accessible,
 * because we generate tests in the same package with the class under test,
 * which means we can access public, protected and package-private fields
 *
 * @param context context in which code is generated (it is needed because the method needs to know package and language)
 */
fun FieldId.isAccessibleFrom(packageName: String): Boolean {
    val isClassAccessible = declaringClass.isAccessibleFrom(packageName)
    val isAccessibleByVisibility = isPublic || (declaringClass.packageName == packageName && (isPackagePrivate || isProtected))
    val isAccessibleFromPackageByModifiers = isAccessibleByVisibility && !isSynthetic

    return isClassAccessible && isAccessibleFromPackageByModifiers
}

private fun FieldId.canBeReadViaGetterFrom(context: CgContext): Boolean =
    declaringClass.allMethods.contains(getter) && getter.isAccessibleFrom(context.testClassPackageName)

/**
 * Returns whether you can read field's value without reflection
 */
internal infix fun FieldId.canBeReadFrom(context: CgContext): Boolean {
    if (context.codegenLanguage == CodegenLanguage.KOTLIN) {
        // Kotlin will allow direct field access for non-static fields with accessible getter
        if (!isStatic && canBeReadViaGetterFrom(context))
            return true
    }

    return isAccessibleFrom(context.testClassPackageName)
}

private fun FieldId.canBeSetViaSetterFrom(context: CgContext): Boolean =
    declaringClass.allMethods.contains(setter) && setter.isAccessibleFrom(context.testClassPackageName)

/**
 * Whether or not a field can be set without reflection
 */
internal fun FieldId.canBeSetFrom(context: CgContext): Boolean {
    if (context.codegenLanguage == CodegenLanguage.KOTLIN) {
        // Kotlin will allow direct write access if both getter and setter is defined
        // !isAccessibleFrom(context) is important here because above rule applies to final fields only if they are not accessible in Java terms
        if (!isAccessibleFrom(context.testClassPackageName) && !isStatic && canBeReadViaGetterFrom(context) && canBeSetViaSetterFrom(context)) {
            return true
        }
    }

    return isAccessibleFrom(context.testClassPackageName) && !isFinal
}

/**
 * The default getter method for field (the one which is generated by Kotlin compiler)
 */
val FieldId.getter: MethodId
    get() = MethodId(declaringClass, "get${name.replaceFirstChar { it.uppercase() } }", type, emptyList())

/**
 * The default setter method for field (the one which is generated by Kotlin compiler)
 */
val FieldId.setter: MethodId
    get() = MethodId(declaringClass, "set${name.replaceFirstChar { it.uppercase() } }", voidClassId, listOf(type))
