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

@file:JvmName("LunarMapper")

package com.grappenmaker.solarpatcher.util

import io.sigpipe.jbsdiff.Patch
import java.io.File
import java.util.*
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

// Utility to map the lunar client classes, so the output jar contains
// classes how they look on runtime.

// Usage:
// java -cp lunar-util-vX.X.jar com.grappenmaker.solarpatcher.util.LunarMapper
// /path/to/lunar-prod-optifine.jar /path/to/1.8.9.jar (or other version) output.jar
fun main(args: Array<String>) {
    val fileName = args.firstOrNull() ?: "lunar-prod-optifine.jar"
    val file = File(fileName)
    if (!file.exists()) exitWithError("File $fileName does not exist! Please provide a valid file.")

    val minecraftFileName = args.getOrNull(1) ?: exitWithError("Please specify a <version>.jar!")
    val minecraftFile = File(minecraftFileName)
    if (!minecraftFile.exists())
        exitWithError("File $minecraftFileName does not exist! Please provide a valid minecraft jar!")

    // Load JarFile and patches
    val jar = JarFile(file)
    val patchEntry = jar.getPatchEntry("patches")
    val mappingsEntry = jar.getPatchEntry("mappings")

    // Load patches
    val patches =
        jar.loadPatches(patchEntry) { (name, patch) -> name.toInternalName() to Base64.getDecoder().decode(patch) }
    println("Loaded ${patches.size} patches")

    // Load mappings
    val mappings = jar.loadPatches(mappingsEntry) { (k, v) -> v.toInternalName() to k.toInternalName() }

    // Load all classes, and patch when necessary
    val mcJar = JarFile(minecraftFile)
    val (classes, resources) = (jar.load() + mcJar.load())
        .partition { (e) -> e.name.endsWith(".lclass") || e.name.endsWith(".class") }

    mcJar.close()

    val (toPatch, passThroughClasses) = classes
        .map { (e, f) -> e.name.substringBeforeLast('.') to f }
        .partition { (name) -> name in patches && isAllowed(name) }

    val passThrough = (resources
        .map { (e, f) -> e.name to f } + passThroughClasses
        .map { (name, f) -> "$name.class" to f })
        .filter { (name) -> !name.contains("META-INF") }

    println("Loaded ${classes.size} classes, ${toPatch.size} need to be patched")
    jar.close()

    // Create new jarfile, and save pass through classes
    val output = File(args.getOrElse(2) { "${file.nameWithoutExtension}-remapped.jar" })
        .also { if (it.exists()) it.delete() }

    val outputJar = JarOutputStream(output.outputStream())
    val passthroughTime =
        measureTimeMillis { passThrough.forEach { (name, file) -> outputJar.writeFile(name, file) } }

    println("Took ${passthroughTime}ms to write passthrough resources")

    println("Written ${passThrough.size} files without patching")
    println("Starting to patch...")

    val patchTime = measureTimeMillis {
        toPatch.map { (name, file) ->
            val patch = patches[name] ?: error("Impossible")
            val mappedName = mappings[name] ?: name

            outputJar.putNextEntry(JarEntry("$mappedName.class"))
            when (mappedName) {
                "net/optifine/Config" -> outputJar.write(file)
                else -> Patch.patch(file, patch, outputJar)
            }

            outputJar.closeEntry()
        }
    }

    println("Took ${patchTime}ms to patch classes")

    println("Writing file...")
    outputJar.close()
    println("Done!")
}

fun JarFile.load() = entries().asSequence()
    .map { it to getInputStream(it).readBytes() }

fun JarOutputStream.writeFile(name: String, file: ByteArray) {
    putNextEntry(JarEntry(name))
    write(file)
    closeEntry()
}

fun JarFile.getPatchEntry(name: String) = entries().asSequence().find {
    !it.isDirectory
            && it.name.startsWith("patch/")
            && it.name.endsWith("$name.txt")
} ?: exitWithError("No patch file was found!")

inline fun <K, V> JarFile.loadPatches(
    entry: JarEntry,
    transform: (Pair<String, String>) -> Pair<K, V>
): Map<K, V> = getInputStream(entry).bufferedReader().readLines()
    .filter { it.isNotEmpty() }
    .associate {
        val (key, value) = it.split(" ")
        transform(key to value)
    }

private fun exitWithError(msg: String): Nothing {
    System.err.println(msg)
    exitProcess(-1)
}

fun String.toInternalName() = replace('.', '/')
fun String.toBinaryName() = replace('/', '.')

val disallowed = listOf("net/minecraftforge/", "net/optifine/", "shadersmod/", "org/spongepowered/")
fun isAllowed(name: String) = disallowed.none { name.startsWith(it) }