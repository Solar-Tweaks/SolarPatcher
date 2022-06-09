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

package com.grappenmaker.solarpatcher.asm.util

import com.grappenmaker.solarpatcher.asm.FieldDescription
import com.grappenmaker.solarpatcher.asm.isStatic
import com.grappenmaker.solarpatcher.asm.method.InvocationType
import com.grappenmaker.solarpatcher.asm.method.MethodDescription
import com.grappenmaker.solarpatcher.asm.method.invocationType
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.MethodNode
import java.io.PrintStream
import java.lang.invoke.StringConcatFactory
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaMethod

val outField: Field by lazy { System::class.java.getField("out") }
val printlnMethod: Method by lazy { PrintStream::class.java.getMethod("println", String::class.java) }
val printMethod: Method by lazy { PrintStream::class.java.getMethod("print", String::class.java) }
val concatHandle: Handle by lazy {
    val method = StringConcatFactory::makeConcatWithConstants.javaMethod!!
    Handle(
        H_INVOKESTATIC,
        getInternalName<StringConcatFactory>(),
        method.name,
        Type.getMethodDescriptor(method),
        false
    )
}

// Makes a methodvisitor invoke a given method
// Caller is responsible for providing arguments and a target, if applicable
fun MethodVisitor.invokeMethod(method: Method) = visitMethodInsn(
    method.invocationType.opcode,
    method.declaringClass.internalName,
    method.name,
    Type.getMethodDescriptor(method),
    method.declaringClass.isInterface
)

fun <R> MethodVisitor.invokeMethod(func: KFunction<R>) =
    invokeMethod(func.javaMethod ?: error("No valid java method available"))

fun MethodVisitor.invokeMethod(invocationType: InvocationType, name: String, descriptor: String, owner: String) =
    visitMethodInsn(invocationType.opcode, owner, name, descriptor, invocationType == InvocationType.INTERFACE)

fun MethodVisitor.invokeMethod(invocationType: InvocationType, desc: MethodDescription) {
    val (name, descriptor, owner) = desc
    invokeMethod(invocationType, name, descriptor, owner)
}

// Makes a methodvisitor get the value of a field, and store it on the operand stack
// Caller is responsible for providing a target, if applicable
fun MethodVisitor.getField(field: Field) =
    visitFieldInsn(
        if (field.isStatic) GETSTATIC else GETFIELD,
        field.declaringClass.internalName,
        field.name,
        Type.getDescriptor(field.type)
    )

fun MethodVisitor.getProperty(prop: KProperty<*>) = invokeMethod(prop.getter)

fun MethodVisitor.getField(desc: FieldDescription, static: Boolean = desc.access and ACC_STATIC != 0) =
    visitFieldInsn(if (static) GETSTATIC else GETFIELD, desc.owner, desc.name, desc.descriptor)

// Makes a methodvisitor get set value of a field
// Caller is responsible for providing a value and target
fun MethodVisitor.setField(field: Field) =
    visitFieldInsn(
        if (Modifier.isStatic(field.modifiers)) PUTSTATIC else PUTFIELD,
        field.declaringClass.internalName,
        field.name,
        Type.getDescriptor(field.type)
    )

fun MethodVisitor.setField(desc: FieldDescription, static: Boolean = desc.access and ACC_STATIC != 0) =
    visitFieldInsn(if (static) PUTSTATIC else PUTFIELD, desc.owner, desc.name, desc.descriptor)

// Makes a methodvisitor construct a class with a given constructor.
// New instance will be pushed onto the stack
// Arguments for the constructor are initialized with the given block
inline fun MethodVisitor.construct(constructor: Constructor<*>, block: MethodVisitor.() -> Unit = {}) {
    visitTypeInsn(NEW, constructor.declaringClass.internalName)
    dup()
    block()
    visitMethodInsn(
        INVOKESPECIAL,
        constructor.declaringClass.internalName,
        "<init>",
        Type.getConstructorDescriptor(constructor),
        false
    )
}

inline fun MethodVisitor.construct(func: KFunction<Unit>, block: MethodVisitor.() -> Unit = {}) =
    construct(func.javaConstructor!!, block)

inline fun MethodVisitor.construct(className: String, descriptor: String, block: MethodVisitor.() -> Unit = {}) {
    visitTypeInsn(NEW, className)
    dup()
    block()
    visitMethodInsn(INVOKESPECIAL, className, "<init>", descriptor, false)
}

// Makes a methodvisitor get the value of the stdout stream
fun MethodVisitor.getOut() = getField(outField)

// Makes a methodvisitor print something (with newline)
// Block is used to initialize the value of the printed text
// Caller must make sure that the first value of the stack is a string
inline fun MethodVisitor.visitPrintln(block: MethodVisitor.() -> Unit) {
    getOut()
    block()
    invokeMethod(printlnMethod)
}

fun MethodVisitor.visitPrintln(string: String) = visitPrintln { visitLdcInsn(string) }

// Makes a methodvisitor print something
// Block is used to initialize the value of the printed text
// Caller must make sure that the first value of the stack is a string
inline fun MethodVisitor.visitPrint(block: MethodVisitor.() -> Unit) {
    getOut()
    block()
    invokeMethod(printMethod)
}

fun MethodVisitor.visitPrint(string: String) = visitPrint { visitLdcInsn(string) }

// Utility to pop n elements from the stack
fun MethodVisitor.pop(n: Int = 1) {
    require(n >= 1) { "Can only pop at least one element from the stack" }
    repeat(n) { visitInsn(POP) }
}

