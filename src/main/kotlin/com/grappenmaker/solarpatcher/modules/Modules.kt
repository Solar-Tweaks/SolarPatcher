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

import com.grappenmaker.solarpatcher.Versioning
import com.grappenmaker.solarpatcher.asm.*
import com.grappenmaker.solarpatcher.asm.matching.ClassMatcher
import com.grappenmaker.solarpatcher.asm.matching.ClassMatching
import com.grappenmaker.solarpatcher.asm.matching.MethodMatcherData
import com.grappenmaker.solarpatcher.asm.matching.MethodMatching.matchAny
import com.grappenmaker.solarpatcher.asm.matching.MethodMatching.matchDescriptor
import com.grappenmaker.solarpatcher.asm.matching.MethodMatching.matchName
import com.grappenmaker.solarpatcher.asm.matching.MethodMatching.matchOwner
import com.grappenmaker.solarpatcher.asm.matching.MethodMatching.plus
import com.grappenmaker.solarpatcher.asm.matching.asMatcher
import com.grappenmaker.solarpatcher.asm.method.*
import com.grappenmaker.solarpatcher.asm.transform.*
import com.grappenmaker.solarpatcher.asm.util.*
import com.grappenmaker.solarpatcher.config.Constants
import com.grappenmaker.solarpatcher.config.Constants.packetClassname
import com.grappenmaker.solarpatcher.util.ensure
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.*
import java.util.*
import java.util.concurrent.ThreadLocalRandom
import java.util.function.Consumer
import java.util.function.Predicate
import java.util.stream.Stream
import kotlin.math.floor
import kotlin.math.min
import kotlin.reflect.jvm.javaMethod

val internalString = getInternalName<String>()

@Serializable
sealed class Module : TransformGenerator {
    abstract val isEnabled: Boolean
}

@Serializable
sealed class JoinedModule(@Transient private val modules: List<Module> = listOf()) : Module() {
    override fun generate(node: ClassNode): ClassTransform? =
        modules.mapNotNull { it.generate(node) }.reduceTransforms()
}

@Serializable
data class Metadata(
    val removeCalls: List<String> = listOf(
        "serverIntegration",
        "pinnedServers",
        "modSettings",
        "clientSettings",
        "blogPosts"
    ),
    override val isEnabled: Boolean = false
) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        val metaTypes = listOf(
            "blogPosts",
            "modSettings",
            "clientSettings",
            "pinnedServers",
            "serverIntegration",
            "featureFlag",
            "knownServersHash",
            "store"
        )

        val method = node.methods.find { it.constants.containsAll(metaTypes) } ?: return null

        return ClassTransform(
            VisitorTransform(method.asDescription(node).asMatcher()) { parent ->
                object : MethodVisitor(API, parent) {
                    private var lastMetadata: String? = null

                    override fun visitLdcInsn(value: Any?) {
                        super.visitLdcInsn(value)
                        if (value is String) lastMetadata = value
                    }

                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean
                    ) {
                        if (
                            !descriptor.contains("Ljava/util/HashMap;)")
                            || lastMetadata !in removeCalls
                        ) {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                        }
                    }
                }
            })
    }
}

@Serializable
data class ModpacketRemoval(
    override val isEnabled: Boolean = false
) : Module(), TransformGenerator by matcherGenerator(ClassTransform(visitors = listOf { parent: ClassVisitor ->
    AdviceClassVisitor(
        parent,
        matchName("addPacket") + matchDescriptor("(ILjava/lang/Class;)V"),
        enterAdvice = {
            loadVariable(0, ILOAD)
            visitIntInsn(BIPUSH, 31)

            val jumpLabel = Label()
            visitJumpInsn(IF_ICMPNE, jumpLabel)
            returnMethod()
            visitLabel(jumpLabel)
        }
    )
}, shouldExpand = true), matcher = ClassMatching.matchName(packetClassname))

private const val serverRuleClass = "com/lunarclient/bukkitapi/nethandler/client/obj/ServerRule"
private const val logDetection = "No default value for Server Rule [\u0001] found!"

