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

package com.grappenmaker.solarpatcher.config

import com.grappenmaker.solarpatcher.Versioning
import com.grappenmaker.solarpatcher.modules.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KVisibility
import kotlin.reflect.full.functions
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.memberProperties

@Serializable
data class Configuration(
    val patcherVersion: String = Versioning.version,
    val debug: Boolean = false,
    val enableAll: Boolean = false,

    // Allows you to change your nickhider name.
    // This is different to Lunar's implementation because it supports colors
    val nickhider: Nickhider = Nickhider(),
    val fps: FPS = FPS(), // Change your FPS text
    val cps: CPS = CPS(), // Change your CPS text
    val autoGG: AutoGG = AutoGG(), // Change your autogg command (eg. /achat gg (default) -> /ac Good Game)
    val levelHead: LevelHead = LevelHead(), // Sets the levelhead prefix
    // Allows you to disable pinned servers, enable freelook on hypixel, remove blog posts etc
    val metadata: Metadata = Metadata(),
    // Removes LCModSettingsPacket so no mods can be disabled by servers
    val modpacketRemoval: ModpacketRemoval = ModpacketRemoval(),
    val cloaksPlus: CloaksPlus = CloaksPlus(), // Integrate with cloaksplus capes + cosmetics system
    val windowName: WindowName = WindowName(), // Change the name of the window
    // Removes "hit delay" (1.7-like combat). Note that this is not a cheat, just a bugfix for 1.8
    val noHitDelay: NoHitDelay = NoHitDelay(),
    val fpsSpoof: FPSSpoof = FPSSpoof(), // Multiplier for your FPS counter
    // Allows you to remap commands, eg. /qb -> /play duels_bridge_duel
    val customCommands: CustomCommands = CustomCommands(),
    // Change the text that is displayed in your Rich Presence
    // Can also show your activity (eg. Playing Hypixel/AntiAC/Private Server)
    val rpcUpdate: RPCUpdate = RPCUpdate(),
    // Allows the user to have more privacy, because your full task list and hosts list is sent to
    // Lunar Servers every time your client starts, which is questionable
    val tasklistPrivacy: TasklistPrivacy = TasklistPrivacy(),
    val hostslistPrivacy: HostslistPrivacy = HostslistPrivacy(),
    val uncapReach: UncapReach = UncapReach(), // Fixes the reach display to properly display reach in creative mode
    val removeFakeLevelhead: RemoveFakeLevelhead = RemoveFakeLevelhead(), // Removes fake level head levels for nicked players
    val removeHashing: RemoveHashing = RemoveHashing(), // Removes class hash checks. Can speed up launches
    // For developers. Allows to intercept packets being sent from and to Lunar BukkitAPI
    val debugPackets: DebugPackets = DebugPackets(),
    val toggleSprintText: ToggleSprintText = ToggleSprintText(), // Change the togglesprint text
    val reachText: ReachText = ReachText(), // Change the reach display etxt
    // Fix chat "pings" to only happen with actual chat packets and not action bars
    val fixPings: FixPings = FixPings(),
    val lunarOptions: LunarOptions = LunarOptions(), // Always show "Lunar Options" in pause menu
    // Reenables overlays (e.g. to change the icon or panorama, builtin lunar assets)
    val supportOverlays: SupportOverlays = SupportOverlays(),
    // Allows you to use toggle sneak in containers on hypixel
    val toggleSneakContainer: ToggleSneakContainer = ToggleSneakContainer(),
    val pingText: PingText = PingText(), // Change your ping text
    val pingSpoof: PingSpoof = PingSpoof(), // Change the text of the ping value (not a multiplier)
    val clothCapes: ClothCapes = ClothCapes(), // Enable cloth capes for everyone
    val hurtCamShake: HurtCamShake = HurtCamShake(), // Allows you to modify your hurt cam shake effect multiplier
    val chatLimit: ChatLimit = ChatLimit(), // Removes the maximum chat limit count to a preset value
    val mumbleFix: MumbleFix = MumbleFix(), // Allows you to use mumble on other oses (requires special native)
    val websocket: Websocket = Websocket(), // Allows you to set the websocket url
    val enableWrapped: EnableWrapped = EnableWrapped(), // Re-enables lunar client Wrapped
    val customMods: CustomMods = CustomMods(), // Allows the user to define and use custom mods
    val removeChatDelay: RemoveChatDelay = RemoveChatDelay(), // Removes websocket chat delay
    val allowCosmeticCombinations: AllowCosmeticCombinations = AllowCosmeticCombinations(),
    val removeSplashBlur: RemoveSplashBlur = RemoveSplashBlur()
) {
    // RuntimeData -> Internal module to retrieve information about the current lunar installation
    // HandleNotifications -> forced because it fixes a Lunar Client bug, brings back the LCPacketNotification
    // ModName -> changes the vendor; enforced because this is not normal Lunar Client
    // LaunchRequestModule -> request so we can keep track of some nerdy stats (it just increments a counter)
    @Transient
    private val alwaysEnabledModules = listOf(RuntimeData, HandleNotifications, ModName, LaunchRequestModule)

    // All enabled modules, cached
    // Retrieved with reflection, yes it is slow but i dont want to put
    // the mess of all the modules twice.
    @Transient
    val enabledModules = Configuration::class.memberProperties
        .filter { it.visibility == KVisibility.PUBLIC }
        .map { it(this) }
        .filterIsInstance<Module>().filter { it.isEnabled || enableAll } + alwaysEnabledModules
}