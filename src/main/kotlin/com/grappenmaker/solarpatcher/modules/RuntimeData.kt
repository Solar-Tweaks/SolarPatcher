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
import com.grappenmaker.solarpatcher.asm.matching.MethodMatching
import com.grappenmaker.solarpatcher.asm.matching.MethodMatching.match
import com.grappenmaker.solarpatcher.asm.method.InvocationType
import com.grappenmaker.solarpatcher.asm.method.MethodDescription
import com.grappenmaker.solarpatcher.asm.transform.ClassTransform
import com.grappenmaker.solarpatcher.asm.util.*
import com.grappenmaker.solarpatcher.util.componentName
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import java.lang.reflect.Modifier
import java.util.function.Predicate
import java.util.stream.Stream

// Utility "module" to get runtime data
// Internally used to lookup classes and methods that can't be recognized with simple analysis
object RuntimeData : Module() {
    override val isEnabled = true
    private val knownData = mutableMapOf<String, Any?>()

    private lateinit var mainNode: ClassNode

    // Classnames that have been found
    lateinit var lunarClientClass: String
    lateinit var bridgeClass: String
    lateinit var serverMappingsClass: String
    val clientBridgeClass: String get() = Type.getReturnType(getClientBridgeMethod.descriptor).internalName
    val playerBridgeClass: String get() = Type.getReturnType(getPlayerMethod.descriptor).internalName

    // Methods that have been found
    lateinit var getLunarmainMethod: MethodDescription
    lateinit var getClientBridgeMethod: MethodDescription
    lateinit var getServerDataMethod: MethodDescription
    lateinit var getServerMappingsMethod: MethodDescription
    lateinit var getDisplayToIPMapMethod: MethodDescription
    lateinit var toBridgeComponentMethod: MethodDescription
    lateinit var getPlayerMethod: MethodDescription
    lateinit var displayMessageMethod: MethodDescription

    var assetsSocketField: FieldDescription? = null
    lateinit var sendPopupMethod: MethodDescription

    var outgoingPacketEvent: String = "" // Not lateinit because that could crash the client
        private set

    val version by runtimeValue("version", "unknown")
    val os by runtimeValue("os", "unknown")
    val arch by runtimeValue("arch", "unknown")

    override fun generate(node: ClassNode): ClassTransform? {
        if (node.constants.contains("wss://assetserver.\u0001/connect")) handleAssetsSocket(node)
        if (node.constants.contains("Starting Lunar client...")) handleLunarMain(node)
        if (node.constants.containsAll(listOf("/lc_upload_screenshot", "Screenshot taken"))) handleScreenshotter(node)
        if (node.constants.contains("https://servermappings.lunarclientcdn.com/servers.json")) handleServerMappings(node)
        if (node.isInterface) {
            handleClientBridge(node)
            handlePlayerEntityBridge(node)
        }

        handleBridge(node)
        handleChatMod(node)

        return null // Don't perform transformation
    }

    // Used for retrieving information about what method to call to convert to a component
    private fun handleChatMod(node: ClassNode) {
        val method = node.methods.find { it.constants.contains(" [x\u0001]") } ?: return
        toBridgeComponentMethod = method.calls
            .find {
                Type.getArgumentTypes(it.desc).firstOrNull()?.internalName == componentName &&
                        Type.getReturnType(it.desc).internalName.startsWith("lunar/")
            }?.asDescription() ?: return
    }

    // Used for retrieving data about the player entity bridge
    private fun handlePlayerEntityBridge(node: ClassNode) {
        displayMessageMethod = node.methods.find { it.name == "bridge\$addChatMessage" }?.asDescription(node) ?: return
    }

    // Used for retrieving information about the client bridege
    private fun handleClientBridge(node: ClassNode) {
        getPlayerMethod = node.methods.find { it.name == "bridge\$getPlayer" }?.asDescription(node) ?: return
    }

    // Used for retrieving display server names
    private fun handleServerMappings(node: ClassNode) {
        serverMappingsClass = node.name

        // Get the remap method
        val remapServerMethod = node.methods.find {
            Type.getArgumentTypes(it.desc).getOrNull(0)?.internalName == internalString
                    && Type.getReturnType(it.desc).internalName == internalString
        } ?: return

        // Get the call that gets the Map
        getDisplayToIPMapMethod = remapServerMethod.calls.first().asDescription()

        // Get the server mappings getter
        getServerMappingsMethod =
            mainNode.methods.find { Type.getReturnType(it.desc).internalName == serverMappingsClass }
                ?.asDescription(mainNode) ?: return
    }

    // Used for retrieving data from the client
    private fun handleBridge(node: ClassNode) {
        val serverMethod = node.methods.find { it.calls.any { c -> c.name == "getLunarServer" } } ?: return

        // This is the one and only bridge bridge!
        // Sounds weird, but it is!
        bridgeClass = node.name

        // Retrieve code to get the server
        val calls = serverMethod.calls
        getClientBridgeMethod = calls.first().asDescription()
        getServerDataMethod = calls[1].asDescription()
    }