@Serializable
data class NoHitDelay(override val isEnabled: Boolean = false) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        val method = node.methods.find { it.constants.contains(logDetection) } ?: return null
        val desc = method.asDescription(node)

        return ClassTransform(listOf(VisitorTransform(desc.asMatcher()) { parent ->
            createAdvice(desc, parent, exitAdvice = { opcode: Int ->
                if (opcode == ARETURN) {
                    // No, this is not a redundant dup-pop
                    dup()
                    visitFieldInsn(GETSTATIC, serverRuleClass, "LEGACY_COMBAT", "L$serverRuleClass;")
                    visitInsn(ICONST_1)
                    invokeMethod(java.lang.Boolean::class.java.getMethod("valueOf", Boolean::class.javaPrimitiveType))
                    invokeMethod(Map::class.java.getMethod("put", Object::class.java, Object::class.java))
                    pop()
                }
            })
        }), shouldExpand = true)
    }
}

@Serializable
data class FPSSpoof(
    val multiplier: Double = 2.0,
    override val isEnabled: Boolean = false
) : Module(), TransformGenerator by matcherGenerator(ClassTransform(InvokeAdviceTransform(
    matchAny(),
    MethodMatcherData("bridge\$getDebugFPS", "()I").asMatcher(),
    afterAdvice = {
        if (floor(multiplier) == multiplier && multiplier <= 0xFF) {
            // Whole number, can load as byte integer
            visitIntInsn(BIPUSH, multiplier.toInt())

            // Can immediately multiply
            visitInsn(IMUL)
        } else {
            // Can't immediately multiply, should first convert fps/cps to double
            visitInsn(I2D)

            // Should use ldc instruction
            visitLdcInsn(multiplier)

            // Multiply now
            visitInsn(DMUL)

            // Convert back to int
            visitInsn(D2I)
        }
    }
)), matcher = { it.constants.contains("[1466 FPS]") })

@Serializable
data class CustomCommands(
    val commands: Map<String, TextCommand> = mapOf(
        "qb" to TextCommand("/play duels_bridge_duel"),
        "qbd" to TextCommand("/play duels_bridge_doubles"),
        "db" to TextCommand("/duel ", " bridge"),
        "bwp" to TextCommand("/play bedwars_practice")
    ),
    val useHypixelCommands: Boolean = true
) : Module() {
    @Transient
    override val isEnabled = true

    init {
        // Yep this is a fake singleton
        // Might want to work on a better "storage" system
        instance = this
    }

    @Transient
    internal val actualCommands: List<Pair<String, Command>> =
        (commands + getCodeCommands(useHypixelCommands)).toList()

    companion object {
        // Instance of this class, initialized at runtime
        @JvmStatic
        lateinit var instance: CustomCommands
            private set

        // Function that will handle an event (at runtime),
        // so that we don't have to implement it with bytecode
        @Suppress("Unused") // it is used obviously
        @JvmStatic
        fun commandHandler(event: Any?) {
            if (event == null) return // Java sadness

            val accessor = CommandEventAccessor(event)
            val text = accessor.text
            if (!text.startsWith("/")) return

            val components = text.split(" ")
            val commandName = components.first().substring(1)
            instance.actualCommands
                .find { (name) -> name.equals(commandName, true) }
                ?.second?.handle(accessor)
        }
    }

    override fun generate(node: ClassNode): ClassTransform? {
        val handleMethod = node.methods.find { it.constants.contains("EventBus [\u0001]: \u0001") } ?: return null
        val desc = handleMethod.asDescription(node)

        return ClassTransform(listOf(VisitorTransform(desc.asMatcher()) { parent ->
            createAdvice(desc, parent, exitAdvice = {
                val end = Label()

                // Load event class name onto the stack
                loadVariable(1) // Parameter 0, variable 1
                invokeMethod(Object::class.java.getMethod("getClass"))
                invokeMethod(Class<*>::getName)

                // Load chat event class onto the stack
                getObject<Chat>()
                getProperty(Chat::outgoingChatEvent)

                // Compare the strings
                invokeMethod(String::equals)

                // If they don't match, goodbye
                visitJumpInsn(IFEQ, end)

                // Call handler
                loadVariable(1)
                invokeMethod(
                    InvocationType.STATIC,
                    "commandHandler",
                    "(Ljava/lang/Object;)V",
                    getInternalName<CustomCommands>()
                )

                // method end
                visitLabel(end)
            })
        }), shouldExpand = true)
    }
}

