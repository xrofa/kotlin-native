package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedClassDescriptor


class DescriptorReferenceDeserializer(val currentModule: ModuleDescriptor, val resolvedForwardDeclarations: MutableMap<UniqIdKey, UniqIdKey>, val uniqIdToDescriptor: Map<UniqId, DeclarationDescriptor>) {

    fun deserializeDescriptorReference(
        //packageFqNameString: String,
        //classFqNameString: String,
        name: String,
        uniqId: UniqId,
        isEnumEntry: Boolean = false,
        isEnumSpecial: Boolean = false,
        isDefaultConstructor: Boolean = false,
        isFakeOverride: Boolean = false,
        isGetter: Boolean = false,
        isSetter: Boolean = false
    ): DeclarationDescriptor? {
        val discoverableDescriptor = uniqIdToDescriptor[uniqId] ?: return null

        return when {
            isEnumEntry -> {
                val memberScope = (discoverableDescriptor as ClassDescriptor).getUnsubstitutedMemberScope()
                memberScope.getContributedClassifier(Name.identifier(name), NoLookupLocation.FROM_BACKEND)!!
            }
            isEnumSpecial ->
                (discoverableDescriptor as ClassDescriptor)
                    .getStaticScope()
                    .getContributedFunctions(Name.identifier(name), NoLookupLocation.FROM_BACKEND).single()
            isGetter ->
                (discoverableDescriptor as PropertyDescriptor).getter

            isSetter ->
                (discoverableDescriptor as PropertyDescriptor).setter
            isDefaultConstructor ->
                (discoverableDescriptor as ClassDescriptor).constructors.first()
            else -> discoverableDescriptor
        }
    }
}
