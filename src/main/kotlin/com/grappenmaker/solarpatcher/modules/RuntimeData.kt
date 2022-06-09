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

import com.grappenmaker.solarpatcher.asm.*
import com.grappenmaker.solarpatcher.asm.matching.MethodMatching.matchName
import com.grappenmaker.solarpatcher.asm.method.*
import com.grappenmaker.solarpatcher.asm.transform.ClassTransform
import com.grappenmaker.solarpatcher.asm.util.getField
import com.grappenmaker.solarpatcher.asm.util.invokeMethod
import com.grappenmaker.solarpatcher.util.ensure
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.lang.reflect.Modifier
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

// Utility "module" to get runtime data
// Internally used to lookup classes and methods that can't be recognized with simple analysis
object RuntimeData : Module() {
    internal val finders = mutableListOf<Finder<*>>()
    private val containers = listOf(
        CriticalRuntimeData,
        Bridge,
        ModRuntime,
        SkinLayer,
        Chat,
        ServerMappings,
        OtherRuntimeData
    )

    init {
        println("Loaded ${finders.size} finders from ${containers.size} containers!")
    }

    @Transient
    override val isEnabled = true
    override fun generate(node: ClassNode): ClassTransform? {
        // Find the requested classes and methods
        finders.asSequence().filter { it.value == null }.forEach { finder ->
            when (finder) {
                is FindClass -> if (finder.matcher(node)) finder.value = node
                is FindMethod -> finder.value = node.methods.map { MethodInfo(node, it) }.find(finder.matcher)
                is FindField -> finder.value = node.fields.map { FieldInfo(node, it) }.find(finder.matcher)
            }
        }

        return null // Dont perform transformation
    }
}

object CriticalRuntimeData : FinderContainer {
    // "critical" runtime data goes here
    lateinit var version: String

    val lunarClientMain by +FindClass({ node ->
        (node.methods.first { it.name == "<clinit>" }.instructions.filterIsInstance<FieldInsnNode>()
            .find { it.name == "version" }?.previous as? LdcInsnNode)?.cst?.let { version = it as String }

        // Get assets socket field
        val call = node.methods.filter { it.constants.contains("Connected to the AssetServer") }
            .mapNotNull { it.calls.find { c -> c.name == "connect" } }.first()

        assetsSocketField = (call.previous as FieldInsnNode).asDescription()
    }) { it.hasConstant("Starting Lunar client...") }

    val getLunarmainMethod by findMethodFromClass({ lunarClientMain }) {
        Type.getReturnType(it.desc).internalName == lunarClientMain!!.name
                && Modifier.isStatic(it.access)
    }

    lateinit var clientInstance: FieldDescription
    lateinit var assetsSocketField: FieldDescription

    init {
        +FindMethod({ (_, method) ->
            val insn = method.instructions.filterIsInstance<FieldInsnNode>().first { it.opcode == PUTSTATIC }
            clientInstance = insn.asDescription()
        }) { it.method.hasConstant("Can't reset Minecraft Client instance!") }
    }
}

// bridge methods go here
object Bridge : FinderContainer {
    val playerEntityBridge: ClassNode get() = displayMessageMethod?.owner.ensure()
    val getPlayerMethod by +findBridgeMethod("bridge\$getPlayer")
    val getPlayerNameMethod by +findBridgeMethod("bridge\$getName") { (owner) -> owner.methods.any { it.name == "bridge\$getGameProfile" } }
    val getUUIDMethod by +findBridgeMethod("bridge\$getUniqueID")
    val isWindowFocusedMethod by +findBridgeMethod("bridge\$isWindowFocused")
    val getServerDataMethod by +findBridgeMethod("bridge\$getCurrentServerData")
    val getServerIPMethod by +findBridgeMethod("bridge\$serverIP")
    val displayMessageMethod by +findBridgeMethod("bridge\$addChatMessage")

    private fun findBridgeMethod(
        name: String,
        onFound: (MethodInfo) -> Unit = {},
        extraCondition: (MethodInfo) -> Boolean = { true }
    ) = FindMethod(onFound) { it.owner.isInterface && it.method.name == name && extraCondition(it) }
}

// Mod Related methods go here
object ModRuntime : FinderContainer {
    lateinit var positionField: FieldDescription