const val defaultClientID = 562286213059444737L
const val defaultIcon = "icon_07_11_2020"
const val defaultText = "Lunar Client"
const val defaultVersionText = "Playing Minecraft \u0001"
private const val builderClass = "com/jagrosh/discordipc/entities/RichPresence\$Builder"

@Serializable
data class RPCUpdate(
    val clientID: Long = 920998351430901790,
    val icon: String = "logo",
    val iconText: String = "Solar Tweaks",
    val afkText: String = "AFK",
    val menuText: String = "In Menu",
    val singlePlayerText: String = "Playing Singleplayer",
    val versionText: String = "Minecraft",
    val displayActivity: Boolean = true,
    // EXACT MATCH -> display name
    val customServerMappings: Map<String, String> = mapOf(),
    val showIcon: Boolean = true,
    val bigServerIcon: Boolean = false,
    override val isEnabled: Boolean = true
) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        val updateMethod = node.methods.find { it.constants.contains(defaultIcon) } ?: return null
        return ClassTransform(listOf(InvokeAdviceTransform(
            updateMethod.asDescription(node).asMatcher(),
            matchName("build"), // RPC builder
            beforeAdvice = {
                if (!displayActivity) return@InvokeAdviceTransform

                // There is now a RichPresence.Builder on the stack
                // Keep in mind that after dropping out of this advice
                // the builder should be on the stack

                // Before doing anything, reset icon
                visitLdcInsn(icon)
                visitLdcInsn(iconText)
                setImage(if (bigServerIcon) "setSmallImage" else "setLargeImage")

                // Labels to jump to
                val wasNull = Label()
                val end = Label()

                val afk = Label()
                isWindowFocused()
                visitJumpInsn(IFNE, afk)
                visitLdcInsn(afkText)
                setState()
                visitJumpInsn(GOTO, end)
                visitLabel(afk)

                // Get the server data and check for nullity
                getServerData()
                dup()

                // If it is null, pop to stack and jump to end
                visitJumpInsn(IFNULL, wasNull)

                // If it was not null, get the server ip
                callBridgeMethod(Bridge.getServerIPMethod)
                storeVariable(2) // Store the server ip
                remapServerIP { loadVariable(2) }
                concat("(L$internalString;)L$internalString;", "Playing on \u0001")

                // Set the "state"
                setState()

                // Jump back to original code
                visitJumpInsn(GOTO, end)

                // Handle nullity by popping the null off the stack
                visitLabel(wasNull)
                pop()
                visitLdcInsn(menuText)
                setState()
                visitLabel(end)

                // Continue setting the rpc (drops out of this advice)
            }
        )), listOf {
            replaceClassConstants(
                it, mapOf(
                    defaultClientID to clientID,
                    defaultText to iconText,
                    defaultVersionText to "$versionText \u0001"
                )
            )
        })
    }

    // Utility to remap a server ip to a display name
    private fun MethodVisitor.remapServerIP(default: String = "Private Server", loader: () -> Unit) {
        val defaultHandler = Label()
        val end = Label()

        if (customServerMappings.isNotEmpty()) {
            loader()
            invokeMethod(String::hashCode)
            createLookupSwitch(
                defaultHandler,
                customServerMappings.mapKeys { (k) -> k.hashCode() }.mapValues { (_, v) ->
                    {
                        visitLdcInsn(v)
                        visitJumpInsn(GOTO, end)
                    }
                })
        }

        visitLabel(defaultHandler)
        getServerMappings()
        invokeMethod(
            InvocationType.VIRTUAL,
            ServerMappings.getDisplayToIPMapMethod.ensure()
        ) // map

        // Create an inverse view onto the known servers map
        // This is used to get the display name of a server ip
        val loop = Label()
        val reloop = Label()
        val notFound = Label()

        invokeMethod(InvocationType.INTERFACE, "entrySet", "()Ljava/util/Set;", "java/util/Map") // set of entries
        invokeMethod(InvocationType.INTERFACE, "iterator", "()Ljava/util/Iterator;", "java/util/Set") // iterator

        visitLabel(loop)
        dup() // 2x iterator
        invokeMethod(Iterator<*>::hasNext) // iterator bool
        visitJumpInsn(IFEQ, notFound)

        dup() // 2x iterator
        invokeMethod(Iterator<*>::next) // iterator entry
        dup() // iterator 2x entry
        getProperty(Map.Entry<*, *>::value) // iterator entry list
        visitTypeInsn(CHECKCAST, "java/util/List")
        invokeMethod(Collection<*>::stream)
        loader() // iterator entry listStream string
        invokeMethod(::doesMatch) // iterator entry listStream predicate
        invokeMethod(Stream<*>::anyMatch) // iterator entry bool
        visitJumpInsn(IFEQ, reloop) // iterator entry
        getProperty(Map.Entry<*, *>::key) // iterator string
        visitTypeInsn(CHECKCAST, "java/lang/String")
        visitInsn(SWAP) // string iterator
        pop() // string

        if (showIcon) {
            // As we found a match, might as well try to display an icon
            visitInsn(SWAP) // string builder
            loader() // string builder string
            loadConstant('.')
            loadConstant('_')
            invokeMethod(
                String::class.java.getMethod(
                    "replace",
                    Char::class.javaPrimitiveType,
                    Char::class.javaPrimitiveType
                )
            ) // string builder string
            loader() // string builder string string
            setImage(if (bigServerIcon) "setLargeImage" else "setSmallImage") // string builder
            visitInsn(SWAP) // builder string
        }

        visitJumpInsn(GOTO, end) // exit with string

        visitLabel(reloop)
        pop()
        visitJumpInsn(GOTO, loop)

        visitLabel(notFound)
        pop()
        visitLdcInsn(default)
        visitLabel(end)
    }

    private fun MethodVisitor.setState() = invokeMethod(
        InvocationType.VIRTUAL,
        "setState",
        "(L$internalString;)L$builderClass;",
        builderClass
    )

    private fun MethodVisitor.setImage(name: String) = invokeMethod(
        InvocationType.VIRTUAL,
        name,
        "(L$internalString;L$internalString;)L$builderClass;",
        builderClass
    )
}

