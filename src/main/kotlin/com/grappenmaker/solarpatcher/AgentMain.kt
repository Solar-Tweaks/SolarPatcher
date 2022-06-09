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

@file:JvmName("AgentMain")

package com.grappenmaker.solarpatcher

import com.grappenmaker.solarpatcher.asm.transform.FileTransformer
import com.grappenmaker.solarpatcher.config.Configuration
import com.grappenmaker.solarpatcher.config.json
import com.grappenmaker.solarpatcher.util.LunarClassLoader
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.IOException
import java.lang.instrument.Instrumentation
import java.util.*
import javax.swing.JOptionPane

lateinit var configuration: Configuration

@Suppress("unused")
// premain method, see https://docs.oracle.com/javase/9/docs/api/java/lang/instrument/package-summary.html
fun premain(arg: String?, inst: Instrumentation) {
    println("Solar Patcher v${Versioning.version}")
    println("Built ${Date(Versioning.buildTimestamp)}")

    if (Versioning.devBuild) {
        println("Note: this build is a development build")
        println("This means that you most likely WON'T be granted ANY support")
        println("Use at your own discretion")
    }

    // Get the config based on the args
    val file = File(arg ?: "config.json")
    configuration = try {
        println("Attempting to read configuration from ${file.canonicalPath}")
        json.decodeFromString(file.readText())
    } catch (e: SerializationException) {
        e.printStackTrace()
        println("Something went wrong deserializing the config, error is above")
        println("Falling back to default config")

        errorPopup("Config appears to be invalid - please fix configuration issues (e.g. numeric values) or delete your config to fallback to default")
        Configuration()
    } catch (e: IOException) {
        e.printStackTrace()
        println("An IO error occured when loading the config, error is above")
        println("Falling back to default config")

        errorPopup("Using default config, pass a valid config! (When on Solar Tweaks, disable skip checks)")
        Configuration()
    }

    // Define transforms and visitors
    val transforms = configuration.enabledModules.also {
        val moduleText = it.map { m -> m::class.simpleName ?: "Unnamed" }.sorted().joinToString()
        println("Using modules $moduleText")
    }

    println("Launching Lunar Client")
    println()

    // Add the transforms to the instrumentation backend implementation of the jvm
    inst.addTransformer(FileTransformer(transforms, debug = configuration.debug))

    // Utility to store lunar client's class loader
    inst.addTransformer(LunarClassLoader)
}

private fun errorPopup(message: String) =
    JOptionPane.showMessageDialog(null, message, "SolarPatcher - Error", JOptionPane.ERROR_MESSAGE)