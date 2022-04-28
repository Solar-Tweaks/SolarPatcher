/*
 * Solar Patcher, a runtime patcher for Lunar Client
 * Copyright (C) 2022 Solar Tweaks and respective contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.grappenmaker.solarpatcher.modules

import com.grappenmaker.solarpatcher.asm.asDescription
import com.grappenmaker.solarpatcher.asm.hasConstant
import com.grappenmaker.solarpatcher.asm.matching.asMatcher
import com.grappenmaker.solarpatcher.asm.method.InvocationType
import com.grappenmaker.solarpatcher.asm.transform.ClassTransform
import com.grappenmaker.solarpatcher.asm.util.*
import com.grappenmaker.solarpatcher.util.generation.Accessors
import com.grappenmaker.solarpatcher.util.generation.createAccountNameMod
import com.grappenmaker.solarpatcher.util.generation.createTextMod
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode

// TextMod class for user configurable custom mods
@Serializable
data class TextMod(val name: String, val text: String)

val TextMod.id get() = "${name.lowercase()}-custom"

// Mod class for keeping track of mod data
class Mod(val id: String, val displayName: String, val loader: MethodVisitor.() -> Unit)

// Joined module that allows creation of custom mods
@Serializable
data class CustomMods(val textMods: List<TextMod> = listOf()) :
    JoinedModule(listOf(ModRegistry(modLoaders(textMods)), LangMapper(langMap(textMods)))) {
    @Transient
    override val isEnabled: Boolean = true
}

// Simple module that handles mod registration
class ModRegistry(private val loaders: List<MethodVisitor.() -> Unit>) : Module() {
    override val isEnabled = true
    override fun generate(node: ClassNode): ClassTransform? {
        if (node.methods.none { m ->
                Type.getReturnType(m.desc).internalName == internalString
                        && m.hasConstant("mods.json")
            }) return null

        val method = node.methods.find { Type.getReturnType(it.desc).internalName == "java/util/Set" } ?: return null
        return ClassTransform(visitors = listOf { parent ->
            AdviceClassVisitor(
                parent,
                method.asDescription(node).asMatcher(),
                exitAdvice = {
                    loaders.forEach {
                        dup()
                        it()
                        invokeMethod(InvocationType.INTERFACE, "add", "(Ljava/lang/Object;)Z", "java/util/Set")
                        pop()
                    }
                }
            )
        }, shouldExpand = true)
    }
}

// Simple language mapper for the custom mods
class LangMapper(private val mapping: Map<String, String>) : Module() {
    override val isEnabled = true
    override fun generate(node: ClassNode): ClassTransform? {
        if (mapping.isEmpty()) return null
        if (!node.hasConstant("language.json")) return null
        val method =
            node.methods.find { it.desc == "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;" }
                ?: return null

        return ClassTransform(visitors = listOf { parent ->
            AdviceClassVisitor(parent, method.asDescription(node).asMatcher(), enterAdvice = {
                val noMatch = Label()

                loadVariable(1)
                visitLdcInsn("features.")
                invokeMethod(String::class.java.getMethod("startsWith", String::class.java))
                visitJumpInsn(IFEQ, noMatch) // If we arent requesting feature details, exit early

                // Make sure we are actually requesting the name
                loadVariable(2)
                visitLdcInsn("name")
                invokeMethod(String::equals)
                visitJumpInsn(IFEQ, noMatch) // exit early when not looking for the name

                // Get the id this is about
                loadVariable(1)
                visitLdcInsn("\\.")
                invokeMethod(String::class.java.getMethod("split", String::class.java))

                // Array on stack
                visitIntInsn(BIPUSH, 1)
                visitInsn(AALOAD)

                // ID on stack
                invokeMethod(String::hashCode) // Get hashcode to match on

                // create switch statement
                createLookupSwitch(noMatch, mapping.mapKeys { (k) -> k.hashCode() }.mapValues { (_, v) ->
                    {
                        visitLdcInsn(v)
                        returnMethod(ARETURN)
                    }
                })
                visitLabel(noMatch) // If we aren't supposed to handle the language, let it go
            })
        }, shouldExpand = true)
    }
}

// Custom mod that shows your account name
private val nameMod = Mod("account-name", "Account Name") {
    visitLdcInsn("account-name")
    invokeMethod(::createAccountNameMod)
}

// List of custom mods
private val customMods = listOf(nameMod)

// Load custom mods from solarMods directory
private fun loadCustomMods(): List<Mod> {
    val gameDir = Accessors.Utility.getMCDataDir()

    println(gameDir)
    return listOf()
}

// Utility method for providing mod loaders
private fun modLoaders(textMods: List<TextMod>): List<MethodVisitor.() -> Unit> {
    return customMods.map { it.loader } + loadCustomMods().map { it.loader } + textMods.map {
        {
            visitLdcInsn(it.id)
            visitLdcInsn(it.text)
            invokeMethod(::createTextMod)
        }
    }
}

// Utility method to get the language map
private fun langMap(textMods: List<TextMod>) =
    customMods.associate { it.id to it.displayName } + textMods.associate { it.id to it.name }