@Suppress("MemberVisibilityCanBePrivate") // can't be private because used in bytecode
fun doesMatch(address: String) = Predicate<String> { address.endsWith(it) }

private fun privacyTransformGenerator(path: String): TransformGenerator = object : TransformGenerator {
    override fun generate(node: ClassNode): ClassTransform? {
        val method = node.methods.find { it.constants.contains(path) }
        return method?.let { ClassTransform(StubMethodTransform(method.asDescription(node))) }
    }
}

@Serializable
data class TasklistPrivacy(override val isEnabled: Boolean = false) : Module(),
    TransformGenerator by privacyTransformGenerator("\u0001\\system32\\tasklist.exe")

@Serializable
data class HostslistPrivacy(override val isEnabled: Boolean = false) : Module(),
    TransformGenerator by privacyTransformGenerator("\u0001\\system32\\drivers\\etc\\hosts")

@Serializable
data class UncapReach(override val isEnabled: Boolean = false) : Module(), TransformGenerator by matcherGenerator(
    ClassTransform(
        listOf(
            RemoveInvokeTransform(
                matchAny(),
                MethodMatcherData("min", "(DD)D").asMatcher()
            )
        ),
        listOf { parent: ClassVisitor -> replaceClassConstants(parent, mapOf(3.0 to null)) }),
    matcher = { it.constants.containsAll(setOf(" blocks", "range")) }
)

@Serializable
data class RemoveFakeLevelhead(override val isEnabled: Boolean = false) : Module(),
    TransformGenerator by matcherGenerator(ClassTransform(
        listOf(
            RemoveInvokeTransform(
                matchAny(),
                ThreadLocalRandom::current.javaMethod!!.asMatcher()
            ),
            ReplaceCodeTransform(
                matchAny(),
                ThreadLocalRandom::class.java.getMethod("nextInt", Int::class.javaPrimitiveType).asMatcher()
            ) { _, _ ->
                pop()
                visitIntInsn(BIPUSH, -26) // Hacky bro
            }
        )), matcher = { it.constants.contains("Level: ") }
    )

