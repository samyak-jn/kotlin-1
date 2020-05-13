package org.jetbrains.kotlin.backend.common.serialization

import org.jetbrains.kotlin.backend.common.serialization.mangle.descriptor.DescriptorBasedKotlinManglerImpl
import org.jetbrains.kotlin.backend.common.serialization.mangle.ir.IrBasedKotlinManglerImpl
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.findClassAcrossModuleDependencies
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrOverridableMember
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.module
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered

class FakeOverrideChecker(val irMangler: IrBasedKotlinManglerImpl, val descriptorMangler: DescriptorBasedKotlinManglerImpl) {

    private fun validateFakeOverrides(clazz: IrClass) {
        val classId = clazz.classId ?: return
        val classDescriptor = clazz.module.module.findClassAcrossModuleDependencies(classId) ?: return
        // All enum entry overrides look like fake overrides in descriptor enum entries
        if (classDescriptor.kind == ClassKind.ENUM_ENTRY) return

        val descriptorFakeOverrides = classDescriptor.unsubstitutedMemberScope
            .getDescriptorsFiltered(DescriptorKindFilter.CALLABLES)
            .filterIsInstance<CallableMemberDescriptor>()
            .filter { it.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE }
            .filterNot { it.visibility == Visibilities.PRIVATE || it.visibility == Visibilities.INVISIBLE_FAKE }

        val descriptorSignatures = descriptorFakeOverrides
            .map { with(descriptorMangler) { it.mangleString }}
            .sorted()

        val irFakeOverrides = clazz.declarations
            .filterIsInstance<IrOverridableMember>()
            .filter { it.isFakeOverride }

        val irSignarures = irFakeOverrides
            .map { with(irMangler) { it.mangleString }}
            .sorted()

        if(descriptorSignatures != irSignarures) {
            classDescriptor.module
            println("[IR VALIDATION] Fake override mismatch for ${clazz.fqNameWhenAvailable!!}")
            println("Descriptor based: $descriptorSignatures")
            println("IR based        : $irSignarures")
            println(descriptorFakeOverrides)
            println(irFakeOverrides)
            assert(true) {
                "Descriptor based: $descriptorSignatures\nIR based        : $irSignarures"
            }
        }
    }

    fun check(module: IrModuleFragment) {
        module.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }
            override fun visitClass(declaration: IrClass) {
                validateFakeOverrides(declaration)
                super.visitClass(declaration)
            }
            override fun visitFunction(declaration: IrFunction) {
                // Don't go for function local classes
                return
            }
        })
    }
}