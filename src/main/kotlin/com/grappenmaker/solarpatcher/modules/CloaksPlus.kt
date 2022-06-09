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

import com.grappenmaker.solarpatcher.asm.API
import com.grappenmaker.solarpatcher.asm.asDescription
import com.grappenmaker.solarpatcher.asm.calls
import com.grappenmaker.solarpatcher.asm.isInterface
import com.grappenmaker.solarpatcher.asm.matching.MethodMatching.matchName
import com.grappenmaker.solarpatcher.asm.matching.asMatcher
import com.grappenmaker.solarpatcher.asm.transform.*
import com.grappenmaker.solarpatcher.asm.util.*
import com.grappenmaker.solarpatcher.util.generation.Accessors
import com.grappenmaker.solarpatcher.util.generation.IConfigDelegate
import com.grappenmaker.solarpatcher.util.generation.Instances
import kotlinx.serialization.Serializable
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode

const val capeServer = "http://server.cloaksplus.com"

@Serializable
data class CloaksPlus(override val isEnabled: Boolean = true) :
    JoinedModule(listOf(ChangeURL, ImplementConfigFetcher, ImplementSkinLayer, CapeServerURL))

private object ChangeURL : Module(), TransformGenerator by matcherGenerator(
    ClassTransform(ConstantValueTransform(matchName("getPlayerItemsUrl"), capeServer)),
    matcher = { it.name.startsWith("net/optifine/") && it.name.endsWith("HttpUtils") }
) {
    override val isEnabled = true
}

private object ImplementConfigFetcher : Module() {
    override val isEnabled = true
    override fun generate(node: ClassNode): ClassTransform? {
        if (!(node.name.startsWith("net/optifine/") && node.name.endsWith("PlayerConfigurations"))) return null

        val getConfigMethod = node.methods.find { it.name == "getPlayerConfiguration" } ?: return null
        if (getConfigMethod.calls(matchName("getPlayerItemsUrl"))) return null

        return ClassTransform(listOf(
            ImplementTransform(matchName("getPlayerConfiguration")) {
                getObject<Accessors.ConfigDelegate>()
                loadVariable(0)
                invokeMethod(IConfigDelegate::getPlayerConfig)
                visitTypeInsn(CHECKCAST, Type.getReturnType(getConfigMethod.desc).internalName)
                returnMethod(ARETURN)
            },
            ImplementTransform(matchName("setPlayerConfiguration")) {
                getObject<Accessors.ConfigDelegate>()
                loadVariable(0)
                loadVariable(1)
                invokeMethod(IConfigDelegate::setPlayerConfig)
                returnMethod(RETURN)
            }
        ))
    }
}

private object ImplementSkinLayer : Module() {
    override val isEnabled = true
    override fun generate(node: ClassNode): ClassTransform? {
        if (node.isInterface) return null

        val addLayerMethod = "bridge\$addLayer"
        val initMethod = node.methods.find { m -> m.calls.any { it.name == addLayerMethod } } ?: return null

        return ClassTransform(VisitorTransform(initMethod.asDescription(node).asMatcher()) { parent ->
            object : MethodVisitor(API, parent) {
                private var hasVisited = false

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean
                ) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)

                    if (name == addLayerMethod && !hasVisited) {
                        val label = Label()
                        invokeMethod(::shouldImplementItems)
                        visitJumpInsn(IFEQ, label)

                        hasVisited = true
                        loadVariable(3)
                        getObject<Instances>()
                        getProperty(Instances::playerItemsLayer)
                        visitInsn(ICONST_1)
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)

                        visitLabel(label)
                    }
                }
            }
        })
    }
}

private object CapeServerURL : TextTransformModule() {
    override val from = "http://s.optifine.net"
    override val to = capeServer
    override val isEnabled = true
}

object ConfigFetcher {
    val configs = mutableMapOf<String, Any>() // Player name to config
    fun getConfig(name: String?): Any? {
        if (name == null) return null
        if (!shouldImplementItems()) return null

        return configs.getOrPut(name) {
            println("Attempting to fetch configuration for $name")
            Accessors.ConfigDelegate.downloadPlayerConfig(name)
        }
    }

    fun setConfig(name: String?, value: Any?) {
        if (name == null || value == null) return
        configs[name] = value
    }
}

fun shouldImplementItems() = Accessors.ConfigDelegate.getVersion() in listOf("v1_12", "v1_8", "v1_7")