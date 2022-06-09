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

@file:JvmName("LunarLauncher")

package com.grappenmaker.solarpatcher.util

import com.typesafe.config.ConfigFactory
import java.io.File
import java.io.InputStream
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarFile
import kotlin.system.exitProcess

// Utility used to run the remapped lunar-prod-optifine by LunarMapper

// Usage: java -cp lunar-util-vX.X.jar com.grappenmaker.solarpatcher.util.LunarLauncher
// <remapped path> <version> [<passthrough arguments>]
fun main(args: Array<String>) {
    // Retrieve arguments
    val remappedLunarPath = args.firstOrNull() ?: error("Specify the path of the remapped lc!")
    val remappedLunar = File(remappedLunarPath).assertExists()

    val version = args.getOrNull(1) ?: exitWithError("Specify a minecraft version!")
    val confVersion = (if (!version.startsWith("v")) "v$version" else version)
        .replace('.', '_')

    println("Attempting to launch version $confVersion from $remappedLunar...")

    // Find lunar dirs
    val homeDir = File(System.getProperty("user.home")).assertExists()
    val lunarDir = File(homeDir, ".lunarclient").assertExists()
    val versionDir = File(lunarDir, "offline/$version").assertExists()
    val vpatcher = File(versionDir, "vpatcher-prod.jar").assertExists()

    println("Found version dir: ${versionDir.absolutePath}, vpatcher at ${vpatcher.absolutePath}")

    // Verify the natives path state
    val nativesDir = File(versionDir, "natives").assertExists()
    if (!System.getProperty("java.library.path").contains(nativesDir.absolutePath)) {
        // Try to access the natives path
        exitWithError("Make sure the natives directory exists in your library path!")
    }

    // Find the versions.conf file in vpatcher
    val jarFile = JarFile(vpatcher, false)
    val entry = jarFile.getJarEntry("versions.conf") ?: error("No versions.conf in vpatcher?")
    val stream = jarFile.getInputStream(entry)

    // Read the configuration
    val parsed = ConfigFactory.parseReader(stream.reader()).resolve()
    stream.close()
    val versionConfig = parsed.getConfig(confVersion)
    val dependencies = versionConfig.getStringList("dependencies")

    // Find lunar's libs + mc dependencies
    val lunarLibs = listOf(
        "lunar-assets-prod-1-optifine",
        "lunar-assets-prod-2-optifine",
        "lunar-assets-prod-3-optifine",
        "lunar-libs"
    ).map { File(versionDir, "$it.jar").assertExists() }

    // Find minecraft directory
    val platform = System.getProperty("os.name").lowercase()
    val minecraftDir = when {
        platform.contains("win") -> File(System.getenv("APPDATA"), ".minecraft")
        platform.contains("mac") -> File(homeDir, "Library/Application Support/minecraft")
        else -> File(homeDir, ".minecraft")
    }.assertExists()

    // Find all mc dependencies
    val libsDir = File(minecraftDir, "libraries").assertExists()
    val allLibs = dependencies.map {
        val (groupId, artifactId, artifactVersion) = it.split(":")
        val groupDir = File(libsDir, groupId.replace('.', '/'))
        val artifactDir = File(groupDir, artifactId)
        val location = File(artifactDir, artifactVersion)
        File(location, "$artifactId-$artifactVersion.jar").assertExists()
    } + lunarLibs + remappedLunar + vpatcher

    println("Found all ${allLibs.size} libs, launching the game...")

    // Check if Solar Patcher exists
    try {
        Class.forName("com.grappenmaker.solarpatcher.AgentMain", false, ClassLoader.getSystemClassLoader())
        println("Solar Patcher was detected. Usage with this launcher is experimental!")
    } catch (e: ClassNotFoundException) {
        // Do nothing when class not found
    }

    // Create classloader with required libraries
    val loader = Loader(allLibs.map { it.toURI().toURL() }.toTypedArray())
    Thread.currentThread().contextClassLoader = loader

    // Launch game based on config, pass through all extra arguments
    val launchClass = Class.forName(versionConfig.getString("launch-class"), false, loader)
    val gameArgs = buildList {
        addAll(args.drop(2))

        val addConditional = { name: String, default: String ->
            if (!contains(name)) {
                add(name)
                add(default)
            }
        }

        addConditional("--accessToken", "0")
        addConditional("--version", version)

        val warnWhenMissing = { name: String, msg: String -> if (!contains(name)) println("Warning: $msg") }
        warnWhenMissing("--gameDir", "game is running in current directory; set a gamedir with --gameDir")
        warnWhenMissing("--assetsDir", "assets will be saved in current directory; set your assets dir with --assetsDir")
        warnWhenMissing("--texturesDir", "cosmetics will not work without a --texturesDir")

        warnWhenMissing("--assetIndex", "asset index not specified; automatically adding $version, might fail!")
        addConditional("--assetIndex", version)
    }.toTypedArray()

    // Use MethodHandles to minimize frames
    MethodHandles.lookup().findStatic(
        launchClass,
        "main",
        MethodType.methodType(Void::class.javaPrimitiveType, gameArgs::class.java)
    ).invokeExact(gameArgs)
}

class Loader(urls: Array<URL>) : URLClassLoader(urls) {
    private val patches =
        super.getResourceAsStream("optifinePatches.cfg")
            ?.bufferedReader()?.lineSequence()
            ?.filter { !it.startsWith("#") }
            ?.map {
                val (regex, mapped) = it.split("=")
                regex.trim().toRegex() to mapped.trim()
            }?.toList()

    // Optifine resources handler
    override fun getResourceAsStream(name: String): InputStream? {
        return super.getResourceAsStream(name)
            ?: Loader::class.java.classLoader.getResourceAsStream(name)
            ?: let {
                val patch = patches?.find { (pattern) -> pattern.matches(name) }
                patch?.second?.let { getResourceAsStream(it) }
            }
    }
}

// Utility to make sure certain files exist
private fun File.assertExists(): File {
    if (!exists()) exitWithError("File $absolutePath does not exist!")
    return this
}

// Exit the program with an error message
private fun exitWithError(msg: String): Nothing {
    System.err.println(msg)
    exitProcess(-1)
}