package assembly

import javassist.bytecode.ClassFile
import javassist.bytecode.ConstPool
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.apache.maven.artifact.Artifact
import org.apache.maven.project.MavenProject
import org.eclipse.aether.RepositorySystemSession
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.*
import java.io.FileOutputStream
import java.util.jar.*


class Assembler(
        mavenProject: MavenProject,
        repoSession: RepositorySystemSession
) {

    private val localRepoPath = repoSession.localRepository.basedir.absolutePath

    private val mavenDependencies: MutableMap<String, String> = mutableMapOf()

    private val extraDependencies: MutableSet<String> = mutableSetOf()

    private val alreadyProcessed: MutableMap<String, MutableSet<String>> = mutableMapOf()

    init {
        val artifacts = mavenProject.artifacts as Set<Artifact>
        artifacts.forEach {
            processArtifact(it)
        }
    }

    fun assembleProject(paths: List<String>) {

        println("Processing Dependencies")
        val dependencies = paths.map {
            val fileName = it.substringAfterLast(File.separatorChar)
            val handlerStream = getClassFile(it)!!
            val dependencies = getDependenciesOfFile(handlerStream)
            dependencies.addAll(getRecursiveDependencies(dependencies))
            dependencies.addAll(extraDependencies)
            FileDependency(fileName, dependencies)
        }

        println("Creating Jars")

        runBlocking {
            dependencies.forEachParallel {
                createJar(it.dependencies, it.fileName)
            }
        }
    }

    private fun getRecursiveDependencies(classPaths: Set<String>): Set<String> {
        val set: MutableSet<String> = mutableSetOf()

        for (classPath in classPaths) {
            if (alreadyProcessed.contains(classPath)) {
                set.addAll(alreadyProcessed[classPath]!!)
                continue
            }
            val results = mutableSetOf<String>()

            alreadyProcessed[classPath] = results

            val dependencyPath = classPath.replace('.', File.separatorChar)
            val inputStream = getClassFile(dependencyPath) ?: continue

            val subSet = getDependenciesOfFile(inputStream)

            val subSetDependencies = getRecursiveDependencies(subSet)
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

    private fun processArtifact(artifact: Artifact) {

        val identifier = (artifact.groupId + "." + artifact.artifactId).replace(".", File.separator)

        val directoryPath = localRepoPath + File.separator + identifier + File.separator + artifact.version

        val directory = File(directoryPath)

        if (directory.listFiles() == null) return

        directory.listFiles().forEach { file ->
            if (file.extension == "jar") {
                val jarFile = JarFile(file)
                val entries = jarFile.entries()
                for (entry in entries) {
                    mavenDependencies[entry.name] = file.absolutePath
                    if (entry.name.contains("nimbuscore/annotation/annotations")) {
                        val nimbusAnnotationDependency = entry.name.replace('/', '.').substringBefore(".class")
                        extraDependencies.add(nimbusAnnotationDependency)
                    }
                }
            }
        }
    }

    private fun openJar(jarFilePath: String, pathInJar: String): InputStream {
        val jarFile = JarFile(jarFilePath)
        val jarEntry = jarFile.getJarEntry(pathInJar)
        return jarFile.getInputStream(jarEntry)
    }

    private fun createJar(dependencies: Set<String>, fileName: String) {
        val manifest = Manifest()
        val alreadyAdded: MutableSet<String> = mutableSetOf()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        val target = JarOutputStream(FileOutputStream("target/$fileName.jar"), manifest)
        val (jarToRequiredClasses, notFound) = createMapOfJarToRequiredClasses(dependencies)
        for ((jarPath, classes) in jarToRequiredClasses) {
            addFromJarToJar(jarPath, classes, target, alreadyAdded)
        }
        addLocalToJar(notFound, target, alreadyAdded)
        target.close()
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

    private fun addFromJarToJar(jarFilePath: String, jarDependencies: EntireJarDependency, target: JarOutputStream, alreadyAdded: MutableSet<String>) {
        val jarFile = JarFile(jarFilePath)
        if (jarDependencies.allClasses) {
            for (entry in jarFile.entries()) {
                if (!alreadyAdded.contains(entry.name)) {
                    if (!entry.name.contains("META-INF")) {
                        addInputStreamToJar(entry.name, entry.lastModifiedTime.toMillis(), jarFile.getInputStream(entry), target)
                        alreadyAdded.add(entry.name)
                    }
                }
            }
        } else {
            for (dependency in jarDependencies.specificClasses) {
                if (!alreadyAdded.contains(dependency)) {
                    val entry = jarFile.getJarEntry(dependency)
                    addInputStreamToJar(dependency, entry.lastModifiedTime.toMillis(), jarFile.getInputStream(entry), target)
                    alreadyAdded.add(dependency)
                }
            }
        }
    }

    private fun addLocalToJar(setOfClasses: Set<String>, target: JarOutputStream, alreadyAdded: MutableSet<String>) {
        for (dependency in setOfClasses) {
            if (!alreadyAdded.contains(dependency)) {
                val projectFilePath = "target" + File.separator + "classes" + File.separator + dependency.replace('/', File.separatorChar)
                val projectFile = File(projectFilePath)
                if (projectFile.exists()) {
                    addInputStreamToJar(dependency, projectFile.lastModified(), projectFile.inputStream(), target)
                    alreadyAdded.add(dependency)
                }
            }
        }
    }

    private fun addInputStreamToJar(path: String, lastModifiedTime: Long, inputStream: InputStream, target: JarOutputStream) {

        val newEntry = JarEntry(path)

        newEntry.time = lastModifiedTime

        target.putNextEntry(newEntry)


        val buffer = ByteArray(1024)
        while (true) {
            val count = inputStream.read(buffer)
            if (count == -1)
                break
            target.write(buffer, 0, count)
        }
        target.closeEntry()
        inputStream.close()
    }


    data class EntireJarDependency(val allClasses: Boolean, val specificClasses: MutableList<String>)

    data class FileDependency(val fileName: String, val dependencies: Set<String>)


    suspend fun <A> List<A>.forEachParallel(f: suspend (A) -> Unit): Unit = coroutineScope {
        map { async { f(it) } }.forEach { it.await() }
    }
}