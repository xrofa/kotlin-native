/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.objcexport

import org.jetbrains.kotlin.backend.konan.descriptors.isArray
import org.jetbrains.kotlin.backend.konan.descriptors.isInterface
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.konan.isKonanStdlib
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.descriptorUtil.isSubclassOf
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.source.PsiSourceFile

interface ObjCExportNameTranslator {
    fun getFileClassName(file: KtFile): ObjCExportNamer.ClassOrProtocolName

    fun getClassOrProtocolName(
            ktClassOrObject: KtClassOrObject
    ): ObjCExportNamer.ClassOrProtocolName
}

interface ObjCExportNamer {
    data class ClassOrProtocolName(val swiftName: String, val objCName: String, val binaryName: String = objCName)

    interface Configuration {
        val topLevelNamePrefix: String
        fun getAdditionalPrefix(module: ModuleDescriptor): String?
    }

    fun getFileClassName(file: SourceFile): ClassOrProtocolName
    fun getClassOrProtocolName(descriptor: ClassDescriptor): ClassOrProtocolName
    fun getSelector(method: FunctionDescriptor): String
    fun getSwiftName(method: FunctionDescriptor): String
    fun getPropertyName(property: PropertyDescriptor): String
    fun getObjectInstanceSelector(descriptor: ClassDescriptor): String
    fun getEnumEntrySelector(descriptor: ClassDescriptor): String

    fun numberBoxName(classId: ClassId): ClassOrProtocolName

    val kotlinAnyName: ClassOrProtocolName
    val mutableSetName: ClassOrProtocolName
    val mutableMapName: ClassOrProtocolName
    val kotlinNumberName: ClassOrProtocolName
}

fun createNamer(moduleDescriptor: ModuleDescriptor,
                topLevelNamePrefix: String = moduleDescriptor.namePrefix): ObjCExportNamer =
        createNamer(moduleDescriptor, emptyList(), topLevelNamePrefix)

fun createNamer(
        moduleDescriptor: ModuleDescriptor,
        exportedDependencies: List<ModuleDescriptor>,
        topLevelNamePrefix: String = moduleDescriptor.namePrefix
): ObjCExportNamer = ObjCExportNamerImpl.local(
        (exportedDependencies + moduleDescriptor),
        ObjCExportMapper(),
        topLevelNamePrefix
)

// Note: this class duplicates some of ObjCExportNamerImpl logic,
// but operates on different representation.
internal open class ObjCExportNameTranslatorImpl(
        topLevelNamePrefix: String
) : ObjCExportNameTranslator {

    constructor(configuration: ObjCExportNamer.Configuration) : this(configuration.topLevelNamePrefix)

    private val helper = ObjCExportNamingHelper(topLevelNamePrefix)

    override fun getFileClassName(file: KtFile): ObjCExportNamer.ClassOrProtocolName =
            helper.getFileClassName(file)

    override fun getClassOrProtocolName(
            ktClassOrObject: KtClassOrObject
    ): ObjCExportNamer.ClassOrProtocolName = helper.swiftClassNameToObjC(
            getClassOrProtocolSwiftName(ktClassOrObject)
    )

    private fun getClassOrProtocolSwiftName(
            ktClassOrObject: KtClassOrObject
    ): String = buildString {
        val outerClass = ktClassOrObject.getStrictParentOfType<KtClassOrObject>()
        // TODO: consider making it more strict.
        if (outerClass != null) {
            append(getClassOrProtocolSwiftName(outerClass))

            val importAsMember = when {
                ktClassOrObject.isInterface || outerClass.isInterface -> {
                    // Swift doesn't support neither nested nor outer protocols.
                    false
                }

                this.contains('.') -> {
                    // Swift doesn't support swift_name with deeply nested names.
                    // It seems to support "OriginalObjCName.SwiftName" though,
                    // but this doesn't seem neither documented nor reliable.
                    false
                }

                else -> true
            }

            if (importAsMember) {
                append(".").append(ktClassOrObject.name!!)
            } else {
                append(ktClassOrObject.name!!.capitalize())
            }
        } else {
            append(ktClassOrObject.name)
        }
    }
}