// Utility to duplicate n elements onto the stack
fun MethodVisitor.dup(n: Int = 1) {
    require(n >= 1) { "At least one duplication should be performed" }
    repeat(n) { visitInsn(DUP) }
}

// Utility to return the method
// Return type must be valid
fun MethodVisitor.returnMethod(opcode: Int = RETURN) {
    require(opcode in (IRETURN..RETURN)) { "Must be valid return" }
    visitInsn(opcode)
}

// Utililty to load a local variable or parameter
fun MethodVisitor.loadVariable(num: Int, opcode: Int = ALOAD) {
    require(opcode in (ILOAD..ALOAD)) { "Must be valid load operation" }
    visitVarInsn(opcode, num)
}

// Utililty to store a local variable or parameter
fun MethodVisitor.storeVariable(num: Int, opcode: Int = ASTORE) {
    require(opcode in (ISTORE..ASTORE)) { "Must be valid store operation" }
    visitVarInsn(opcode, num)
}

// Utility to concat something with constants
fun MethodVisitor.concat(desc: String, template: String) =
    visitInvokeDynamicInsn(concatHandle.name, desc, concatHandle, template)

// Utility to convert integer on operand stack to string
fun MethodVisitor.intToString() =
    invokeMethod(java.lang.Integer::class.java.getMethod("toString", Int::class.javaPrimitiveType))

// Utility to convert the current object on the stack to a string
fun MethodVisitor.visitToString() = invokeMethod(Any::toString)

// Utility to box an integer on operand stack
fun MethodVisitor.boxInt() =
    invokeMethod(java.lang.Integer::class.java.getMethod("valueOf", Int::class.javaPrimitiveType))

// Utility to get an object instance
fun MethodVisitor.getObject(kClass: KClass<*>) =
    getField(kClass.java.getField("INSTANCE"))

inline fun <reified T> MethodVisitor.getObject() = getObject(T::class)

// Utility to load a given value as constant onto the stack
// Warning: because of optimizations, this code is huge
fun MethodVisitor.loadConstant(value: Any?) = when (value) {
    null -> visitInsn(ACONST_NULL)
    true -> visitInsn(ICONST_1)
    false -> visitInsn(ICONST_0)
    is Byte -> {
        visitIntInsn(BIPUSH, value.toInt())
        visitInsn(I2B)
    }
    is Int -> when (value) {
        -1 -> visitInsn(ICONST_M1)
        0 -> visitInsn(ICONST_0)
        1 -> visitInsn(ICONST_1)
        2 -> visitInsn(ICONST_2)
        3 -> visitInsn(ICONST_3)
        4 -> visitInsn(ICONST_4)
        5 -> visitInsn(ICONST_5)
        in Byte.MIN_VALUE..Byte.MAX_VALUE -> visitIntInsn(BIPUSH, value)
        in Short.MIN_VALUE..Short.MAX_VALUE -> visitIntInsn(SIPUSH, value)
        else -> visitLdcInsn(value)
    }
    is Float -> when (value) {
        0f -> visitInsn(FCONST_0)
        1f -> visitInsn(FCONST_1)
        2f -> visitInsn(FCONST_2)
        else -> visitLdcInsn(value)
    }
    is Double -> when (value) {
        0.0 -> visitInsn(DCONST_0)
        1.0 -> visitInsn(DCONST_1)
        else -> visitLdcInsn(value)
    }
    is Long -> when (value) {
        0L -> visitInsn(LCONST_0)
        1L -> visitInsn(LCONST_1)
        in Byte.MIN_VALUE..Byte.MAX_VALUE -> {
            visitIntInsn(BIPUSH, value.toInt())
            visitInsn(I2L)
        }
        in Short.MIN_VALUE..Short.MAX_VALUE -> {
            visitIntInsn(SIPUSH, value.toInt())
            visitInsn(I2L)
        }
        else -> visitLdcInsn(value)
    }
    is Char -> {
        visitIntInsn(BIPUSH, value.code)
        visitInsn(I2C)
    }
    is Short -> {
        visitIntInsn(SIPUSH, value.toInt())
        visitInsn(I2S)
    }
    is String -> visitLdcInsn(value)
    else -> error("Constant value ($value) is not a valid JVM constant!")
}

// Utility to create a lookup switch
// Used because apparently entries have to be sorted, and i forget that
// Int needs to be on stack
fun MethodVisitor.createLookupSwitch(default: Label, cases: Map<Int, MethodVisitor.() -> Unit>) {
    val sorted = cases.toSortedMap()
    val entries = sorted.values.map {
        val label = Label()
        label to {
            visitLabel(label)
            it()
        }
    }

    visitLookupSwitchInsn(default, sorted.keys.toIntArray(), entries.map { it.first }.toTypedArray())
    entries.forEach { (_, code) -> code() }
}

// Utility to implement a stub value return
fun MethodVisitor.returnStub(returnType: Type) = when (returnType.sort) {
    Type.VOID -> visitInsn(RETURN)
    Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.CHAR, Type.INT -> {
        visitInsn(ICONST_0)
        visitInsn(IRETURN)
    }
    Type.FLOAT -> {
        visitInsn(FCONST_0)
        visitInsn(FRETURN)
    }
    Type.DOUBLE -> {
        visitInsn(DCONST_0)
        visitInsn(DRETURN)
    }
    Type.LONG -> {
        visitInsn(LCONST_0)
        visitInsn(LRETURN)
    }
    Type.OBJECT, Type.ARRAY -> {
        visitInsn(ACONST_NULL)
        visitInsn(ARETURN)
    }
    else -> error("Impossible")
}