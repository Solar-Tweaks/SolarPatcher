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
import com.grappenmaker.solarpatcher.asm.util.getField
import com.grappenmaker.solarpatcher.asm.util.invokeMethod
import com.grappenmaker.solarpatcher.asm.util.loadVariable
import com.grappenmaker.solarpatcher.asm.util.returnMethod
import com.grappenmaker.solarpatcher.modules.OtherRuntimeData
import com.grappenmaker.solarpatcher.modules.internalString
import com.grappenmaker.solarpatcher.util.LunarClassLoader
import com.grappenmaker.solarpatcher.util.ensure
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.util.TraceClassVisitor
import java.io.PrintWriter

// Object containing a class loader and utility to generate a class,
// and use it in your code
object GeneratedCode {
    const val prefix = "com/grappenmaker/solarpatcher/generated"
    private val loader = object : ClassLoader(LunarClassLoader.loader!!) {
        fun createClass(name: String, file: ByteArray): Class<*> =
            defineClass(name, file, 0, file.size).also { resolveClass(it) }
    }

    // I don't know why, but I need to use a delegate like this
    fun createClass(name: String, file: ByteArray) = loader.createClass(name, file)
}

// Utility to generate a class
// Automatically defines a constructor for you
internal inline fun generateClass(
    name: String,
    extends: String = "java/lang/Object",
    interfaces: Array<String> = arrayOf(),
    createConstructor: Boolean = true,
    generator: ClassVisitor.() -> Unit
): Class<*> {
    val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
    val visitor = TraceClassVisitor(writer, PrintWriter(System.out))
    visitor.visit(Opcodes.V9, Opcodes.ACC_PUBLIC, name, null, extends, interfaces)

    try {
        visitor.generator()
    } catch (e: Exception) {
        println("Error while generating class $name: ${e.message}")
        e.printStackTrace()
        throw e
    }

    if (createConstructor) {
        with(visitor.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)) {
            visitCode()
            loadVariable(0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, extends, "<init>", "()V", false)
            returnMethod()
            visitMaxs(1, 0)
            visitEnd()
        }
    }

    visitor.visitEnd()
    return GeneratedCode.createClass(name.replace('/', '.'), writer.toByteArray())
}

// Utility function to generate a getVersion method
internal fun ClassVisitor.implementGetVersion() =
    with(visitMethod(Opcodes.ACC_PUBLIC, "getVersion", "()L$internalString;", null, null)) {
        visitCode()
        invokeMethod(
            InvocationType.STATIC,
            OtherRuntimeData.getVersionMethod.ensure().asDescription()
        )
        getField(
            OtherRuntimeData.versionIdField ?: error("No version id field was found!"),
            static = false
        )
        returnMethod(Opcodes.ARETURN)
        visitMaxs(-1, -1)
        visitEnd()
    }