    // Used for retrieving information about the current runtime
    private fun handleLunarMain(node: ClassNode) {
        mainNode = node
        println("Loading runtime values")

        try {
            lunarClientClass = node.name
            getLunarmainMethod =
                node.methods.find {
                    Type.getReturnType(it.desc).internalName == node.name
                            && Modifier.isStatic(it.access)
                }?.asDescription(node) ?: error("No get lunar main method?")

            val clinit = node.methods.find { MethodMatching.matchClinit().match(it.asDescription(node)) } ?: return
            val assignments = clinit.instructions
                .filterIsInstance<FieldInsnNode>()
                .filter { it.opcode == PUTSTATIC }
                .associate { it.name to (it.previous as? LdcInsnNode)?.cst }

            knownData += node.fields
                .associate { it.name to (it.value ?: assignments[it.name]) }
        } catch (e: Exception) {
            e.printStackTrace()
            println("Couldn't load runtime values!")
        }

        // Debuy log information
        println("Runtime information: ${knownData.toList()}")

        // Get assets socket field
        val call = node.methods.filter { it.constants.contains("Connected to the AssetServer") }
            .mapNotNull { it.calls.find { c -> c.name == "connect" } }.first()

        val insn = call.previous as FieldInsnNode
        assetsSocketField = FieldDescription(insn.name, insn.desc, insn.owner)
    }

    // Used for retrieving information on a specific event
    private fun handleScreenshotter(node: ClassNode) {
        val ctor = node.methods.find { it.name == "<init>" } ?: return
        ctor.instructions
            .filterIsInstance<LdcInsnNode>()
            .lastOrNull { it.cst is Type }
            ?.let { outgoingPacketEvent = (it.cst as Type).className }
    }

    // Used for getting the method with the popup call
    private fun handleAssetsSocket(node: ClassNode) {
        val method =
            node.methods.find { it.constants.contains("\u0001[\u0001\u0001\u0001] \u0001\u0001") } ?: return

        sendPopupMethod = method.asDescription(node)
    }

    private inline fun <reified T> runtimeValue(field: String, default: T) =
        lazy { (knownData[field] ?: default) as? T ?: error("Wrong type in runtime data!") }
}

// Utility to get the lunar client main instance
fun MethodVisitor.getLunarMain() =
    invokeMethod(InvocationType.STATIC, RuntimeData.getLunarmainMethod)

// Utility to get the assets socket
fun MethodVisitor.getAssetsSocket() {
    getLunarMain()
    getField(RuntimeData.assetsSocketField!!, static = false)
}

// Utility to load the client bridge onto the stack
fun MethodVisitor.getClientBridge() =
    invokeMethod(InvocationType.STATIC, RuntimeData.getClientBridgeMethod)

// Utility to load the current server data onto the stack
fun MethodVisitor.getServerData() {
    getClientBridge()
    invokeMethod(InvocationType.INTERFACE, RuntimeData.getServerDataMethod)
}

// Utility to load server mappings class onto the stack
fun MethodVisitor.getServerMappings() {
    getLunarMain()
    invokeMethod(InvocationType.VIRTUAL, RuntimeData.getServerMappingsMethod)
}

// Utility to remap a server ip to a display name
fun MethodVisitor.remapServerIP(default: String = "Private Server", loader: () -> Unit) {
    getServerMappings()
    invokeMethod(InvocationType.VIRTUAL, RuntimeData.getDisplayToIPMapMethod) // map

    // Create an inverse view onto the known servers map
    // This is used to get the display name of a server ip
    val loop = Label()
    val reloop = Label()
    val notFound = Label()
    val end = Label()

    invokeMethod(InvocationType.INTERFACE, "entrySet", "()Ljava/util/Set;", "java/util/Map") // set of entries
    invokeMethod(InvocationType.INTERFACE, "iterator", "()Ljava/util/Iterator;", "java/util/Set") // iterator

    visitLabel(loop)
    dup() // 2x iterator
    invokeMethod(Iterator<*>::hasNext) // iterator bool
    visitJumpInsn(IFEQ, notFound)

    dup() // 2x iterator
    invokeMethod(Iterator<*>::next) // iterator entry
    dup() // iterator 2x entry
    invokeMethod(Map.Entry<*, *>::value.getter) // iterator entry list
    visitTypeInsn(CHECKCAST, "java/util/List")
    invokeMethod(Collection<*>::stream)
    loader() // iterator entry listStream string
    invokeMethod(::doesMatch) // iterator entry listStream predicate
    invokeMethod(Stream<*>::anyMatch) // iterator entry bool
    visitJumpInsn(IFEQ, reloop) // iterator entry
    invokeMethod(Map.Entry<*, *>::key.getter) // iterator string
    visitTypeInsn(CHECKCAST, "java/lang/String")
    visitInsn(SWAP) // string iterator
    pop() // string
    visitJumpInsn(GOTO, end) // exit with string

    visitLabel(reloop)
    pop()
    visitJumpInsn(GOTO, loop)

    visitLabel(notFound)
    pop()
    visitLdcInsn(default)
    visitLabel(end)
}

fun doesMatch(address: String) = Predicate<String> { address.endsWith(it) }

// Utility to load the boolean if the client window is focused onto the stack
fun MethodVisitor.isWindowFocused() {
    getClientBridge()
    invokeMethod(InvocationType.INTERFACE, "bridge\$isWindowFocused", "()Z", RuntimeData.clientBridgeClass)
}

// Utility to convert a kyori adventure compoenent that is currently on the stack
// to a lunar bridge component
fun MethodVisitor.toBridgeComponent() =
    invokeMethod(InvocationType.STATIC, RuntimeData.toBridgeComponentMethod)

// Utility to load the current player bridge
fun MethodVisitor.getPlayerBridge() {
    getClientBridge()
    invokeMethod(InvocationType.INTERFACE, RuntimeData.getPlayerMethod)
}