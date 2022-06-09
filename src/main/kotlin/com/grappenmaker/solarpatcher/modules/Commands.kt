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
import com.grappenmaker.solarpatcher.config.json
import com.grappenmaker.solarpatcher.configuration
import com.grappenmaker.solarpatcher.util.generation.Accessors
import com.grappenmaker.solarpatcher.util.javaReflectionProperty
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.awt.Desktop
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Utility for command handling
class CommandEventAccessor(event: Any) {
    private val textField = event.javaClass.fields.first()
    private val cancelledField = event.javaClass.superclass.fields.first()

    var text: String by javaReflectionProperty(textField, event)
    var cancelled: Boolean by javaReflectionProperty(cancelledField, event)
    val arguments get() = text.split(" +".toRegex()).drop(1)

    fun cancel() {
        cancelled = true
    }
}

sealed class Command {
    abstract fun handle(accessor: CommandEventAccessor)
}

@Serializable
data class TextCommand(val prefix: String, val suffix: String = "") : Command() {
    override fun handle(accessor: CommandEventAccessor) {
        val components = accessor.text.split(" ")
        accessor.text = prefix + components.drop(1).joinToString(" ") + suffix
    }
}

class HandlerCommand(
    val description: String = "No description was given.",
    val hidden: Boolean = false,
    val handler: CommandEventAccessor.() -> Unit,
) : Command() {
    override fun handle(accessor: CommandEventAccessor) = accessor.handler()
}

// Custom implemented commands
fun getCodeCommands(hypixelCommands: Boolean): Map<String, Command> {
    val easterEgg = HandlerCommand("???", hidden = true) {
        cancel()
        Accessors.ChatUtility.displayComponent(
            """
            {
                "color": "dark_purple",
                "extra": [
                    {
                        "obfuscated": true,
                        "text": "a"
                    },
                    {
                        "obfuscated": false,
                        "text": " NotEvenJoking was here "
                    },
                    {
                        "obfuscated": true,
                        "text": "a"
                    }
                ],
                "text": ""
            }
        """
        )
    }

    // :D
    val rickroll = HandlerCommand("???", hidden = true) {
        cancel()
        Desktop.getDesktop().browse(URI("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    val reloadCosmetics = HandlerCommand("Reloads your cosmetics (for the OptifineItems Module)") {
        cancel()
        Accessors.ChatUtility.displayComponent("""{"text": "Reloading your cosmetics...", "color": "gray"}""")

        try {
            Accessors.ConfigDelegate.reloadPlayerCosmetics()
            Accessors.ChatUtility.displayComponent("""{"text": "Successfully unregistered cosmetics!", "color": "green"}""")
        } catch (e: Exception) {
            Accessors.ChatUtility.displayComponent("""{"text": "There was an error while reloading your cosmetics:\n${e.message}", "color": "red"}""")
        }
    }

    val debugCommand = HandlerCommand("Displays debug information") {
        cancel()
        val formattedDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(Versioning.buildTimestamp)
                .atZone(ZoneId.systemDefault()).toOffsetDateTime()
        )

        val moduleText = configuration.enabledModules.map { m -> m::class.simpleName ?: "Unnamed" }
            .sorted().joinToString()

        Accessors.ChatUtility.displayComponent(
            """[
            "",
            {
                "text": "Solar Patcher Debug",
                "color": "red"
            },
            {
                "text": "\n\n"
            },
            {
                "text": "Minecraft Version: ",
                "color": "green"
            },
            {
                "text": "${Accessors.Utility.getVersion()}\n"
            },
            {
                "text": "Patcher Version: ",
                "color": "green"
            },
            {
                "text": "${Versioning.version}\n"
            },
            {
                "text": "Built date: ",
                "color": "green"
            },
            {
                "text": "$formattedDate\n"
            },
            {
                "text": "Build type: ",
                "color": "green"
            },
            {
                "text": "${if (Versioning.devBuild) "Development" else "Production"}\n"
            },
            {
                "text": "Player: ",
                "color": "green"
            },
            {
                "text": "${Accessors.Utility.getPlayerName()}\n"
            },
            {
                "text": "UUID: ",
                "color": "green"
            },
            {
                "text": "${Accessors.Utility.getPlayerUUID()}\n"
            },
            {
                "text": "Current Server: ",
                "color": "green"
            },
            {
                "text": "${Accessors.Utility.getServerIP() ?: "Singleplayer"}\n"
            },
            {
                "text": "Active modules (${configuration.enabledModules.size}): ",
                "color": "green"
            },
            {
                "text": "$moduleText\n"
            }
        ]"""
        )
    }

    val helpCommand = HandlerCommand("Displays this help message") {
        val commandsText = configuration.customCommands.actualCommands
            .filter { (_, cmd) -> (cmd as? HandlerCommand)?.hidden != true }
            .sortedBy { (name) -> name }
            .joinToString(",\n") { (name, command) ->
                val description = when (command) {
                    is HandlerCommand -> command.description
                    is TextCommand -> "User-defined custom command"
                }
                """{"text": "$name: ", "color": "green"},{"text": "$description\n"}"""
            }

        cancel()
        Accessors.ChatUtility.displayComponent(
            """[
            "",
            {
                "text": "Solar Tweaks - Commands help",
                "color": "red"
            },
            {
                "text": "\nBelow is a list of all custom/Solar Tweaks built-in commands\n\n"
            },
            $commandsText
        ]""",
        )
    }

    val extraHelp = HandlerCommand(hidden = true) {
        Accessors.ChatUtility.displayComponent(
            """{
            "text": "If you need Solar Tweaks related help, take a look in the github discussions, or use /solarhelp",
            "color": "dark_purple",
            "italic": true
        }"""
        )
    }

    return mapOf(
        "whowashere" to easterEgg,
        "rickroll" to rickroll,
        "reloadcosmetics" to reloadCosmetics,
        "solardebug" to debugCommand,
        "solarhelp" to helpCommand,
        "help" to extraHelp
    ) + if (hypixelCommands) loadHypixelCommands() else mapOf()
}

private fun loadHypixelCommands(): Map<String, HandlerCommand> {
    return json.decodeFromString<Map<String, HypixelCommandData>>(
        (Command::class.java.classLoader
            .getResourceAsStream("hypixelCommands.json") ?: return emptyMap())
            .readBytes()
            .decodeToString()
    ).mapValues { (_, command) ->
        val (desc, playCMD, duelCMD) = command
        HandlerCommand(desc) {
            if (Accessors.Utility.getServerIP()?.endsWith("hypixel.net") == true) {
                text = when {
                    duelCMD != null && arguments.isNotEmpty() -> "/duel ${arguments[0]} ${duelCMD}${
                        arguments.getOrNull(1)?.let { "_$it" } ?: ""
                    }"
                    else -> "/play $playCMD"
                }
            }
        }
    }
}

@Serializable
data class HypixelCommandData(
    val description: String,
    val playCommand: String,
    val duelCommand: String? = null,
)
