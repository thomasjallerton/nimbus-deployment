package assembly

import assembly.models.JarDependencies
import assembly.models.JarDependency
import configuration.EMPTY_CLIENTS
import javassist.bytecode.ClassFile
import javassist.bytecode.ConstPool
import persisted.HandlerInformation
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.HashSet
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class DependencyProcessor(
        private val mavenDependencies: Map<String, String>,
        private val extraDependencies: Set<String>
) {

    private val alreadyProcessed: MutableMap<String, MutableSet<String>> = mutableMapOf()

    fun determineDependencies(handlers: Set<HandlerInformation>): Pair<List<JarOutputStream>, JarDependencies> {
        val jarDependencies = JarDependencies()
        val outputs = mutableListOf<JarOutputStream>()
        handlers.forEach { handler ->
            val fileName = handler.handlerClassPath.substringAfterLast(File.separatorChar)
            val handlerStream = getClassFile(handler.handlerClassPath)!!

            val dependencies = getDependenciesOfFile(handlerStream)
            dependencies.addAll(
                    handler.usesClients.flatMap { it.toClassPaths() }
            )
            dependencies.addAll(handler.extraDependencies)

            dependencies.addAll(getRecursiveDependencies(dependencies, handler.usesClients))
            dependencies.addAll(extraDependencies)

            val manifest = Manifest()
            val targetJar = JarOutputStream(FileOutputStream("target/$fileName.jar"), manifest)
            outputs.add(targetJar)

            val (requiredJar, externalDependencies) = createMapOfJarToRequiredClasses(dependencies)

            externalDependencies.forEach { classPath ->
                val targets = jarDependencies.localDependencies.getOrPut(classPath) { mutableListOf() }
                targets.add(targetJar)
            }

            requiredJar.forEach { (jarFile, jarDependency) ->
                val jarClasses = jarDependencies.inJarDependencies.getOrPut(jarFile) { JarDependency() }
                if (jarDependency.allClasses) {
                    jarClasses.allClasses.add(targetJar)
                } else {
                    jarDependency.specificClasses.forEach {
                        val listOfOutputs = jarClasses.classOutputs.getOrPut(it) { mutableListOf() }
                        listOfOutputs.add(targetJar)
                    }
                }
            }
        }
        return Pair(outputs, jarDependencies)
    }

    private fun createMapOfJarToRequiredClasses(dependencies: Set<String>): Pair<Map<String, EntireJarDependency>, Set<String>> {
        val result: MutableMap<String, EntireJarDependency> = mutableMapOf()
        val notFoundInJars: MutableSet<String> = mutableSetOf()
        for (dependency in dependencies) {
            val classFile = "${dependency.replace('.', '/')}.class"

            val jarFilePath = mavenDependencies[classFile]

            if (jarFilePath != null) {
                if (result.containsKey(jarFilePath)) {
                    result[jarFilePath]!!.specificClasses.add(classFile)
                } else {
                    if (dependency.startsWith("com.nimbusframework.nimbuscore")) {
                        result[jarFilePath] = EntireJarDependency(false, mutableListOf(classFile))
                    } else {
                        result[jarFilePath] = EntireJarDependency(true, mutableListOf(classFile))
                    }
                }
            } else {
                notFoundInJars.add(classFile)
            }
        }
        return Pair(result, notFoundInJars)
    }

    private fun getClassFile(path: String): InputStream? {
        if (path.startsWith("java")) return null

        val projectFilePath = "target" + File.separator + "classes" + File.separator + path + ".class"
        val projectFile = File(projectFilePath)
        if (projectFile.exists()) return projectFile.inputStream()

        val classFile = "${path.replace(File.separatorChar, '/')}.class"
        if (mavenDependencies.containsKey(classFile)) {
            val jarFilePath = mavenDependencies[classFile]!!
            return openJar(jarFilePath, classFile)
        }
        return null
    }

    private fun openJar(jarFilePath: String, pathInJar: String): InputStream {
        val jarFile = JarFile(jarFilePath)
        val jarEntry = jarFile.getJarEntry(pathInJar)
        return jarFile.getInputStream(jarEntry)
    }


    private fun getRecursiveDependencies(classPaths: Set<String>, clients: Set<ClientType>): Set<String> {
        val set: MutableSet<String> = mutableSetOf()

        for (classPath in classPaths) {
            if (alreadyProcessed.contains(classPath)) {
                set.addAll(alreadyProcessed[classPath]!!)
                continue
            }
            val results = mutableSetOf<String>()
            alreadyProcessed[classPath] = results

            //This is required if clients are declared in fields they do not throw class not found errors if the client is not used
            val subSet = if (classPath == "com.nimbusframework.nimbuscore.clients.ClientBuilder") {
                EMPTY_CLIENTS
            } else {
                val dependencyPath = classPath.replace('.', File.separatorChar)
                val inputStream = getClassFile(dependencyPath) ?: continue
                getDependenciesOfFile(inputStream)
            }

            val subSetDependencies = getRecursiveDependencies(subSet, clients)
            results.addAll(subSet)
            results.addAll(subSetDependencies)

            set.addAll(results)
        }
        return set
    }

    private fun getDependenciesOfFile(inputStream: InputStream): MutableSet<String> {
        val cf = ClassFile(DataInputStream(inputStream))
        val constPool = cf.constPool
        val set = HashSet<String>()
        for (ix in 1 until constPool.size) {
            val constTag = constPool.getTag(ix)
            if (constTag == ConstPool.CONST_Class) {
                set.add(constPool.getClassInfo(ix))
            } else {
                val descriptorIndex = when (constTag) {
                    ConstPool.CONST_NameAndType -> constPool.getNameAndTypeDescriptor(ix)
                    ConstPool.CONST_MethodType -> constPool.getMethodTypeInfo(ix)
                    else -> -1
                }

                if (descriptorIndex != -1) {
                    val desc = constPool.getUtf8Info(descriptorIndex)
                    var p = 0
                    while (p < desc.length) {
                        if (desc[p] == 'L') {
                            val semiColonIndex = desc.indexOf(';', p)
                            val toAdd = desc.substring(p + 1, semiColonIndex).replace('/', '.')
                            set.add(toAdd)
                            p = semiColonIndex
                        }
                        p++
                    }

                }

            }
        }
        inputStream.close()
        return set
    }

    data class EntireJarDependency(val allClasses: Boolean, val specificClasses: MutableList<String>)
}