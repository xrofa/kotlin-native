package org.jetbrains.kotlin.backend.konan.objcexport

import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.PropertyDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.descriptors.konan.isKonanStdlib
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.hasExpectModifier
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifierTypeOrDefault
import org.jetbrains.kotlin.resolve.BindingTraceContext
import org.jetbrains.kotlin.resolve.DescriptorResolver
import org.jetbrains.kotlin.resolve.TypeResolver
import org.jetbrains.kotlin.resolve.lazy.FileScopeProvider
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.LexicalScopeKind
import org.jetbrains.kotlin.resolve.scopes.LexicalWritableScope
import org.jetbrains.kotlin.resolve.scopes.LocalRedeclarationChecker
import org.jetbrains.kotlin.resolve.source.PsiSourceFile

interface ObjCExportLazy {
    interface Configuration {
        val frameworkName: String
        fun isIncluded(moduleInfo: ModuleInfo): Boolean
        fun getCompilerModuleName(moduleInfo: ModuleInfo): String
    }

    fun translate(file: KtFile): List<ObjCTopLevel<*>>
    fun generateBase(): List<ObjCTopLevel<*>>
}

class ObjCExportLazyImpl(
        configuration: ObjCExportLazy.Configuration,
        private val resolveSession: ResolveSession,
        private val typeResolver: TypeResolver,
        private val descriptorResolver: DescriptorResolver,
        private val fileScopeProvider: FileScopeProvider
) : ObjCExportLazy {

    private val namerConfiguration = createNamerConfiguration(configuration)
    private val nameTranslator: ObjCExportNameTranslator = ObjCExportNameTranslatorImpl(namerConfiguration)
    private val mapper = ObjCExportMapper()
    private val translator: ObjCExportTranslator =
            createObjCExportTranslator(namerConfiguration, ObjCExportWarningCollector.SILENT, mapper)

    override fun generateBase() = translator.generateBaseDeclarations()

    override fun translate(file: KtFile): List<ObjCTopLevel<*>> {
        val result = mutableListOf<ObjCTopLevel<*>>()

        file.children.flatMapTo(result) {
            if (it is KtClassOrObject) translateClassAndNested(it) else emptyList()
        }

        result += translateTopLevels(file)

        return result
    }

    private fun translateClassAndNested(ktClassOrObject: KtClassOrObject): List<ObjCClass<*>> {
        if (ktClassOrObject.visibilityModifierTypeOrDefault() != KtTokens.PUBLIC_KEYWORD) return emptyList()
        if (ktClassOrObject is KtEnumEntry) return emptyList()
        if (ktClassOrObject.hasExpectModifier()) return emptyList()

        val result = mutableListOf<ObjCClass<*>>()

        if (!ktClassOrObject.isAnnotation() && !ktClassOrObject.hasModifier(KtTokens.INLINE_KEYWORD)) {
            // FIXME: special-mapped.
            result += translateClass(ktClassOrObject)
        }

        ktClassOrObject.body?.children?.flatMapTo(result) {
            if (it is KtClassOrObject) translateClassAndNested(it) else emptyList()
        }
        return result
    }

    private fun translateClass(ktClassOrObject: KtClassOrObject): ObjCClass<*> {
        val name = nameTranslator.getClassOrProtocolName(ktClassOrObject)
        val nameAttributes = name.toNameAttributes()
        return if (ktClassOrObject.isInterface) {
            object : ObjCProtocol(name.objCName, nameAttributes) {
                override val descriptor: ClassDescriptor by lazy { resolve(ktClassOrObject) }

                private val realStub by lazy { translator.translateInterface(descriptor) }

                override val members: List<Stub<*>>
                    get() = realStub.members
                override val superProtocols: List<String>
                    get() = realStub.superProtocols

            }
        } else {
            val isFinal = when (ktClassOrObject) { // FIXME
                is KtObjectDeclaration -> true
                is KtClass -> ktClassOrObject.isEnum() ||
                        listOf(KtTokens.ABSTRACT_KEYWORD, KtTokens.OPEN_KEYWORD, KtTokens.SEALED_KEYWORD)
                                .none { ktClassOrObject.hasModifier(it) }

                else -> error("$ktClassOrObject") // FIXME
            }
            val attributes = if (isFinal) {
                listOf(OBJC_SUBCLASSING_RESTRICTED) + nameAttributes
            } else {
                nameAttributes
            }

            object : ObjCInterface(
                    name.objCName,
                    generics = emptyList(),
                    categoryName = null,
                    attributes = attributes
            ) {
                override val descriptor: ClassDescriptor by lazy { resolve(ktClassOrObject) }

                private val realStub by lazy { translator.translateClass(descriptor) }

                override val members: List<Stub<*>>
                    get() = realStub.members
                override val superProtocols: List<String>
                    get() = realStub.superProtocols
                override val superClass: String?
                    get() = realStub.superClass

            }
        }
    }

    protected fun resolveDeclaration(ktDeclaration: KtDeclaration): DeclarationDescriptor =
            resolveSession.resolveToDescriptor(ktDeclaration)
    protected fun getFileScope(ktFile: KtFile): LexicalScope = fileScopeProvider.getFileResolutionScope(ktFile)

    private fun resolve(ktClassOrObject: KtClassOrObject) = resolveDeclaration(ktClassOrObject) as ClassDescriptor
    private fun resolve(ktCallableDeclaration: KtCallableDeclaration) =
            resolveDeclaration(ktCallableDeclaration) as CallableMemberDescriptor

    private fun translateTopLevels(file: KtFile): List<ObjCInterface> {
        val extensions =
                mutableMapOf<ClassDescriptor, MutableList<() -> CallableMemberDescriptor>>()

        val topLevel = mutableListOf<() -> CallableMemberDescriptor>()
        file.children.filterIsInstance<KtCallableDeclaration>().forEach {
            if (it is KtFunction || it is KtProperty) {
                val classDescriptor = getClassIfExtension(it)
                if (classDescriptor != null) {
                    extensions.getOrPut(classDescriptor, { mutableListOf() }) += { resolve(it) }
                } else {
                    topLevel += { resolve(it) }
                }
            }
        }

        val result = mutableListOf<ObjCInterface>()

        extensions.mapTo(result) { (classDescriptor, declarations) ->
            translateExtensions(classDescriptor, declarations)
        }

        if (topLevel.isNotEmpty()) result += translateFileClass(file, topLevel)

        return result
    }

    private fun getClassIfExtension(declaration: KtCallableDeclaration): ClassDescriptor? {
        val receiverType = declaration.receiverTypeReference ?: return null
        val scope = getHeaderScope(declaration, getFileScope(declaration.containingKtFile))

        val kotlinReceiverType = typeResolver.resolveType(
                scope,
                receiverType,
                BindingTraceContext(), // FIXME: revise
                checkBounds = false // FIXME: revise
        )


        return translator.getClassIfExtension(kotlinReceiverType)
    }

    private fun getHeaderScope(declaration: KtCallableDeclaration, parent: LexicalScope): LexicalScope {
        val fakeName = Name.special("<fake>")
        val sourceElement = SourceElement.NO_SOURCE

        val descriptor: CallableMemberDescriptor
        val scopeKind: LexicalScopeKind

        when (declaration) {
            is KtFunction -> {
                descriptor = SimpleFunctionDescriptorImpl.create(
                        parent.ownerDescriptor,
                        Annotations.EMPTY,
                        fakeName,
                        CallableMemberDescriptor.Kind.DECLARATION,
                        sourceElement
                )
                scopeKind = LexicalScopeKind.FUNCTION_HEADER
            }
            is KtProperty -> {
                descriptor = PropertyDescriptorImpl.create(
                        parent.ownerDescriptor,
                        Annotations.EMPTY,
                        Modality.FINAL,
                        Visibilities.PUBLIC,
                        declaration.isVar,
                        fakeName,
                        CallableMemberDescriptor.Kind.DECLARATION,
                        sourceElement,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false
                )
                scopeKind = LexicalScopeKind.PROPERTY_HEADER
            }
            else -> TODO()
        }
        val result = LexicalWritableScope(
                parent,
                descriptor,
                false,
                LocalRedeclarationChecker.DO_NOTHING,
                scopeKind
        )

        val trace = BindingTraceContext()
        val typeParameters =
                descriptorResolver.resolveTypeParametersForDescriptor(descriptor, result, result, declaration.typeParameters, trace)
        descriptorResolver.resolveGenericBounds(declaration, descriptor, result, typeParameters, trace)

        return result
    }

    private fun translateExtensions(
            classDescriptor: ClassDescriptor,
            declarations: List<() -> CallableMemberDescriptor>
    ): ObjCInterface {
        val fakeStub = translator.translateExtensions(classDescriptor, emptyList()) // FIXME: add proper API
        return object : ObjCInterface(fakeStub.name, fakeStub.generics, fakeStub.categoryName, fakeStub.attributes) {
            val realStub: ObjCInterface by lazy {
                translator.translateExtensions(classDescriptor,
                        declarations.mapNotNull { declaration ->
                            declaration().takeIf { mapper.shouldBeExposed(it) }
                        })
            }

            override val descriptor: ClassDescriptor?
                get() = null
            override val members: List<Stub<*>>
                get() = realStub.members
            override val superProtocols: List<String>
                get() = realStub.superProtocols
            override val superClass: String?
                get() = realStub.superClass

        }
    }

    private fun translateFileClass(file: KtFile, declarations: List<() -> CallableMemberDescriptor>): ObjCInterface {
        val name = nameTranslator.getFileClassName(file)
        return object : ObjCInterface(
                name.objCName,
                generics = emptyList(),
                categoryName = null,
                attributes = listOf(OBJC_SUBCLASSING_RESTRICTED) + name.toNameAttributes()
        ) {

            private val realStub by lazy {
                translator.translateFile(PsiSourceFile(file),
                        declarations.mapNotNull { declaration ->
                            declaration().takeIf { mapper.shouldBeExposed(it) }
                        })
            }

            override val descriptor: ClassDescriptor?
                get() = null
            override val members: List<Stub<*>>
                get() = realStub.members
            override val superProtocols: List<String>
                get() = realStub.superProtocols
            override val superClass: String?
                get() = realStub.superClass
        }
    }
}


private fun createObjCExportTranslator(
        configuration: ObjCExportNamer.Configuration,
        warningCollector: ObjCExportWarningCollector,
        mapper: ObjCExportMapper
): ObjCExportTranslator = ObjCExportTranslatorImpl(
        null,
        mapper,
        ObjCExportNamerImpl.local(configuration, mapper),
        warningCollector
)

private fun createNamerConfiguration(configuration: ObjCExportLazy.Configuration): ObjCExportNamer.Configuration {
    return object : ObjCExportNamer.Configuration {
        override val topLevelNamePrefix = configuration.topLevelNamePrefix

        override fun getAdditionalPrefix(module: ModuleDescriptor): String? {
            if (module.isKonanStdlib()) return "Kotlin" // FIXME
            // FIXME: incorrect for compiler.
            val moduleInfo = module.getCapability(ModuleInfo.Capability) ?: return null
            if (configuration.isIncluded(moduleInfo)) return null
            return abbreviate(configuration.getCompilerModuleName(moduleInfo))
        }

    }
}

internal val ObjCExportLazy.Configuration.topLevelNamePrefix: String get() = abbreviate(this.frameworkName)