    val textModClass by +FindClass { node ->
        node.hasConstant("[\u0001]") && node.methods.any { m ->
            val args = Type.getArgumentTypes(m.desc)
            args.size == 4 && args[0].internalName.startsWith("lunar/") &&
                    args[1].internalName == internalString &&
                    args.drop(2).all { it == Type.FLOAT_TYPE } &&
                    m.access == 0x4
        }
    }

    val featureDetailsClass by +FindClass { it.hasConstant("features.\u0001.details") }

    val getDetails by +FindMethod {
        val featureDetailsClass = ModRuntime.featureDetailsClass
        featureDetailsClass != null &&
                it.method.desc == "()L${featureDetailsClass.name};"
    }

    init {
        +FindClass({
            positionField = it.fields.first { f -> Modifier.isStatic(f.access) }.asDescription(it)
        }) { it.hasConstant("top_left") }
    }
}

// Skin layer related methods go here
object SkinLayer : FinderContainer {
    private val renderMethodFilter: (MethodNode) -> Boolean = { it.desc.endsWith("FFFFFFF)V") }
    private val shouldRenderMethodFilter: (MethodNode) -> Boolean = { it.desc == "()Z" }

    val skinLayerClass by +FindClass {
        it.name.startsWith("lunar/") && it.isInterface && it.methods.any(renderMethodFilter) &&
                it.methods.any(shouldRenderMethodFilter)
    }

    val renderLayerMethod by findMethodFromClass({ skinLayerClass }, renderMethodFilter)
    val shouldRenderLayerMethod by findMethodFromClass({ skinLayerClass }, shouldRenderMethodFilter)

    val renderPlayerItemsMethod by +FindMethod {
        it.method.name == "renderPlayerItems" &&
                it.owner.name.let { name -> name.startsWith("net/optifine/") && name.endsWith("PlayerConfigurations") }
    }

    val renderer by +FindClass { it.calls { c -> c.name == "bridge\$enableColorMaterial" } }
}

// Chat related methods go here
object Chat : FinderContainer {
    lateinit var outgoingChatEvent: String // not internal name!

    private val componentMethod by +FindMethod { it.method.hasConstant(" [x\u0001]") }
    val toBridgeComponentMethod: MethodDescription
        get() = componentMethod.ensure().method.calls
            .find {
                Type.getArgumentTypes(it.desc)
                    .firstOrNull()?.internalName == "net/kyori/adventure/text/Component" &&
                        Type.getReturnType(it.desc).internalName.startsWith("lunar/")
            }!!.asDescription()

    init {
        +FindMethod({
            outgoingChatEvent = it.method.instructions.filterIsInstance<MethodInsnNode>()
                .last { c -> c.name == "getMessage" }
                .owner.replace('/', '.')
        }) {
            it.method.calls { m -> m.name == "getMessage" && m.owner.startsWith("lunar/") }
                    && it.owner.name.startsWith("net/minecraft/")
        }
    }
}

object ServerMappings : FinderContainer {
    private val mappingsClass by +FindClass {
        it.hasConstant("https://servermappings.lunarclientcdn.com/servers.json")
    }

    private val remapServerMethod by findMethodFromClass({ mappingsClass }) {
        Type.getArgumentTypes(it.desc).getOrNull(0)?.internalName == internalString
                && Type.getReturnType(it.desc).internalName == internalString
    }

    val getServerMappingsMethod by findMethodFromClass({ CriticalRuntimeData.lunarClientMain }) {
        Type.getReturnType(it.desc).internalName == mappingsClass?.name
    }

    val getDisplayToIPMapMethod: MethodDescription?
        get() = remapServerMethod?.method?.calls?.first()?.asDescription()
}

object OtherRuntimeData : FinderContainer {
    val sendPopupMethod by +FindMethod { it.method.hasConstant("\u0001[\u0001\u0001\u0001] \u0001\u0001") }
    val getVersionMethod by +FindMethod { it.method.calls(matchName("getMinecraftVersion")) }
    private val isVersionOldMethod by +FindMethod {
        it.owner.hasConstant("https://launchermeta.mojang.com/mc/game/version_manifest.json")
                && Type.getReturnType(it.method.desc) == Type.BOOLEAN_TYPE
                && it.method.hasConstant("v1_8")
    }