@Serializable
data class RemoveHashing(
    val className: String = "WMMWMWMMWMWWMWMWWWWWWWMMM",
    val method: MethodMatcherData = MethodMatcherData(
        "WWWWWNNNNWMWWWMWWMMMWMMWM",
        "(L$internalString;[BZ)Z"
    ),
    override val isEnabled: Boolean = false
) : Module(), TransformGenerator by matcherGenerator(
    ClassTransform(
        ImplementTransform(method.asMatcher()) {
            visitInsn(ICONST_1)
            returnMethod(IRETURN)
        }
    ), matcher = ClassMatching.matchName(className)
)

@Serializable
data class DebugPackets(override val isEnabled: Boolean = false) : Module(),
    TransformGenerator by matcherGenerator(ClassTransform(
        InvokeAdviceTransform(
            matchAny(),
            matchName("process") + matchOwner(packetClassname),
            afterAdvice = {
                visitPrintln {
                    loadVariable(2)
                    invokeMethod(Object::class.java.getMethod("getClass"))
                    invokeMethod(Class<*>::getName)

                    val stringName = internalString
                    concat(
                        "(L$stringName;)L$stringName;",
                        "Received/processed packet type \u0001"
                    )
                }
            }
        )), matcher = { it.interfaces.contains("com/lunarclient/bukkitapi/nethandler/client/LCNetHandlerClient") }
    )

@Serializable
data class ToggleSprintText(
    val replacements: Map<String, String> = Constants.ToggleSprint.defaultConfig,
    val method: MethodMatcherData = MethodMatcherData.CLINIT,
    override val isEnabled: Boolean = false,
) : Module(), TransformGenerator by matcherGenerator(
    ClassTransform(replacements.map { (from, to) -> TextTransform(method.asMatcher(), from, to) }),
    matcher = { it.constants.containsAll(replacements.keys) }
)

const val notifPacketClassname = "com/lunarclient/bukkitapi/nethandler/client/LCPacketNotification"

object HandleNotifications : Module() {
    override val isEnabled = true
    override fun generate(node: ClassNode): ClassTransform? {
        val method = node.methods.find {
            it.name == "handleNotification" && Type.getArgumentTypes(it.desc)
                .first().internalName == notifPacketClassname
        } ?: return null

        return ClassTransform(
            VisitorTransform(method.asDescription(node).asMatcher()) { parent ->
                object : MethodVisitor(API, parent) {
                    override fun visitCode() {
                        super.visitCode()
                        // Actually implement this garbage
                        // Get assets socket
                        getAssetsSocket()

                        // Create packet
                        val popupMethod = OtherRuntimeData.sendPopupMethod.ensure()
                        construct(
                            Type.getArgumentTypes(popupMethod.method.desc).first().internalName,
                            "(L$internalString;L$internalString;)V"
                        ) {
                            // Load title onto operand stack
                            loadVariable(1)
                            invokeMethod(
                                InvocationType.VIRTUAL,
                                "getLevel",
                                "()L$internalString;",
                                notifPacketClassname
                            )

                            concat("(L$internalString;)L$internalString;", "Server Notification - \u0001")

                            // Load message onto operand stack
                            loadVariable(1)
                            invokeMethod(
                                InvocationType.VIRTUAL,
                                "getMessage",
                                "()L$internalString;",
                                notifPacketClassname
                            )
                        }

                        // Fake send packet
                        invokeMethod(InvocationType.VIRTUAL, popupMethod.asDescription())
                    }
                }
            }
        )
    }
}

const val modName: String = "SolarTweaks"

