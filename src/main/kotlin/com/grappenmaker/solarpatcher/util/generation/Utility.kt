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

package com.grappenmaker.solarpatcher.util.generation

import com.grappenmaker.solarpatcher.asm.method.InvocationType
import com.grappenmaker.solarpatcher.asm.util.*
import com.grappenmaker.solarpatcher.modules.*
import com.grappenmaker.solarpatcher.util.ensure
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.*

// Generated class supplying various utility functions
internal val utilityClass by lazy {
    generateClass("${GeneratedCode.prefix}/Utility", interfaces = arrayOf(getInternalName<IUtility>())) {
        with(visitMethod(Opcodes.ACC_PUBLIC, "getPlayerName", "()L$internalString;", null, null)) {
            visitCode()
            getPlayerBridge()
            callBridgeMethod(Bridge.getPlayerNameMethod)
            returnMethod(Opcodes.ARETURN)
            visitMaxs(-1, -1)
            visitEnd()
        }

        with(visitMethod(Opcodes.ACC_PUBLIC, "getPlayerUUID", "()L${getInternalName<UUID>()};", null, null)) {
            visitCode()
            getPlayerBridge()
            callBridgeMethod(Bridge.getUUIDMethod)
            returnMethod(Opcodes.ARETURN)
            visitMaxs(-1, -1)
            visitEnd()
        }

        with(visitMethod(Opcodes.ACC_PUBLIC, "getServerIP", "()L$internalString;", null, null)) {
            visitCode()

            val label = Label()

            getServerData()
            dup()
            visitJumpInsn(Opcodes.IFNULL, label)

            callBridgeMethod(Bridge.getServerIPMethod)
            returnMethod(Opcodes.ARETURN)

            visitLabel(label)
            pop()
            visitInsn(Opcodes.ACONST_NULL)
            returnMethod(Opcodes.ARETURN)

            visitMaxs(-1, -1)
            visitEnd()
        }

        // Arguments:
        // 0: this (automatically stacked by jvm)
        // 1: title: java/lang/String
        // 2: description: java/lang/String
        with(visitMethod(Opcodes.ACC_PUBLIC, "displayPopup", "(L$internalString;L$internalString;)V", null, null)) {
            visitCode()

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

                // Load description onto operand stack
                loadVariable(2)
            }

            // Fake send packet
            invokeMethod(InvocationType.VIRTUAL, popupMethod.asDescription())
            returnMethod(Opcodes.RETURN)

            visitMaxs(-1, -1)
            visitEnd()
        }

        with(visitMethod(Opcodes.ACC_PUBLIC, "getMCDataDir", "()Ljava/io/File;", null, null)) {
            visitCode()

            getClientBridge()
            callBridgeMethod(Bridge.getGameDirMethod)
            returnMethod(Opcodes.ARETURN)
            visitMaxs(2, 1)
            visitEnd()
        }

        implementGetVersion()
    }
}

interface IUtility {
    fun getPlayerName(): String
    fun getPlayerUUID(): UUID
    fun getServerIP(): String?
    fun getVersion(): String
    fun getMCDataDir(): File
    fun displayPopup(title: String, description: String)
}