private class ObjCExportNamingHelper(
        private val topLevelNamePrefix: String
) {
    fun getFileClassName(fileName: String): ObjCExportNamer.ClassOrProtocolName {
        val baseName = PackagePartClassUtils.getFilePartShortName(fileName)
        return ObjCExportNamer.ClassOrProtocolName(swiftName = baseName, objCName = "$topLevelNamePrefix$baseName")
    }

    fun swiftClassNameToObjC(swiftName: String): ObjCExportNamer.ClassOrProtocolName =
            ObjCExportNamer.ClassOrProtocolName(swiftName, buildString {
                append(topLevelNamePrefix)
                swiftName.split('.').forEachIndexed { index, part ->
                    append(if (index == 0) part else part.capitalize())
                }
            })

    fun getFileClassName(file: KtFile): ObjCExportNamer.ClassOrProtocolName =
            getFileClassName(file.name)
}

internal class ObjCExportNamerImpl private constructor(
        private val configuration: ObjCExportNamer.Configuration,
        private val mapper: ObjCExportMapper,
        private val local: Boolean
) : ObjCExportNamer {

    private constructor(
                moduleNames: Set<Name>,
                mapper: ObjCExportMapper,
                topLevelNamePrefix: String,
                local: Boolean
    ) : this(object : ObjCExportNamer.Configuration {
        override val topLevelNamePrefix: String
            get() = topLevelNamePrefix

        override fun getAdditionalPrefix(module: ModuleDescriptor): String? =
                if (module.name in moduleNames) null else module.namePrefix

    }, mapper, local)

    companion object {
        fun local(configuration: ObjCExportNamer.Configuration, mapper: ObjCExportMapper) =
                ObjCExportNamerImpl(configuration, mapper, local = true)

        fun local(moduleNames: Set<Name>, mapper: ObjCExportMapper, topLevelNamePrefix: String) =
                ObjCExportNamerImpl(moduleNames, mapper, topLevelNamePrefix, local = true)

        fun local(moduleDescriptors: List<ModuleDescriptor>, mapper: ObjCExportMapper, topLevelNamePrefix: String) =
                local(moduleDescriptors.map { it.name }.toSet(), mapper, topLevelNamePrefix)

        fun global(
                moduleNames: Set<Name>,
                mapper: ObjCExportMapper,
                topLevelNamePrefix: String,
                builtIns: KotlinBuiltIns
        ) = ObjCExportNamerImpl(moduleNames, mapper, topLevelNamePrefix, local = false).apply {
            forceAssign(builtIns)
        }

        fun global(
                moduleDescriptors: List<ModuleDescriptor>,
                mapper: ObjCExportMapper,
                topLevelNamePrefix: String,
                builtIns: KotlinBuiltIns
        ) = global(moduleDescriptors.map { it.name }.toSet(), mapper, topLevelNamePrefix, builtIns)
    }

    private fun String.toUnmangledClassOrProtocolName(): ObjCExportNamer.ClassOrProtocolName =
            ObjCExportNamer.ClassOrProtocolName(swiftName = this, objCName = this)

    private val topLevelNamePrefix get() = configuration.topLevelNamePrefix

    private fun String.toSpecialStandardClassOrProtocolName() = ObjCExportNamer.ClassOrProtocolName(
            swiftName = "Kotlin$this",
            objCName = "${topLevelNamePrefix}$this",
            binaryName = "Kotlin$this"
    )

    override val kotlinAnyName = "KotlinBase".toUnmangledClassOrProtocolName()

    override val mutableSetName = "MutableSet".toSpecialStandardClassOrProtocolName()
    override val mutableMapName = "MutableDictionary".toSpecialStandardClassOrProtocolName()

    override fun numberBoxName(classId: ClassId): ObjCExportNamer.ClassOrProtocolName =
            classId.shortClassName.asString().toSpecialStandardClassOrProtocolName()

    override val kotlinNumberName = "Number".toSpecialStandardClassOrProtocolName()

    private val methodSelectors = object : Mapping<FunctionDescriptor, String>() {

        // Try to avoid clashing with critical NSObject instance methods:

        private val reserved = setOf(
                "retain", "release", "autorelease",
                "class", "superclass",
                "hash"
        )

        override fun reserved(name: String) = name in reserved

        override fun conflict(first: FunctionDescriptor, second: FunctionDescriptor): Boolean =
                !mapper.canHaveSameSelector(first, second)
    }

    private val methodSwiftNames = object : Mapping<FunctionDescriptor, String>() {
        override fun conflict(first: FunctionDescriptor, second: FunctionDescriptor): Boolean =
                !mapper.canHaveSameSelector(first, second)
        // Note: this condition is correct but can be too strict.
    }

    private val propertyNames = object : Mapping<PropertyDescriptor, String>() {
        override fun conflict(first: PropertyDescriptor, second: PropertyDescriptor): Boolean =
                !mapper.canHaveSameName(first, second)
    }

    private inner open class GlobalNameMapping<in T : Any, N> : Mapping<T, N>() {
        final override fun conflict(first: T, second: T): Boolean = true
    }

    private val objCClassNames = GlobalNameMapping<Any, String>()
    private val objCProtocolNames = GlobalNameMapping<ClassDescriptor, String>()

    // Classes and protocols share the same namespace in Swift.
    private val swiftClassAndProtocolNames = GlobalNameMapping<Any, String>()

    private abstract inner class ClassPropertyNameMapping<T : Any> : Mapping<T, String>() {

        // Try to avoid clashing with NSObject class methods:

        private val reserved = setOf(
                "retain", "release", "autorelease",
                "initialize", "load", "alloc", "new", "class", "superclass",
                "classFallbacksForKeyedArchiver", "classForKeyedUnarchiver",
                "description", "debugDescription", "version", "hash",
                "useStoredAccessor"
        )

        override fun reserved(name: String) = name in reserved
    }

    private val objectInstanceSelectors = object : ClassPropertyNameMapping<ClassDescriptor>() {
        override fun conflict(first: ClassDescriptor, second: ClassDescriptor) = false
    }

    private val enumEntrySelectors = object : ClassPropertyNameMapping<ClassDescriptor>() {
        override fun conflict(first: ClassDescriptor, second: ClassDescriptor) =
                first.containingDeclaration == second.containingDeclaration
    }

    override fun getFileClassName(file: SourceFile): ObjCExportNamer.ClassOrProtocolName {
        val baseName by lazy {
            val fileName = when (file) {
                is PsiSourceFile -> {
                    val psiFile = file.psiFile
                    val ktFile = psiFile as? KtFile ?: error("PsiFile '$psiFile' is not KtFile")
                    ktFile.name
                }
                else -> file.name ?: error("$file has no name")
            }
            PackagePartClassUtils.getFilePartShortName(fileName)
        }

        val objCName = objCClassNames.getOrPut(file) {
            StringBuilder(topLevelNamePrefix).append(baseName)
                    .mangledBySuffixUnderscores()
        }

        val swiftName = swiftClassAndProtocolNames.getOrPut(file) {
            StringBuilder(baseName)
                    .mangledBySuffixUnderscores()
        }

        return ObjCExportNamer.ClassOrProtocolName(swiftName = swiftName, objCName = objCName)
    }

    override fun getClassOrProtocolName(descriptor: ClassDescriptor): ObjCExportNamer.ClassOrProtocolName =
            ObjCExportNamer.ClassOrProtocolName(
                    swiftName = getClassOrProtocolSwiftName(descriptor),
                    objCName = getClassOrProtocolObjCName(descriptor)
            )

    private fun getClassOrProtocolSwiftName(
            descriptor: ClassDescriptor
    ): String = swiftClassAndProtocolNames.getOrPut(descriptor) {
        StringBuilder().apply {
            val containingDeclaration = descriptor.containingDeclaration
            if (containingDeclaration is ClassDescriptor) {
                append(getClassOrProtocolSwiftName(containingDeclaration))

                val importAsMember = when {
                    descriptor.isInterface || containingDeclaration.isInterface -> {
                        // Swift doesn't support neither nested nor outer protocols.
                        false
                    }

                    this.contains('.') -> {
                        // Swift doesn't support swift_name with deeply nested names.
                        // It seems to support "OriginalObjCName.SwiftName" though,
                        // but this doesn't seem neither documented nor reliable.
                        false
                    }

                    else -> true
                }
                if (importAsMember) {
                    append(".").append(descriptor.name.asString())
                } else {
                    append(descriptor.name.asString().capitalize())
                }
            } else if (containingDeclaration is PackageFragmentDescriptor) {
                appendTopLevelClassBaseName(descriptor)
            } else {
                error("unexpected class parent: $containingDeclaration")
            }
        }.mangledBySuffixUnderscores()
    }

    private fun getClassOrProtocolObjCName(descriptor: ClassDescriptor): String {
        val objCMapping = if (descriptor.isInterface) objCProtocolNames else objCClassNames
        return objCMapping.getOrPut(descriptor) {
            StringBuilder().apply {
                val containingDeclaration = descriptor.containingDeclaration
                if (containingDeclaration is ClassDescriptor) {
                    append(getClassOrProtocolObjCName(containingDeclaration))
                            .append(descriptor.name.asString().capitalize())

                } else if (containingDeclaration is PackageFragmentDescriptor) {
                    append(topLevelNamePrefix).appendTopLevelClassBaseName(descriptor)
                } else {
                    error("unexpected class parent: $containingDeclaration")
                }
            }.mangledBySuffixUnderscores()
        }
    }

    private fun StringBuilder.appendTopLevelClassBaseName(descriptor: ClassDescriptor) = apply {
        configuration.getAdditionalPrefix(descriptor.module)?.let {
            append(it)
        }
        append(descriptor.name.asString())
    }

    override fun getSelector(method: FunctionDescriptor): String = methodSelectors.getOrPut(method) {
        assert(mapper.isBaseMethod(method))

        getPredefined(method, Predefined.anyMethodSelectors)?.let { return it }

        val parameters = mapper.bridgeMethod(method).valueParametersAssociated(method)

        StringBuilder().apply {
            append(method.getMangledName(forSwift = false))

            parameters.forEachIndexed { index, (bridge, it) ->
                val name = when (bridge) {
                    is MethodBridgeValueParameter.Mapped -> when {
                        it is ReceiverParameterDescriptor -> ""
                        method is PropertySetterDescriptor -> when (parameters.size) {
                            1 -> ""
                            else -> "value"
                        }
                        else -> it!!.name.asString()
                    }
                    MethodBridgeValueParameter.ErrorOutParameter -> "error"
                    is MethodBridgeValueParameter.KotlinResultOutParameter -> "result"
                }

                if (index == 0) {
                    append(when {
                        bridge is MethodBridgeValueParameter.ErrorOutParameter ||
                                bridge is MethodBridgeValueParameter.KotlinResultOutParameter -> "AndReturn"

                        method is ConstructorDescriptor -> "With"
                        else -> ""
                    })
                    append(name.capitalize())
                } else {
                    append(name)
                }

                append(':')
            }
        }.mangledSequence {
            if (parameters.isNotEmpty()) {
                // "foo:" -> "foo_:"
                insert(lastIndex, '_')
            } else {
                // "foo" -> "foo_"
                append("_")
            }
        }
    }

    override fun getSwiftName(method: FunctionDescriptor): String = methodSwiftNames.getOrPut(method) {
        assert(mapper.isBaseMethod(method))

        getPredefined(method, Predefined.anyMethodSwiftNames)?.let { return it }

        val parameters = mapper.bridgeMethod(method).valueParametersAssociated(method)

        StringBuilder().apply {
            append(method.getMangledName(forSwift = true))
            append("(")

            parameters@ for ((bridge, it) in parameters) {
                val label = when (bridge) {
                    is MethodBridgeValueParameter.Mapped -> when {
                        it is ReceiverParameterDescriptor -> "_"
                        method is PropertySetterDescriptor -> when (parameters.size) {
                            1 -> "_"
                            else -> "value"
                        }
                        else -> it!!.name.asString()
                    }
                    MethodBridgeValueParameter.ErrorOutParameter -> continue@parameters
                    is MethodBridgeValueParameter.KotlinResultOutParameter -> "result"
                }

                append(label)
                append(":")
            }

            append(")")
        }.mangledSequence {
            // "foo(label:)" -> "foo(label_:)"
            // "foo()" -> "foo_()"
            insert(lastIndex - 1, '_')
        }
    }

    private fun <T : Any> getPredefined(method: FunctionDescriptor, predefined: Map<Name, T>): T? {
        val containingDeclaration = method.containingDeclaration
        return if (containingDeclaration is ClassDescriptor && KotlinBuiltIns.isAny(containingDeclaration)) {
            predefined.getValue(method.name)
        } else {
            null
        }
    }

    override fun getPropertyName(property: PropertyDescriptor): String = propertyNames.getOrPut(property) {
        assert(mapper.isBaseProperty(property))
        assert(mapper.isObjCProperty(property))

        StringBuilder().apply {
            append(property.name.asString())
        }.mangledSequence {
            append('_')
        }
    }

    override fun getObjectInstanceSelector(descriptor: ClassDescriptor): String {
        assert(descriptor.kind == ClassKind.OBJECT)

        return objectInstanceSelectors.getOrPut(descriptor) {
            val name = descriptor.name.asString().decapitalize().mangleIfSpecialFamily("get")

            StringBuilder(name).mangledBySuffixUnderscores()
        }
    }

    override fun getEnumEntrySelector(descriptor: ClassDescriptor): String {
        assert(descriptor.kind == ClassKind.ENUM_ENTRY)

        return enumEntrySelectors.getOrPut(descriptor) {
            // FOO_BAR_BAZ -> fooBarBaz:
            val name = descriptor.name.asString().split('_').mapIndexed { index, s ->
                val lower = s.toLowerCase()
                if (index == 0) lower else lower.capitalize()
            }.joinToString("").mangleIfSpecialFamily("the")

            StringBuilder(name).mangledBySuffixUnderscores()
        }
    }

    private object Predefined {
        val anyMethodSelectors = mapOf(
                "hashCode" to "hash",
                "toString" to "description",
                "equals" to "isEqual:"
        ).mapKeys { Name.identifier(it.key) }

        val anyMethodSwiftNames = mapOf(
                "hashCode" to "hash()",
                "toString" to "description()",
                "equals" to "isEqual(:)"
        ).mapKeys { Name.identifier(it.key) }
    }

    private fun forceAssign(builtIns: KotlinBuiltIns) {
        mapOf(
                builtIns.any to kotlinAnyName,
                builtIns.mutableSet to mutableSetName,
                builtIns.mutableMap to mutableMapName
        ).forEach { descriptor, name ->
            objCClassNames.forceAssign(descriptor, name.objCName)
            swiftClassAndProtocolNames.forceAssign(descriptor, name.swiftName)
        }

        fun ClassDescriptor.method(name: Name) =
                this.unsubstitutedMemberScope.getContributedFunctions(
                        name,
                        NoLookupLocation.FROM_BACKEND
                ).single()

        val any = builtIns.any

        Predefined.anyMethodSelectors.forEach { name, selector ->
            methodSelectors.forceAssign(any.method(name), selector)
        }

        Predefined.anyMethodSwiftNames.forEach { name, swiftName ->
            methodSwiftNames.forceAssign(any.method(name), swiftName)
        }
    }

    private fun FunctionDescriptor.getMangledName(forSwift: Boolean): String {
        if (this is ConstructorDescriptor) {
            return if (this.constructedClass.isArray && !forSwift) "array" else "init"
        }

        val candidate = when (this) {
            is PropertyGetterDescriptor -> this.correspondingProperty.name.asString()
            is PropertySetterDescriptor -> "set${this.correspondingProperty.name.asString().capitalize()}"
            else -> this.name.asString()
        }

        return candidate.mangleIfSpecialFamily("do")
    }

    private fun String.mangleIfSpecialFamily(prefix: String): String {
        val trimmed = this.dropWhile { it == '_' }
        for (family in listOf("alloc", "copy", "mutableCopy", "new", "init")) {
            if (trimmed.startsWithWords(family)) {
                // Then method can be detected as having special family by Objective-C compiler.
                // mangle the name:
                return prefix + this.capitalize()
            }
        }

        // TODO: handle clashes with NSObject methods etc.

        return this
    }

    private fun String.startsWithWords(words: String) = this.startsWith(words) &&
            (this.length == words.length || !this[words.length].isLowerCase())

    private abstract inner class Mapping<in T : Any, N>() {
        private val elementToName = mutableMapOf<T, N>()
        private val nameToElements = mutableMapOf<N, MutableList<T>>()

        abstract fun conflict(first: T, second: T): Boolean
        open fun reserved(name: N) = false

        inline fun getOrPut(element: T, nameCandidates: () -> Sequence<N>): N {
            getIfAssigned(element)?.let { return it }

            nameCandidates().forEach {
                if (tryAssign(element, it)) {
                    return it
                }
            }

            error("name candidates run out")
        }

        private fun getIfAssigned(element: T): N? = elementToName[element]

        private fun tryAssign(element: T, name: N): Boolean {
            if (element in elementToName) error(element)

            if (reserved(name)) return false

            if (nameToElements[name].orEmpty().any { conflict(element, it) }) {
                return false
            }

            if (!local) {
                nameToElements.getOrPut(name) { mutableListOf() } += element

                elementToName[element] = name
            }

            return true
        }

        fun forceAssign(element: T, name: N) {
            if (name in nameToElements || element in elementToName) error(element)

            nameToElements[name] = mutableListOf(element)
            elementToName[element] = name
        }
    }

}

