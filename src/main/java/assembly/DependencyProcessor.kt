package assembly

import assembly.models.AssemblyDependencies
import assembly.models.JarDependency
import assembly.models.LocalResource
import assembly.models.MavenDependency
import com.nimbusframework.nimbuscore.persisted.ClientType
import com.nimbusframework.nimbuscore.persisted.HandlerInformation
import javassist.bytecode.ClassFile
import javassist.bytecode.ConstPool
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.HashSet
import java.util.jar.JarFile
import java.util.jar.JarOutputStream


class DependencyProcessor(
        private val mavenDependencies: Map<String, MavenDependency>,
        private val localResources: Map<String, String>,
        private val targetDirectory: String,
        private val addEntireJars: Boolean
) {

    private val alreadyProcessed: MutableMap<String, MutableSet<String>> = mutableMapOf()

    fun determineDependencies(handlers: Iterable<Pair<HandlerInformation, JarOutputStream>>): AssemblyDependencies {
        val assemblyDependencies = AssemblyDependencies()
        handlers.forEach { pair ->
            val handler = pair.first
            val targetJar = pair.second
            val handlerStream = getClassFile(classPathToJarPath(handler.handlerClassPath))!!

            val dependencies = getDependenciesOfClassFile(handlerStream)
            dependencies.addAll(
                    handler.usesClients.flatMap { it.toClassPaths() }.map { classPathToJarPath(it) }
            )

            val jarPathExtraDependencies = handler.extraDependencies.map { classPathToJarPath(it) }.toSet()

            dependencies.addAll(jarPathExtraDependencies)

            val stringDependencies = getRecursiveDependencies(dependencies, handler.usesClients)
            dependencies.addAll(stringDependencies)

            addToAssemblyDependencies(dependencies, jarPathExtraDependencies, assemblyDependencies, targetJar)
        }
        return assemblyDependencies
    }

    private fun addToAssemblyDependencies(dependencies: Set<String>, forceEntireJar: Set<String>, assemblyDependencies: AssemblyDependencies, targetJar: JarOutputStream) { //: Pair<Map<String, EntireJarDependency>, Set<String>> {
        val alreadyProcessedMavenDependency: MutableSet<MavenDependency> = mutableSetOf()

        for (dependency in dependencies) {
            val mavenDependency = mavenDependencies[dependency]

            if (mavenDependency != null) {
                val jarFilePath = mavenDependency.filePath

                val externalDependency = assemblyDependencies.externalDependencies.getOrPut(jarFilePath) { JarDependency() }
                if (forceEntireJar.contains(dependency) || addEntireJars) {
                    externalDependency.allClasses.add(targetJar)
                } else if (!externalDependency.allClasses.contains(targetJar)) {
                    externalDependency.specificClasses.getOrPut(dependency) { mutableListOf() }.add(targetJar)
                    if (!alreadyProcessedMavenDependency.contains(mavenDependency)) {
                        mavenDependency.requiredClasses.forEach {
                            val specificTargets = externalDependency.specificClasses.getOrPut(it) { mutableListOf() }
                            specificTargets.add(targetJar)
                        }
                    }
                }
                alreadyProcessedMavenDependency.add(mavenDependency)
            } else {
                if (localResources.contains(dependency)) {
                    assemblyDependencies.localResources.getOrPut(dependency) { LocalResource(localResources[dependency]!!) }.targets.add(targetJar)
                } else {
                    assemblyDependencies.localDependencies.getOrPut(dependency) { mutableListOf() }.add(targetJar)
                }
            }
        }
    }

    //Expects JAR path
    private fun getClassFile(path: String): InputStream? {
        if (path.startsWith("java") || !path.endsWith(".class")) return null

        val operatingSystemPath = path.replace('/', File.separatorChar)
        val projectFilePath = targetDirectory + File.separator + operatingSystemPath
        val projectFile = File(projectFilePath)
        if (projectFile.exists()) return projectFile.inputStream()

        val jarFilePath = mavenDependencies[path]?.filePath ?: return null

        val jarFile = JarFile(jarFilePath)
        val jarEntry = jarFile.getJarEntry(path)
        if (jarEntry.isDirectory) return null
        return jarFile.getInputStream(jarEntry)
    }

    private fun getOtherFile(path: String): InputStream? {
        val jarFilePath = mavenDependencies[path]?.filePath ?: return null

        val jarFile = JarFile(jarFilePath)
        val jarEntry = jarFile.getJarEntry(path)
        if (jarEntry.isDirectory) return null
        return jarFile.getInputStream(jarEntry)
    }

    //Return JAR path of files
    private fun getRecursiveDependencies(jarPaths: Set<String>, clients: Set<ClientType>): Set<String> {
        val dependencies: MutableSet<String> = mutableSetOf()

        for (jarPath in jarPaths) {
            if (alreadyProcessed.contains(jarPath)) {
                dependencies.addAll(alreadyProcessed[jarPath]!!)
                continue
            }
            val results = mutableSetOf<String>()
            alreadyProcessed[jarPath] = results

            //This is required if clients are declared in fields they do not throw class not found errors if the client is not used
            val classInputStream = getClassFile(jarPath)
            val directDependencies = if (classInputStream != null) {
                getDependenciesOfClassFile(classInputStream)
            } else {
                val handlerInputStream = getOtherFile(jarPath) ?: continue
                getDependenciesOfHandlerFile(handlerInputStream)
            }


            val recursiveDependencies = getRecursiveDependencies(directDependencies, clients)
            results.addAll(directDependencies)
            results.addAll(recursiveDependencies)

            dependencies.addAll(results)
        }
        return dependencies
    }

    // Returns a set of possible dependencies
    // Dependencies of form 'path.path.path.ClassName' (if class)
    // Or path/path/path/file.extension (if other type of file)
    private fun getDependenciesOfClassFile(inputStream: InputStream): MutableSet<String> {
        val cf = ClassFile(DataInputStream(inputStream))
        val constPool = cf.constPool
        val dependencies = HashSet<String>()
        for (ix in 1 until constPool.size) {
            val constTag = constPool.getTag(ix)

            if (constTag == ConstPool.CONST_Class) {
                //Always a class
                val constClass = classPathToJarPath(constPool.getClassInfo(ix))
                dependencies.add(constClass)
            } else if (constTag == ConstPool.CONST_String) {
                //Could be any kind of file
                val possibleDependency = constPool.getStringInfo(ix)
                if (possibleDependency != null) {
                    addAnyFileToDependencySet(possibleDependency, dependencies)
                }
            } else if (constTag == ConstPool.CONST_Utf8) {
                //Could be any kind of file

                val desc = constPool.getUtf8Info(ix)
                addAnyFileToDependencySet(desc, dependencies)
                addAnyClassToDependencySet(desc, dependencies)
            } else {
                val descriptorIndex = when (constTag) {
                    ConstPool.CONST_NameAndType -> constPool.getNameAndTypeDescriptor(ix)
                    ConstPool.CONST_MethodType -> constPool.getMethodTypeInfo(ix)
                    else -> -1
                }
                if (descriptorIndex != -1) {
                    //Guaranteed to be class
                    val desc = constPool.getUtf8Info(descriptorIndex)
                    addAnyClassToDependencySet(desc, dependencies)
                }

            }
        }
        inputStream.close()
        return dependencies
    }

    private fun getDependenciesOfHandlerFile(inputStream: InputStream): Set<String> {
        val dependencies = HashSet<String>()

        inputStream.bufferedReader().useLines { lines ->
            lines.forEach {
                addAnyFileToDependencySet(it, dependencies)
            }
        }

        return dependencies
    }

    private fun addAnyClassToDependencySet(desc: String, dependencies: MutableSet<String>) {
        var p = 0
        while (p < desc.length) {
            //Will always be a class (path/path/path/class)
            if (desc[p] == 'L') {
                val semiColonIndex = desc.indexOf(';', p)
                if (semiColonIndex < 0) break

                val typeIndex = desc.indexOf('<', p)
                val endIndex = if (typeIndex in (p + 1) until semiColonIndex) {
                    typeIndex
                } else {
                    semiColonIndex
                }
                val toAdd = desc.substring(p + 1, endIndex) + ".class"
                dependencies.add(toAdd)
                p = endIndex
            }
            p++
        }
    }

    private fun addAnyFileToDependencySet(possibleDependency: String, dependencies: MutableSet<String>) {
        val noLeadingSeparator = removeLeadingSeparator(possibleDependency)
        if (mavenDependencies.containsKey(noLeadingSeparator) || localResources.containsKey(noLeadingSeparator)) {
            dependencies.add(noLeadingSeparator)
        }
        val transformedClassPath = classPathToJarPath(noLeadingSeparator)
        if (mavenDependencies.containsKey(transformedClassPath)) {
            dependencies.add(transformedClassPath)
        }
    }

    private fun removeLeadingSeparator(path: String): String {
        return if (path.startsWith('/')) {
            path.substring(1, path.length)
        } else {
            path
        }
    }

    private fun classPathToJarPath(classPath: String): String {
        return classPath.replace('.', '/') + ".class"
    }
}