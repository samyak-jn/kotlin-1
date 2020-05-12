/*
 * Copyright 2010-2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.common.serialization

import org.jetbrains.kotlin.backend.common.ir.ir2stringWhole
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrDynamicType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeCheckerContext
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.isReal
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.OverridingUtil.OverrideCompatibilityInfo
import org.jetbrains.kotlin.resolve.OverridingUtil.OverrideCompatibilityInfo.incompatible
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.types.AbstractTypeCheckerContext
import kotlin.annotations.jvm.Mutable

interface FakeOverrideBuilderStrategy {
    fun fakeOverrideMember(superType: IrType, member: IrOverridableMember, clazz: IrClass, newModality: Modality? = null, newVisibility: Visibility? =  null): IrOverridableMember
    // TODO: this one doesn't belong here. IrOverridingUtil shouldn't know about symbol table.
    fun redelegateFakeOverride(fakeOverride: IrOverridableMember)
    // TODO: need to make IrProperty carry overriddenSymbols.
    val propertyOverriddenSymbols: MutableMap<IrOverridableMember, List<IrSymbol>>
}

// TODO:
// The below pile of code is basically half of OverridingUtil.java
// adapted to IR and converted to Kotlin.
// Need to convert frontend's OverridingUtil to Kotlin and merge this codes
// to use abstract overridable member interfaces.

class IrOverridingUtil(val irBuiltIns: IrBuiltIns, val fakeOverrideBuilder: FakeOverrideBuilderStrategy) {
    private val doDebug = false
    private inline fun debug(any: Any) = if (doDebug) println(any) else {}

    private val originals = mutableMapOf<IrOverridableMember, IrOverridableMember>()
    private val IrOverridableMember.original get() = originals[this] ?: error("No original for ${this.render()}")
    private val originalSuperTypes = mutableMapOf<IrOverridableMember, IrType>()

    private var IrOverridableMember.overriddenSymbols: List<IrSymbol>
        get() = when (this) {
            is IrSimpleFunction -> this.overriddenSymbols
            is IrProperty -> fakeOverrideBuilder.propertyOverriddenSymbols[this]!!
            else -> error("Unexpected declaration for overriddenSymbols: $this")
        }
        set(value) {
            when (this) {
                is IrSimpleFunction -> this.overriddenSymbols = value as List<IrSimpleFunctionSymbol>
                is IrProperty -> {
                    fakeOverrideBuilder.propertyOverriddenSymbols.put(this, value)
                    this.getter!!.overriddenSymbols = value.map{(it.owner as IrProperty).getter!!.symbol}
                    this.setter?.let{  setter ->
                        setter.overriddenSymbols = value.map{(it.owner as IrProperty).setter?.symbol}.filterNotNull()
                    }
                }
                else -> error("Unexpected declaration for overriddenSymbols: $this")
            }
        }


    fun buildFakeOverridesForClass(clazz: IrClass) {
        debug("\n\nBUILDING fake overrides (2) for ${clazz.render()}:")

        val superTypes = clazz.superTypes

        val fromCurrent = clazz.declarations
            .filterIsInstance<IrOverridableMember>()
            .filter { (it as IrSymbolOwner).symbol.isPublicApi }

        debug("\nDESERIALIZED members:\n\t${fromCurrent.map {it.render()}.joinToString("\n\t")}")

        val allFromSuper = superTypes.flatMap { superType ->
            val superClass = superType.classifierOrNull?.owner as IrClass? // TODO: What if there's no class?
            superClass!!.declarations
                .filterIsInstance<IrOverridableMember>()
                .filter { (it as IrSymbolOwner).symbol.isPublicApi }
                // HEre we try to depart from descriptor counterpart algoritm making the substitution right here.
                .map{
                    val fakeOverride = fakeOverrideBuilder.fakeOverrideMember(superType, it, clazz)
                    originals.put(fakeOverride, it as IrOverridableMember)
                    originalSuperTypes.put(fakeOverride, superType)
                    fakeOverride
                }
        }

        val allFromSuperByName = allFromSuper.groupBy { it.name }

        allFromSuperByName.forEach { group ->
            generateOverridesInFunctionGroup(
                group.key,
                group.value,
                fromCurrent.filter {it.name == group.key},
                clazz
            )
        }
    }

    fun generateOverridesInFunctionGroup(
        name: Name,
        membersFromSupertypes: List<IrOverridableMember>,
        membersFromCurrent: List<IrOverridableMember>,
        current: IrClass
    ) {
        val notOverridden = membersFromSupertypes.toMutableSet()

        for (fromCurrent in membersFromCurrent) {
            val bound = extractAndBindOverridesForMember(fromCurrent, membersFromSupertypes, current)
            bound?.let { notOverridden.removeAll(it) }
        }

        debug("GROUP ${name.toString()}")
        createAndBindFakeOverrides(current, notOverridden)
    }

    private fun extractAndBindOverridesForMember(
        fromCurrent: IrOverridableMember,
        descriptorsFromSuper: Collection<IrOverridableMember>,
        current: IrClass
    ): Collection<IrOverridableMember>? {
        val bound = ArrayList<IrOverridableMember>(descriptorsFromSuper.size)
        val overridden = mutableSetOf<IrOverridableMember>()
        for (fromSupertype in descriptorsFromSuper) {
            val result = isOverridableBy(fromSupertype, fromCurrent/*, current*/).getResult()
            val isVisibleForOverride = isVisibleForOverride(fromCurrent, fromSupertype)
            when (result) {
                OverrideCompatibilityInfo.Result.OVERRIDABLE -> {
                    if (isVisibleForOverride) {
                        overridden.add(fromSupertype)
                    }
                    bound.add(fromSupertype)
                }
                OverrideCompatibilityInfo.Result.CONFLICT -> {
                    // if (isVisibleForOverride) {
                    //     strategy.overrideConflict(fromSupertype, fromCurrent)
                    // }

                    // Do nothing.
                    bound.add(fromSupertype)
                }
                OverrideCompatibilityInfo.Result.INCOMPATIBLE -> {
                }
            }
        }
        //strategy.setOverriddenDescriptors(fromCurrent, overridden)
        fromCurrent.overriddenSymbols = overridden.map{it.original}.map { if (it is IrProperty) it.symbol else (it as IrSymbolDeclaration<*>).symbol }

        return bound
    }

    private fun createAndBindFakeOverrides(
        current: IrClass,
        notOverridden: Collection<IrOverridableMember>
    ) {
        val fromSuper = notOverridden.toMutableSet()
        while (!fromSuper.isEmpty()) {
            val notOverriddenFromSuper = findMemberWithMaxVisibility(fromSuper)
            val overridables = extractMembersOverridableInBothWays(
                    notOverriddenFromSuper,
                    fromSuper
            )
            createAndBindFakeOverride(overridables, current)
        }
    }

    private fun filterVisibleFakeOverrides(
        current: IrClass,
        toFilter: Collection<IrOverridableMember>
    ): Collection<IrOverridableMember> {
        return toFilter.filter { member: IrOverridableMember ->
            !Visibilities.isPrivate(member.visibility) && isVisibleIgnoringReceiver(member, current)
        }
    }

    private fun determineModalityForFakeOverride(
        members: Collection<IrOverridableMember>,
        current: IrClass
    ): Modality {
        // Optimization: avoid creating hash sets in frequent cases when modality can be computed trivially
        var hasOpen = false
        var hasAbstract = false
        for (member in members) {
            when (member.modality) {
                Modality.FINAL -> return Modality.FINAL
                Modality.SEALED -> throw IllegalStateException("Member cannot have SEALED modality: $member")
                Modality.OPEN -> hasOpen = true
                Modality.ABSTRACT -> hasAbstract = true
            }
        }

        // Fake overrides of abstract members in non-abstract expected classes should not be abstract, because otherwise it would be
        // impossible to inherit a non-expected class from that expected class in common code.
        // We're making their modality that of the containing class, because this is the least confusing behavior for the users.
        // However, it may cause problems if we reuse resolution results of common code when compiling platform code (see KT-15220)
        val transformAbstractToClassModality =
            current.isExpect && current.modality !== Modality.ABSTRACT && current.modality !== Modality.SEALED
        if (hasOpen && !hasAbstract) {
            return Modality.OPEN
        }
        if (!hasOpen && hasAbstract) {
            return if (transformAbstractToClassModality) current.modality else Modality.ABSTRACT
        }
        return getMinimalModality(members, transformAbstractToClassModality, current.modality)
    }

    fun areEquivalent(a: IrOverridableMember, b: IrOverridableMember) = (a == b)

    fun overrides(f: IrOverridableMember, g: IrOverridableMember): Boolean {

        if (f != g && areEquivalent(f.original, g.original)) return true

        for (overriddenFunction in getOverriddenDeclarations(f)) {
            if (areEquivalent(g.original, overriddenFunction)) return true
        }
        return false
    }

    fun filterOverrides(
        candidateSet: Set<IrOverridableMember>
    ): Set<IrOverridableMember> {
        if (candidateSet.size <= 1) return candidateSet
        val result = mutableSetOf<IrOverridableMember>()
        outerLoop@ for (me in candidateSet) {
            val iterator = result.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (overrides(me, other)) {
                    iterator.remove()
                } else if (overrides(other, me)) {
                    continue@outerLoop
                }
            }
            result.add(me)
        }
        assert(!result.isEmpty()) { "All candidates filtered out from $candidateSet" }
        return result
    }


    fun getOverriddenDeclarations(member: IrOverridableMember): Set<IrOverridableMember> {
        val result = mutableSetOf<IrOverridableMember>()
        collectOverriddenDeclarations(member.original, result)
        return result
    }

    private fun collectOverriddenDeclarations(
        member: IrOverridableMember,
        result: MutableSet<IrOverridableMember>
    ) {
        if (member.isReal) {
            result.add(member)
        } else {
            check(!member.overriddenSymbols.isEmpty()) { "No overridden descriptors found for (fake override) $member" }
            for (overridden in member.original.overriddenSymbols.map{it.owner as IrOverridableMember}) {
                val original = overridden.original
                collectOverriddenDeclarations(original, result)
                result.add(original)
            }
        }
    }

    private fun getMinimalModality(
        descriptors: Collection<IrOverridableMember>,
        transformAbstractToClassModality: Boolean,
        classModality: Modality
    ): Modality {
        var result = Modality.ABSTRACT
        for (descriptor in descriptors) {
            val effectiveModality =
                if (transformAbstractToClassModality && descriptor.modality === Modality.ABSTRACT) classModality else descriptor.modality
            if (effectiveModality.compareTo(result) < 0) {
                result = effectiveModality
            }
        }
        return result
    }

    private fun createAndBindFakeOverride(
        overridables: Collection<IrOverridableMember>,
        current: IrClass
    ) {

        debug("BIND overridables:\n\t")
        overridables.forEach{
            debug("\t${it.render()}")
        }

        val visibleOverridables = filterVisibleFakeOverrides(current, overridables)
        val allInvisible = visibleOverridables.isEmpty()
        val effectiveOverridden = if (allInvisible) overridables else visibleOverridables
        val modality = determineModalityForFakeOverride(effectiveOverridden, current)
        val visibility = findMemberWithMaxVisibility(effectiveOverridden).visibility
        val mostSpecific = selectMostSpecificMember(effectiveOverridden)

        debug("UPDATE modality: $modality visibility: $visibility" )
        val fakeOverride = fakeOverrideBuilder.fakeOverrideMember(
            originalSuperTypes[mostSpecific]!!,
            mostSpecific.original,
            current,
            modality,
            visibility
        )

        fakeOverride.overriddenSymbols = effectiveOverridden.map {it.original}. map {  if (it is IrProperty) it.symbol else (it as IrSymbolDeclaration<*>).symbol }
        assert(
            !fakeOverride.overriddenSymbols.isEmpty()
        ) { "Overridden symbols should be set for " + CallableMemberDescriptor.Kind.FAKE_OVERRIDE }

        fakeOverrideBuilder.redelegateFakeOverride(fakeOverride)
        debug("SYNTHESIZED: ${ir2stringWhole(fakeOverride)}")
        current.declarations.add(fakeOverride)
    }

    private fun isVisibilityMoreSpecific(
        a: IrOverridableMember,
        b: IrOverridableMember
    ): Boolean {
        val result =
            Visibilities.compare(a.visibility, b.visibility)
        return result == null || result >= 0
    }

    private fun isAccessorMoreSpecific(
        a: IrSimpleFunction?,
        b: IrSimpleFunction?
    ): Boolean {
        return if (a == null || b == null) true else isVisibilityMoreSpecific(a, b)
    }

    fun IrTypeCheckerContextWithAdditionalAxioms.isSubtypeOf(a: IrType, b: IrType) =
        AbstractTypeChecker.isSubtypeOf(this as AbstractTypeCheckerContext, a, b)

    fun IrTypeCheckerContextWithAdditionalAxioms.equalTypes(a: IrType, b: IrType) =
        AbstractTypeChecker.equalTypes(this as AbstractTypeCheckerContext, a, b)

    fun createTypeChecker(a: List<IrTypeParameter>, b: List<IrTypeParameter>) =
        IrTypeCheckerContextWithAdditionalAxioms(irBuiltIns, a, b)

    private fun isReturnTypeMoreSpecific(
        a: IrOverridableMember,
        aReturnType: IrType,
        b: IrOverridableMember,
        bReturnType: IrType
    ): Boolean {
        val typeCheckerContext = createTypeChecker(a.typeParameters, b.typeParameters)
        return typeCheckerContext.isSubtypeOf(aReturnType, bReturnType)
    }

    fun isMoreSpecific(
        a: IrOverridableMember,
        b: IrOverridableMember
    ): Boolean {
        val aReturnType = a.returnType
        val bReturnType = b.returnType
        if (!isVisibilityMoreSpecific(a, b)) return false
        if (a is IrSimpleFunction) {
            assert(b is IrSimpleFunction) { "b is " + b.javaClass }
            return isReturnTypeMoreSpecific(a, aReturnType, b, bReturnType)
        }
        if (a is IrProperty) {
            assert(b is IrProperty) { "b is " + b.javaClass }
            val pa = a
            val pb = b as IrProperty
            if (!isAccessorMoreSpecific(
                    pa.setter,
                    pb.setter
                )
            ) return false
            return if (pa.isVar && pb.isVar) {
                createTypeChecker(
                    a.getter!!.typeParameters,
                    b.getter!!.typeParameters
                ).equalTypes(aReturnType, bReturnType)
            } else {
                // both vals or var vs val: val can't be more specific then var
                !(!pa.isVar && pb.isVar) && isReturnTypeMoreSpecific(
                    a, aReturnType,
                    b, bReturnType
                )
            }
        }
        throw IllegalArgumentException("Unexpected callable: " + a.javaClass)
    }

    private fun isMoreSpecificThenAllOf(
        candidate: IrOverridableMember,
        descriptors: Collection<IrOverridableMember>
    ): Boolean {
        // NB subtyping relation in Kotlin is not transitive in presence of flexible types:
        //  String? <: String! <: String, but not String? <: String
        for (descriptor in descriptors) {
            if (!isMoreSpecific(candidate, descriptor)) {
                return false
            }
        }
        return true
    }

    fun selectMostSpecificMember(
        overridables: Collection<IrOverridableMember>
    ): IrOverridableMember {
        assert(!overridables.isEmpty()) { "Should have at least one overridable descriptor" }
        if (overridables.size == 1) {
            return overridables.first()
        }
        val candidates: MutableCollection<IrOverridableMember> = java.util.ArrayList(2)
        var transitivelyMostSpecific = overridables.first()
        val transitivelyMostSpecificDescriptor = transitivelyMostSpecific
        for (overridable in overridables) {
            if (isMoreSpecificThenAllOf(overridable, overridables)
            ) {
                candidates.add(overridable)
            }
            if (isMoreSpecific(
                    overridable,
                    transitivelyMostSpecificDescriptor
                )
                && !isMoreSpecific(
                    transitivelyMostSpecificDescriptor,
                    overridable
                )
            ) {
                transitivelyMostSpecific = overridable
            }
        }
        if (candidates.isEmpty()) {
            return transitivelyMostSpecific
        } else if (candidates.size == 1) {
            return candidates.first()
        }
        var firstNonFlexible: IrOverridableMember? = null
        for (candidate in candidates) {
            if (candidate.returnType !is IrDynamicType) {
                firstNonFlexible = candidate
                break
            }
        }
        return firstNonFlexible ?: candidates.first()
    }


    fun extractMembersOverridableInBothWays(
        overrider: IrOverridableMember,
        @Mutable extractFrom: MutableCollection<IrOverridableMember>
    ): Collection<IrOverridableMember> {
        val overridable = arrayListOf<IrOverridableMember>()
        overridable.add(overrider)
        val iterator = extractFrom.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (overrider === candidate) {
                iterator.remove()
                continue
            }
            val finalResult =
                getBothWaysOverridability(
                    overrider,
                    candidate
                )
            if (finalResult == OverrideCompatibilityInfo.Result.OVERRIDABLE) {
                overridable.add(candidate)
                iterator.remove()
            } else if (finalResult == OverrideCompatibilityInfo.Result.CONFLICT) {
                iterator.remove()
            }
        }
        return overridable
    }

    fun getBothWaysOverridability(
        overriderDescriptor: IrOverridableMember?,
        candidateDescriptor: IrOverridableMember?
    ): OverrideCompatibilityInfo.Result {
        val result1 = isOverridableBy(
            candidateDescriptor!!,
            overriderDescriptor!!
            //null
        ).result
        val result2 = isOverridableBy(
            overriderDescriptor,
            candidateDescriptor
            //null
        ).result
        return if (result1 == OverrideCompatibilityInfo.Result.OVERRIDABLE && result2 == OverrideCompatibilityInfo.Result.OVERRIDABLE)
            OverrideCompatibilityInfo.Result.OVERRIDABLE
        else if (result1 == OverrideCompatibilityInfo.Result.CONFLICT || result2 == OverrideCompatibilityInfo.Result.CONFLICT)
            OverrideCompatibilityInfo.Result.CONFLICT
        else
            OverrideCompatibilityInfo.Result.INCOMPATIBLE
    }

    fun isOverridableBy(
        superMember: IrOverridableMember,
        subMember: IrOverridableMember,
        // subClass: IrClass?
    ): OverrideCompatibilityInfo {
        return isOverridableBy(superMember, subMember/*, subClass*/, false)
    }

    fun isOverridableBy(
        superMember: IrOverridableMember,
        subMember: IrOverridableMember,
        // subClass: IrClass?, Would only be needed for external overridability conditions.
        checkReturnType: Boolean
    ): OverrideCompatibilityInfo {
        val basicResult = isOverridableByWithoutExternalConditions(superMember, subMember, checkReturnType)
        return if (basicResult.result == OverrideCompatibilityInfo.Result.OVERRIDABLE)
            OverrideCompatibilityInfo.success()
        else
            basicResult
        // The frontend goes into external overridability condition details here, but don't deal with them in IR (yet?).
    }

    private val IrOverridableMember.compiledValueParameters get() = when(this) {
        is IrSimpleFunction -> extensionReceiverParameter?.let { listOf(it) + valueParameters } ?: valueParameters
        is IrProperty -> getter!!.extensionReceiverParameter?.let { listOf(it) } ?: emptyList()
        else -> error("Unexpected declaration for compiledValueParameters: $this")
    }

    private val IrOverridableMember.returnType get() = when(this) {
        is IrSimpleFunction -> this.returnType
        is IrProperty -> this.getter!!.returnType
        else -> error("Unexpected declaration for returnType: $this")
    }

    private val IrOverridableMember.typeParameters get() = when(this) {
        is IrSimpleFunction -> this.typeParameters
        is IrProperty -> this.getter!!.typeParameters
        else -> error("Unexpected declaration for typeParameters: $this")
    }

    fun isOverridableByWithoutExternalConditions(
        superMember: IrOverridableMember,
        subMember: IrOverridableMember,
        checkReturnType: Boolean
    ): OverrideCompatibilityInfo {
        val basicOverridability = getBasicOverridabilityProblem(superMember, subMember)
        if (basicOverridability != null) return basicOverridability

        val superValueParameters = superMember.compiledValueParameters
        val subValueParameters = subMember.compiledValueParameters
        val superTypeParameters = superMember.typeParameters
        val subTypeParameters = subMember.typeParameters

        val defaultTypeCheckerContext = IrTypeCheckerContext(irBuiltIns)
        if (superTypeParameters.size != subTypeParameters.size) {
            /* TODO: do we need this in IR?
            superValueParameters.forEachIndexed { index, superParameter ->
                if (!AbstractTypeChecker.equalTypes(
                        defaultTypeCheckerContext as AbstractTypeCheckerContext,
                        superParameter.type,
                        subValueParameters[index].type
                    )
                ) {
                    return OverrideCompatibilityInfo.incompatible("Type parameter number mismatch")
                }
            }
            return OverrideCompatibilityInfo.conflict("Type parameter number mismatch")
            */

            return OverrideCompatibilityInfo.incompatible("Type parameter number mismatch")
        }

        val typeCheckerContext =
            IrTypeCheckerContextWithAdditionalAxioms(irBuiltIns, superTypeParameters, subTypeParameters)

        /* TODO: check the bounds. See OverridingUtil.areTypeParametersEquivalent()
        superTypeParameters.forEachIndexed { index, parameter ->
            if (!AbstractTypeChecker.areTypeParametersEquivalent(
                    typeCheckerContext as AbstractTypeCheckerContext,
                    subTypeParameters[index].type,
                    parameter.type
                )
            ) return OverrideCompatibilityInfo.incompatible("Type parameter bounds mismatch")
        }
        */

        assert(superValueParameters.size == subValueParameters.size)

        superValueParameters.forEachIndexed { index, parameter ->
            if (!AbstractTypeChecker.equalTypes(
                    typeCheckerContext as AbstractTypeCheckerContext,
                    subValueParameters[index].type,
                    parameter.type
                )
            ) return OverrideCompatibilityInfo.incompatible("Value parameter type mismatch")
        }

        if (superMember is IrSimpleFunction && subMember is IrSimpleFunction && superMember.isSuspend != subMember.isSuspend) {
            return OverrideCompatibilityInfo.conflict("Incompatible suspendability")
        }

        if (checkReturnType) {
            if (!AbstractTypeChecker.isSubtypeOf(
                    typeCheckerContext as AbstractTypeCheckerContext,
                    subMember.returnType,
                    superMember.returnType
                )
            ) return OverrideCompatibilityInfo.conflict("Return type mismatch")
        }
        return OverrideCompatibilityInfo.success()
    }

    fun getBasicOverridabilityProblem(
        superMember: IrOverridableMember,
        subMember: IrOverridableMember
    ): OverrideCompatibilityInfo? {
        if (superMember is IrSimpleFunction && subMember !is IrSimpleFunction ||
            superMember is IrProperty && subMember !is IrProperty
        ) {
            return incompatible("Member kind mismatch")
        }
        require((superMember is IrSimpleFunction || superMember is IrProperty)) {
            "This type of IrDeclaration cannot be checked for overridability: $superMember"
        }

        return if (superMember.name != subMember.name) {
            incompatible("Name mismatch")
        } else
            checkReceiverAndParameterCount(superMember, subMember)
    }

    private fun checkReceiverAndParameterCount(
        superMember: IrOverridableMember,
        subMember: IrOverridableMember
    ): OverrideCompatibilityInfo? {
        return when (superMember) {
            is IrSimpleFunction -> {
                require(subMember is IrSimpleFunction)
                if (superMember.extensionReceiverParameter == null != (subMember.extensionReceiverParameter == null)) {
                    incompatible("Receiver presence mismatch")
                } else if (superMember.valueParameters.size != subMember.valueParameters.size) {
                    incompatible("Value parameter number mismatch")
                } else null
            }
            is IrProperty -> {
                require(subMember is IrProperty)
                if (superMember.getter?.extensionReceiverParameter == null != (subMember.getter?.extensionReceiverParameter == null)) {
                    incompatible("Receiver presence mismatch")
                } else null
            }
            else -> error("Unxpected declaration for value parameter check: $this")
        }
    }
}