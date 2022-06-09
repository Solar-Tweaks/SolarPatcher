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
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type

internal val configDelegateClass: Class<*> by lazy {
    generateClass("${GeneratedCode.prefix}/ConfigDelegate", interfaces = arrayOf(getInternalName<IConfigDelegate>())) {
        fun MethodVisitor.getName() {
            loadVariable(1)
            visitTypeInsn(CHECKCAST, Bridge.playerEntityBridge.name)
            callBridgeMethod(Bridge.getPlayerNameMethod)
        }

        with(visitMethod(ACC_PUBLIC, "getPlayerConfig", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null)) {
            visitCode()
            // Delegate the call to a specialized class, no way I will implement this in bytecode
            getObject<ConfigFetcher>()
            getName()
            invokeMethod(ConfigFetcher::getConfig)
            returnMethod(ARETURN)

            visitMaxs(-1, -1)
            visitEnd()
        }

        with(
            visitMethod(
                ACC_PUBLIC,
                "setPlayerConfig",
                "(Ljava/lang/String;Ljava/lang/Object;)V",
                null,
                null
            )
        ) {
            visitCode()
            getObject<ConfigFetcher>()
            loadVariable(1)
            loadVariable(2)
            invokeMethod(ConfigFetcher::setConfig)
            returnMethod()

            visitMaxs(-1, -1)
            visitEnd()
        }

        with(
            visitMethod(
                ACC_PUBLIC,
                "downloadPlayerConfig",
                "(L$internalString;)Ljava/lang/Object;",
                null,
                null
            )
        ) {
            visitCode()
            val prefix =
                if (Accessors.Utility.getVersion() == "v1_7") "net/optifine" else "net/optifine/player"

            val httpPrefix = if (Accessors.Utility.getVersion() == "v1_7") "net/optifine" else "net/optifine/http"
            construct("$prefix/PlayerConfiguration", "()V")
            construct(
                "$httpPrefix/FileDownloadThread",
                "(Ljava/lang/String;L$httpPrefix/IFileDownloadListener;)V"
            ) {
                invokeMethod(
                    InvocationType.STATIC,
                    "getPlayerItemsUrl",
                    "()L$internalString;",
                    if (Accessors.Utility.getVersion() == "v1_7") "net/optifine/HttpUtils"
                    else "net/optifine/http/HttpUtils"
                )

                loadVariable(1)
                concat("(L$internalString;L$internalString;)L$internalString;", "\u0001/users/\u0001.cfg")
                construct("$prefix/PlayerConfigurationReceiver", "(L$internalString;)V") { loadVariable(1) }
            }

            invokeMethod(Thread::start)
            returnMethod(ARETURN)

            visitMaxs(-1, -1)
            visitEnd()
        }

        with(visitMethod(ACC_PUBLIC, "reloadPlayerCosmetics", "()V", null, null)) {
            visitCode()
            getObject<ConfigFetcher>()
            getProperty(ConfigFetcher::configs)
            getPlayerBridge()
            callBridgeMethod(Bridge.getPlayerNameMethod)
            invokeMethod(java.util.Map::class.java.getMethod("remove", Any::class.java))
            pop()

            val reloadCapeMethod = OtherRuntimeData.reloadCapeMethod?.asDescription()
            if (reloadCapeMethod != null) {
                getPlayerBridge()
                visitTypeInsn(CHECKCAST, Type.getArgumentTypes(reloadCapeMethod.descriptor).first().internalName)
                invokeMethod(InvocationType.STATIC, reloadCapeMethod)
            }

            returnMethod()

            visitMaxs(-1, -1)
            visitEnd()
        }

        implementGetVersion()
    }
}

interface IConfigDelegate {
    fun getPlayerConfig(player: Any): Any?
    fun setPlayerConfig(name: String, config: Any)
    fun downloadPlayerConfig(name: String): Any
    fun reloadPlayerCosmetics()
    fun getVersion(): String // Yes, this is a duplicate, there are reasons :D
}