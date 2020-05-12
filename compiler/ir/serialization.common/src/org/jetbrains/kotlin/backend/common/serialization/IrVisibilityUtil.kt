package org.jetbrains.kotlin.backend.common.serialization

import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithVisibility
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrOverridableMember
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue

// The contents of this file is from VisibilityUtil.kt adapted to IR.
// TODO: The code would better be commonized for descriptors, ir and fir.

private fun findInvisibleMember(
    receiver: ReceiverValue?,
    what: IrDeclarationWithVisibility,
    from: IrDeclaration
): DeclarationDescriptorWithVisibility? {
    // TODO: We need that for IR.
    // But that requires many of Visibility available in IR.
    return null
}

fun isVisibleIgnoringReceiver(
    what: IrDeclarationWithVisibility,
    from: IrDeclaration
): Boolean {
    return findInvisibleMember(Visibilities.ALWAYS_SUITABLE_RECEIVER, what, from) == null
}

fun isVisibleForOverride(
    overriding: IrOverridableMember,
    fromSuper: IrOverridableMember
): Boolean {
    return !Visibilities.isPrivate((fromSuper as IrDeclarationWithVisibility).visibility) &&
            isVisibleIgnoringReceiver(fromSuper, overriding)
}

fun findMemberWithMaxVisibility(members: Collection<IrOverridableMember>): IrOverridableMember {
    assert(members.isNotEmpty())

    var member: IrOverridableMember? = null
    for (candidate in members) {
        if (member == null) {
            member = candidate
            continue
        }

        val result = Visibilities.compare(member.visibility, candidate.visibility)
        if (result != null && result < 0) {
            member = candidate
        }
    }
    return member!!
}