    val reloadCapeMethod by +FindMethod { it.method.name == "reloadCape" }
    val versionIdField
        get() = isVersionOldMethod?.method?.instructions
            ?.filterIsInstance<FieldInsnNode>()
            ?.first()?.asDescription()
}

// Utility to get the lunar client main instance
fun MethodVisitor.getLunarMain() =
    invokeMethod(CriticalRuntimeData.getLunarmainMethod.ensure())

// Utility to get the assets socket
fun MethodVisitor.getAssetsSocket() {
    getLunarMain()
    getField(CriticalRuntimeData.assetsSocketField, static = false)
}

// Utility to load the client bridge onto the stack
fun MethodVisitor.getClientBridge() =
    getField(CriticalRuntimeData.clientInstance, static = true)

// Utility to load the current server data onto the stack
fun MethodVisitor.getServerData() {
    getClientBridge()
    callBridgeMethod(Bridge.getServerDataMethod)
}

// Utility to load server mappings class onto the stack
fun MethodVisitor.getServerMappings() {
    getLunarMain()
    invokeMethod(ServerMappings.getServerMappingsMethod.ensure())
}

// Utility to load the boolean if the client window is focused onto the stack
fun MethodVisitor.isWindowFocused() {
    getClientBridge()
    callBridgeMethod(Bridge.isWindowFocusedMethod)
}

// Utility to convert a kyori adventure component that is currently on the stack
// to a lunar bridge component
fun MethodVisitor.toBridgeComponent() =
    invokeMethod(InvocationType.STATIC, Chat.toBridgeComponentMethod)

// Utility to load the current player bridge
fun MethodVisitor.getPlayerBridge() {
    getClientBridge()
    callBridgeMethod(Bridge.getPlayerMethod)
}

// Utility to call a bridge method from a visitor
fun MethodVisitor.callBridgeMethod(methodInfo: MethodInfo?) =
    invokeMethod(InvocationType.INTERFACE, methodInfo?.asDescription() ?: error("Couldn't find bridge method!"))

sealed class Finder<T>(
    val onFound: (T) -> Unit,
    val matcher: (T) -> Boolean
) : ReadOnlyProperty<Any, T?> {
    var value: T? = null
        set(value) {
            if (value != null) {
                onFound(value)
                field = value
            }
        }

    override fun getValue(thisRef: Any, property: KProperty<*>): T? = value
}

class FindClass(
    onFound: (ClassNode) -> Unit = {},
    matcher: (ClassNode) -> Boolean
) : Finder<ClassNode>(onFound, matcher)

class FindMethod(
    onFound: (MethodInfo) -> Unit = {},
    matcher: (MethodInfo) -> Boolean
) : Finder<MethodInfo>(onFound, matcher)

class FindField(
    onFound: (FieldInfo) -> Unit = {},
    matcher: (FieldInfo) -> Boolean
) : Finder<FieldInfo>(onFound, matcher)


data class MethodInfo(val owner: ClassNode, val method: MethodNode) {
    fun asDescription() = method.asDescription(owner)
}

data class FieldInfo(val owner: ClassNode, val field: FieldNode) {
    fun asDescription() = field.asDescription(owner)
}

fun findMethodFromClass(loader: () -> ClassNode?, condition: (MethodNode) -> Boolean) =
    object : ReadOnlyProperty<Any, MethodInfo?> {
        private var method: MethodInfo? = null

        override fun getValue(thisRef: Any, property: KProperty<*>): MethodInfo? {
            if (method != null) return method

            val owner = loader() ?: error("No class was found when finding a method for ${property.name}!")
            return owner.methods.find(condition)?.let { MethodInfo(owner, it) }?.also { method = it }
        }
    }

fun MethodVisitor.invokeMethod(info: MethodInfo) {
    val access = info.method.access
    val type = when {
        access and ACC_PRIVATE != 0 -> InvocationType.SPECIAL
        access and ACC_STATIC != 0 -> InvocationType.STATIC
        info.owner.isInterface -> InvocationType.INTERFACE
        else -> InvocationType.VIRTUAL
    }

    invokeMethod(type, info.asDescription())
}

// Simple interface to use if you want to use the finder system. This solely exists
// so classes don't get loaded lazily and "just work"
interface FinderContainer {
    operator fun <T> Finder<T>.unaryPlus(): Finder<T> {
        RuntimeData.finders.add(this)
        return this
    }
}