private inline fun StringBuilder.mangledSequence(crossinline mangle: StringBuilder.() -> Unit) =
        generateSequence(this.toString()) {
            this@mangledSequence.mangle()
            this@mangledSequence.toString()
        }

private fun StringBuilder.mangledBySuffixUnderscores() = this.mangledSequence { append("_") }

private fun ObjCExportMapper.canHaveCommonSubtype(first: ClassDescriptor, second: ClassDescriptor): Boolean {
    if (first.isSubclassOf(second) || second.isSubclassOf(first)) {
        return true
    }

    if (first.isFinalClass || second.isFinalClass) {
        return false
    }

    return first.isInterface || second.isInterface
}

private fun ObjCExportMapper.canBeInheritedBySameClass(
        first: CallableMemberDescriptor,
        second: CallableMemberDescriptor
): Boolean {
    if (this.isTopLevel(first) || this.isTopLevel(second)) {
        return this.isTopLevel(first) && this.isTopLevel(second) &&
                first.source.containingFile == second.source.containingFile
    }

    val firstClass = this.getClassIfCategory(first) ?: first.containingDeclaration as ClassDescriptor
    val secondClass = this.getClassIfCategory(second) ?: second.containingDeclaration as ClassDescriptor

    if (first is ConstructorDescriptor) {
        return firstClass == secondClass || second !is ConstructorDescriptor && firstClass.isSubclassOf(secondClass)
    }

    if (second is ConstructorDescriptor) {
        return secondClass == firstClass || first !is ConstructorDescriptor && secondClass.isSubclassOf(firstClass)
    }

    return canHaveCommonSubtype(firstClass, secondClass)
}

