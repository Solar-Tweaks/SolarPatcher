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

// Constant values
object Constants {
    const val premainClass = "com.grappenmaker.solarpatcher.AgentMain"
    const val saveConfigClass = "com.grappenmaker.solarpatcher.config.SaveDefaultConfig"
    const val defaultConfig = "config.example.json"
    const val updaterConfig = "updater.json"
}

// Versions of dependencies
object Versions {
    const val project = "1.7"
    const val kotlin = "1.7.0"
    const val serializationJSON = "1.3.2"
    const val asm = "9.2"
    const val detekt = "1.19.0"
}