object ModName : Module(),
    TransformGenerator by matcherGenerator(ClassTransform(ImplementTransform(matchName("getClientModName")) {
        val solarVersion = "$modName v${Versioning.version}"

        // Add a try catch block, because class access might fail
        val start = Label()
        val end = Label()
        visitTryCatchBlock(start, end, end, "java/lang/Throwable")

        // try-start
        visitLabel(start)

        getObject<CriticalRuntimeData>()
        getProperty(CriticalRuntimeData::version)
        concat("(L$internalString;)L$internalString;", "Lunar Client \u0001/$solarVersion")
        returnMethod(ARETURN)

        // try-end
        visitLabel(end)

        // try-handler
        dup()
        invokeMethod(Throwable::class.java.getMethod("printStackTrace"))
        visitLdcInsn(solarVersion)
        returnMethod(ARETURN)
    }), matcher = ClassMatching.matchName("net/minecraft/client/ClientBrandRetriever")) {
    @Transient
    override val isEnabled: Boolean = true
}

@Serializable
data class FixPings(override val isEnabled: Boolean = false) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        if (!node.constants.contains("chat_ping_sound")) return null

        val handleSoundMethod = node.methods.find {
            it.calls.any { c -> c.name == "bridge\$playSound" }
        } ?: return null
        val eventType = Type.getArgumentTypes(handleSoundMethod.desc).first()

        return ClassTransform(VisitorTransform(
            handleSoundMethod.asDescription(node).asMatcher()
        ) { parent ->
            object : MethodVisitor(API, parent) {
                override fun visitJumpInsn(opcode: Int, label: Label) {
                    super.visitJumpInsn(opcode, label)

                    if (opcode == IFNE) {
                        val end = Label()

                        loadVariable(1)
                        invokeMethod(InvocationType.VIRTUAL, "getType", "()I", eventType.internalName)
                        visitInsn(ICONST_1)
                        visitJumpInsn(IF_ICMPEQ, end)

                        returnMethod()
                        visitLabel(end)
                    }
                }
            }
        })
    }
}

@Serializable
data class LunarOptions(override val isEnabled: Boolean = false) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        val method = node.methods.find { it.constants.contains("Lunar Options...") } ?: return null
        return ClassTransform(VisitorTransform(method.asDescription(node).asMatcher()) { parent ->
            object : MethodVisitor(API, parent) {
                override fun visitJumpInsn(opcode: Int, label: Label) {
                    if (opcode == IFNE) {
                        pop()
                        return
                    }

                    super.visitJumpInsn(opcode, label)
                }
            }
        })
    }
}

@Serializable
data class SupportOverlays(override val isEnabled: Boolean = true) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        val method = node.methods.find {
            Type.getArgumentTypes(it.desc).firstOrNull()?.internalName == internalString
                    && it.constants.contains("assets/lunar/")
        } ?: return null

        return ClassTransform(ConstantValueTransform(method.asDescription(node).asMatcher(), true))
    }
}

@Serializable
data class ToggleSneakContainer(override val isEnabled: Boolean = false) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        if (!node.constants.contains("toggleSneak")) return null

        val method = node.methods.find { it.calls.any { c -> c.name == "getServer" } } ?: return null
        return ClassTransform(ReplaceCodeTransform(
            method.asDescription(node).asMatcher(),
            matchName("contains")
        ) { _, _ ->
            pop(2)
            visitInsn(ICONST_0)
        })
    }
}

@Serializable
data class PingSpoof(
    val pingValue: String = "69",
    override val isEnabled: Boolean = false
) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        val method = node.methods.find { it.constants.contains("\u0001 ms") } ?: return null
        return ClassTransform(VisitorTransform(method.asDescription(node).asMatcher()) { parent ->
            object : MethodVisitor(API, parent) {
                override fun visitInvokeDynamicInsn(
                    name: String,
                    desc: String,
                    handle: Handle,
                    vararg args: Any?
                ) {
                    if (name == "makeConcatWithConstants") {
                        visitInsn(POP2)
                        visitLdcInsn(pingValue)
                        super.visitInvokeDynamicInsn(name, "(L$internalString;)L$internalString;", handle, *args)
                    } else {
                        super.visitInvokeDynamicInsn(name, desc, handle, *args)
                    }
                }
            }
        })
    }
}

