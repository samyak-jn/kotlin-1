/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.ir.util

import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.builtins.functions.FunctionClassDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.descriptors.WrappedDeclarationDescriptor
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult

class ExternalDependenciesGenerator(
    val symbolTable: SymbolTable,
    private val irProviders: List<IrProvider>,
    private val languageVersionSettings: LanguageVersionSettings
) {
    fun generateUnboundSymbolsAsDependencies() {
        if (languageVersionSettings.supportsFeature(LanguageFeature.NewInference)) {
            require(symbolTable.unboundTypeParameters.isEmpty()) {
                "Unbound type parameters are forbidden: ${symbolTable.unboundTypeParameters.map { it.descriptor }}"
            }
        }
        // There should be at most one DeclarationStubGenerator (none in closed world?)
        irProviders.singleOrNull { it is DeclarationStubGenerator }?.let {
            (it as DeclarationStubGenerator).unboundSymbolGeneration = true
        }
        /*
            Deserializing a reference may lead to new unbound references, so we loop until none are left.
         */
        var unbound = setOf<IrSymbol>()
        lateinit var prevUnbound: Set<IrSymbol>
        do {
            prevUnbound = unbound
            unbound = symbolTable.allUnbound(languageVersionSettings.supportsFeature(LanguageFeature.NewInference))

            for (symbol in unbound) {
                // Symbol could get bound as a side effect of deserializing other symbols.
                if (!symbol.isBound) {
                    irProviders.getDeclaration(symbol)
                }
                //assert(symbol.isBound) { "$symbol unbound even after deserialization attempt" }
            }
        // We wait for the unbound to stabilize on fake overrides.
        } while (/*unbound.isNotEmpty()*/unbound != prevUnbound)
    }
}

private fun SymbolTable.allUnbound(skipTypeParameters: Boolean): Set<IrSymbol> {
    val r = mutableSetOf<IrSymbol>()
    r.addAll(unboundClasses)
    r.addAll(unboundConstructors)
    r.addAll(unboundEnumEntries)
    r.addAll(unboundFields)
    r.addAll(unboundSimpleFunctions)
    r.addAll(unboundProperties)
    r.addAll(unboundTypeAliases)
    if (!skipTypeParameters) {
        r.addAll(unboundTypeParameters)
    }
    return r
}

fun SymbolTable.noUnboundLeft(message: String) {
    val unbound = this.allUnbound(false)
    assert(unbound.isEmpty()) {
        "$message\n" +
                unbound.map {
                    "$it ${if (it.isPublicApi) it.signature.toString() else "NON-PUBLIC API $it"}"
                }.joinToString("\n")
    }
}


fun List<IrProvider>.getDeclaration(symbol: IrSymbol): IrDeclaration? =
    firstNotNullResult { provider ->
        provider.getDeclaration(symbol)
    } //?: error("Could not find declaration for unbound symbol $symbol")

// In most cases, IrProviders list consist of an optional deserializer and a DeclarationStubGenerator.
fun generateTypicalIrProviderList(
    moduleDescriptor: ModuleDescriptor,
    irBuiltins: IrBuiltIns,
    symbolTable: SymbolTable,
    deserializer: IrDeserializer? = null,
    extensions: StubGeneratorExtensions = StubGeneratorExtensions.EMPTY
): List<IrProvider> {
    val stubGenerator = DeclarationStubGenerator(
        moduleDescriptor, symbolTable, irBuiltins.languageVersionSettings, extensions
    )
    return listOfNotNull(deserializer, stubGenerator).also {
        stubGenerator.setIrProviders(it)
    }
}