private fun ObjCExportMapper.canHaveSameSelector(first: FunctionDescriptor, second: FunctionDescriptor): Boolean {
    assert(isBaseMethod(first))
    assert(isBaseMethod(second))

    if (!canBeInheritedBySameClass(first, second)) {
        return true
    }

    if (first.dispatchReceiverParameter == null || second.dispatchReceiverParameter == null) {
        // I.e. any is category method.
        return false
    }

    if (first.name != second.name) {
        return false
    }
    if (first.extensionReceiverParameter?.type != second.extensionReceiverParameter?.type) {
        return false
    }

    if (first is PropertySetterDescriptor && second is PropertySetterDescriptor) {
        // Methods should merge in any common subclass as it can't have two properties with same name.
    } else if (first.valueParameters.map { it.type } == second.valueParameters.map { it.type }) {
        // Methods should merge in any common subclasses since they have the same signature.
    } else {
        return false
    }

    // Check if methods have the same bridge (and thus the same ABI):
    return bridgeMethod(first) == bridgeMethod(second)
}

private fun ObjCExportMapper.canHaveSameName(first: PropertyDescriptor, second: PropertyDescriptor): Boolean {
    assert(isBaseProperty(first))
    assert(isObjCProperty(first))
    assert(isBaseProperty(second))
    assert(isObjCProperty(second))

    if (!canBeInheritedBySameClass(first, second)) {
        return true
    }

    if (first.dispatchReceiverParameter == null || second.dispatchReceiverParameter == null) {
        // I.e. any is category property.
        return false
    }

    return bridgePropertyType(first) == bridgePropertyType(second)
}

internal val ModuleDescriptor.namePrefix: String get() {
    if (this.isKonanStdlib()) return "Kotlin"

    return abbreviateSpecialName(this.name)
}

private fun abbreviateSpecialName(name: Name): String =
        abbreviate(name.asString()
                .let { it.substring(1, it.lastIndex) })

internal fun abbreviate(name: String): String {
    val moduleName = name
            .capitalize()
            .replace('-', '_')

    val uppers = moduleName.filterIndexed { index, character -> index == 0 || character.isUpperCase() }
    if (uppers.length >= 3) return uppers

    return moduleName
}

internal val KtPureClassOrObject.isInterface get() = when (this) {
    is KtClass -> this.isInterface()
    is KtObjectDeclaration -> false
    else -> error("Unexpected KtClassOrObject: $this")
}
