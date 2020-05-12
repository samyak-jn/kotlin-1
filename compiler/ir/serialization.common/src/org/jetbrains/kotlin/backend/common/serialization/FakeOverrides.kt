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

import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureSerializer
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered

interface PlatformFakeOverrideClassFilter {
    fun constructFakeOverrides(clazz: IrClass): Boolean = true
}
object DefaultFakeOverrideClassFilter : PlatformFakeOverrideClassFilter

object FakeOverrideControl {
    // If set to true: all fake overrides go to klib serialized IR.
    // If set to false: eligible fake overrides are not serialized.
    val serializeFakeOverrides: Boolean = false
    // If set to true: fake overrides are deserialized from klib serialized IR.
    // If set to false: eligible fake overrides are constructed within IR linker.
    val deserializeFakeOverrides: Boolean = false
}

class FakeOverrideBuilder(
    val symbolTable: SymbolTable,
    val signaturer: IdSignatureSerializer,
    val irBuiltIns: IrBuiltIns,
    val platformSpecificClassFilter: PlatformFakeOverrideClassFilter = DefaultFakeOverrideClassFilter
) : AbstractFakeOverrideBuilder, FakeOverrideBuilderStrategy
{
    private val doDebug = false
    private inline fun debug(any: Any) = if (doDebug) println(any) else {}

    private val haveFakeOverrides = mutableSetOf<IrClass>()
    override val propertyOverriddenSymbols = mutableMapOf<IrOverridableMember, List<IrSymbol>>()
    private val irOverridingUtil = IrOverridingUtil(irBuiltIns, this)

    override fun fakeOverrideMember(superType: IrType, member: IrOverridableMember, clazz: IrClass, newModality: Modality?, newVisibility: Visibility?): IrOverridableMember {
        if (superType !is IrSimpleType) error("superType is $superType, expected IrSimpleType")
        val classifier = superType.classifier
        if (classifier !is IrClassSymbol) error("superType classifier is not IrClassSymbol: ${classifier}")

        val typeParameters = classifier.owner.typeParameters.map { it.symbol }
        val typeArguments = superType.arguments.map { it as IrSimpleType } // TODO: the cast should not be here

        assert(typeParameters.size == typeArguments.size) {
            "typeParameters = $typeParameters size != typeArguments = $typeArguments size "
        }

        val substitutionMap = typeParameters.zip(typeArguments).toMap()
        val copier = DeepCopyIrTreeWithSymbolsForFakeOverrides(substitutionMap, superType, clazz, newModality, newVisibility)

        val deepCopyFakeOverride = copier.copy(member) as IrOverridableMember
        deepCopyFakeOverride.parent = clazz
        assert(deepCopyFakeOverride.symbol.owner == deepCopyFakeOverride)
        assert((deepCopyFakeOverride.symbol.descriptor as? WrappedDeclarationDescriptor<*>)?.owner == deepCopyFakeOverride)

        return deepCopyFakeOverride as IrOverridableMember
    }

    private val IrDeclaration.modality get() = when(this) {
        is IrProperty -> this.modality
        is IrSimpleFunction -> this.modality
        else -> error("Could not take modality of ${this.render()}")
    }

    fun buildFakeOverrideChainsForClass(clazz: IrClass) {
        if (haveFakeOverrides.contains(clazz)) return
        if (!platformSpecificClassFilter.constructFakeOverrides(clazz) || !clazz.symbol.isPublicApi) return

        val superTypes = clazz.superTypes

        val superClasses = superTypes.map {
            it.classifierOrNull?.owner as IrClass?
        }.filterNotNull()

        superClasses.forEach {
            buildFakeOverrideChainsForClass(it)
            haveFakeOverrides.add(it)
        }

        irOverridingUtil.buildFakeOverridesForClass(clazz)
    }

    override fun buildFakeOverridesForClass(clazz: IrClass) = irOverridingUtil.buildFakeOverridesForClass(clazz)

    override fun redelegateFakeOverride(fake: IrOverridableMember) {
        when (fake) {
            is IrSimpleFunction -> {
                redelegateFunction(fake)
                checkOverriddenSymbols(fake)
            }
            is IrProperty -> redelegateProperty(fake)
        }
    }

    private fun redelegateFakeOverrides(fakeOverrides: List<IrOverridableMember>) =
        fakeOverrides.forEach { redelegateFakeOverride(it) }

    private fun redelegateFunction(declaration: IrSimpleFunction) {
        val signature = signaturer.composePublicIdSignature(declaration)

        val existingSymbol =
            symbolTable.referenceSimpleFunctionFromLinker(WrappedSimpleFunctionDescriptor(), signature)

        (declaration.symbol as? IrDelegatingSimpleFunctionSymbolImpl)?.let {
            debug("REDELEGATING ${declaration.nameForIrSerialization} to $existingSymbol")
            it.delegate = existingSymbol
        } ?: error("Expected a delegating symbol in ${declaration.render()} ${declaration.symbol}")

        existingSymbol.bind(declaration)
        symbolTable.rebindSimpleFunction(signature, declaration)

        (existingSymbol.descriptor as? WrappedSimpleFunctionDescriptor)?.let {
            if (!it.isBound()) it.bind(declaration)
        }
    }

    private fun redelegateProperty(declaration: IrProperty) {
        val signature = signaturer.composePublicIdSignature(declaration)

        val existingSymbol =
            symbolTable.referencePropertyFromLinker(WrappedPropertyDescriptor(), signature)

        (declaration.symbol as? IrDelegatingPropertySymbolImpl)?.let {
            debug("REDELEGATING ${declaration.nameForIrSerialization} to $existingSymbol")
            it.delegate = existingSymbol
        } ?: error("Expected a delegating symbol in ${declaration.render()} ${declaration.symbol}")

        existingSymbol.bind(declaration)
        symbolTable.rebindProperty(signature, declaration)

        (existingSymbol.descriptor as? WrappedPropertyDescriptor)?.let {
            if (!it.isBound()) it.bind(declaration)
        }

        declaration.getter?.let { redelegateFunction(it) }
        declaration.setter?.let { redelegateFunction(it) }
    }

    fun checkOverriddenSymbols(fake: IrSimpleFunction) {
        fake.overriddenSymbols.forEach { symbol ->
            if(!(symbol.owner.parent as IrClass).declarations.contains(symbol.owner)) {
                debug("CHECK overridden symbols: ${fake.render()} refers to ${symbol.owner.render()} which is not a member of ${symbol.owner.parent.render()}")
            }
        }
    }

    fun provideFakeOverrides(module: IrModuleFragment) {
        module.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }
            override fun visitClass(declaration: IrClass) {
                buildFakeOverrideChainsForClass(declaration)
                haveFakeOverrides.add(declaration)
                super.visitClass(declaration)
            }
            override fun visitFunction(declaration: IrFunction) {
                // Don't go for function local classes
                return
            }
        })

    }
}