@Serializable
data class ClothCapes(override val isEnabled: Boolean = false) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        val method = node.methods.find {
            it.constants.contains("LunarPlus")
                    && it.calls.any { c -> c.name == "containsKey" }
        } ?: return null

        return ClassTransform(ConstantValueTransform(method.asDescription(node).asMatcher(), true))
    }
}

@Serializable
data class HurtCamShake(val multiplier: Float = .3f, override val isEnabled: Boolean = false) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        if (!node.constants.contains("Failed to load shader: ")) return null

        val originalValue = 14.0f
        return ClassTransform(visitors = listOf { parent ->
            replaceClassConstants(parent, mapOf(originalValue to originalValue * multiplier))
        })
    }
}

@Serializable
data class ChatLimit(val limit: Int = 255, override val isEnabled: Boolean = false) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        if (!node.constants.contains("sendAutocompleteRequest")) return null

        val originalLimit = 100
        return ClassTransform(visitors = listOf { parent ->
            replaceClassConstants(parent, mapOf(originalLimit to min(limit, 255)))
        })
    }
}

@Serializable
data class MumbleFix(override val isEnabled: Boolean = false) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        if (!node.hasConstant("AllTalk")) return null

        val method = node.methods.find { it.hasConstant("win") } ?: return null
        return ClassTransform(ReplaceCodeTransform(
            method.asDescription(node).asMatcher(),
            matchName("contains")
        ) { _, _ ->
            pop()
            visitInsn(ICONST_1)
        })
    }
}

@Serializable
data class EnableWrapped(override val isEnabled: Boolean = false) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        val method = node.methods.find { it.hasConstant("Wrapped") } ?: return null
        return ClassTransform(ReplaceCodeTransform(
            method.asDescription(node).asMatcher(),
            matchName("booleanValue")
        ) { _, _ ->
            pop()
            visitInsn(ICONST_1)
        })
    }
}

@Serializable
data class RemoveChatDelay(override val isEnabled: Boolean = false) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        if (!node.hasConstant("sendPlaceholder")) return null
        return ClassTransform(VisitorTransform(matchAny()) { parent ->
            replaceMethodConstants(
                parent,
                mapOf(2L to 0L)
            )
        })
    }
}

@Serializable
data class AllowCosmeticCombinations(override val isEnabled: Boolean = false) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        val method = node.methods.find {
            it.desc == "(Lcom/google/gson/JsonObject;)V" &&
                    it.hasConstant("compatible")
        } ?: return null

        val field = node.fields.find { it.desc == "Ljava/util/Map;" } ?: return null

        return ClassTransform(VisitorTransform(method.asDescription(node).asMatcher()) { parent ->
            object : MethodVisitor(API, parent) {
                var lastLdc: Any? = null
                override fun visitLdcInsn(value: Any?) {
                    lastLdc = value
                    super.visitLdcInsn(value)
                }

                override fun visitJumpInsn(opcode: Int, label: Label) {
                    if (opcode == IFEQ && lastLdc == "compatible") {
                        pop()
                        loadVariable(0)
                        getField(field.asDescription(node))
                        loadConstant("any")
                        loadConstant(true)
                        invokeMethod(java.util.Map<*, *>::put)
                        super.visitJumpInsn(GOTO, label)
                    } else {
                        super.visitJumpInsn(opcode, label)
                    }
                }
            }
        })
    }
}

@Serializable
data class RemoveSplashBlur(override val isEnabled: Boolean = false) : Module() {
    override fun generate(node: ClassNode): ClassTransform? {
        val init = node.methods.find { it.name == "<init>" } ?: return null
        if (!init.hasConstant("backgrounds/panorama_0.png")) return null

        val renderMethod = node.methods.find { it.calls(matchName("glCopyTexSubImage2D")) } ?: return null
        val desc = renderMethod.asDescription(node).asMatcher()
        return ClassTransform(
            listOf(
                VisitorTransform(desc) { parent ->
                    object : MethodVisitor(API, parent) {
                        override fun visitInsn(opcode: Int) {
                            super.visitInsn(if (opcode == ICONST_3) ICONST_0 else opcode)
                        }
                    }
                },
                RemoveInvokeTransform(desc, matchName("glTexParameteri"), popCount = 3)
            )
        )
    }
}