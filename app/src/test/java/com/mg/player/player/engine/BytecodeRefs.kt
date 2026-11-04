package com.mg.player.player.engine

import java.io.File
import java.util.jar.JarFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/** Liest den Bytecode einer eingebundenen Bibliothek und sammelt alle Klassen-, Methoden- und Feldverweise. */
object BytecodeRefs {

    data class Ref(val owner: String, val name: String, val desc: String, val field: Boolean, val from: String)

    /** Alle Klassen unter [packagePrefix] aus dem Jar bzw. Ordner, in dem [anchor] liegt. */
    fun classBytes(anchor: Class<*>, packagePrefix: String): Map<String, ByteArray> {
        val location = File(anchor.protectionDomain!!.codeSource!!.location.toURI())
        val out = sortedMapOf<String, ByteArray>()
        if (location.isDirectory) {
            location.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { file ->
                val name = file.relativeTo(location).invariantSeparatorsPath.removeSuffix(".class")
                if (name.startsWith(packagePrefix)) out[name] = file.readBytes()
            }
        } else {
            JarFile(location).use { jar ->
                jar.entries().asSequence()
                    .filter { it.name.startsWith(packagePrefix) && it.name.endsWith(".class") }
                    .forEach { out[it.name.removeSuffix(".class")] = jar.getInputStream(it).readBytes() }
            }
        }
        return out
    }

    /** Verweise auf Mitglieder (Methoden/Felder) und benutzte Klassen (Oberklassen, Schnittstellen, new/cast/ldc). */
    fun collect(classes: Map<String, ByteArray>): Pair<Set<Ref>, Set<String>> {
        val refs = mutableSetOf<Ref>()
        val owners = mutableSetOf<String>()
        classes.forEach { (className, bytes) -> collect(className, bytes, refs, owners) }
        return refs to owners
    }

    private fun collect(className: String, bytes: ByteArray, refs: MutableSet<Ref>, owners: MutableSet<String>) {
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(v: Int, a: Int, n: String, s: String?, superName: String?, ifaces: Array<out String>?) {
                    superName?.let { owners += it }
                    ifaces?.forEach { owners += it }
                }

                override fun visitMethod(a: Int, n: String, d: String, s: String?, e: Array<out String>?): MethodVisitor {
                    val from = "$className.$n"
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(op: Int, owner: String, name: String, desc: String, itf: Boolean) {
                            if (!owner.startsWith("[")) refs += Ref(owner, name, desc, false, from)
                        }

                        override fun visitFieldInsn(op: Int, owner: String, name: String, desc: String) {
                            refs += Ref(owner, name, desc, true, from)
                        }

                        override fun visitTypeInsn(op: Int, type: String) {
                            if (!type.startsWith("[")) owners += type
                        }

                        override fun visitLdcInsn(value: Any?) {
                            if (value is Type && value.sort == Type.OBJECT) owners += value.internalName
                        }

                        override fun visitInvokeDynamicInsn(name: String, desc: String, bsm: Handle, vararg args: Any?) {
                            args.filterIsInstance<Handle>().forEach { h ->
                                refs += Ref(h.owner, h.name, h.desc, h.tag <= Opcodes.H_PUTSTATIC, from)
                            }
                        }
                    }
                }
            },
            ClassReader.SKIP_FRAMES,
        )
    }
}
