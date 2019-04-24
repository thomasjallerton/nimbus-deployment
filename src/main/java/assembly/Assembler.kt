package assembly

import assembly.models.JarDependencies
import assembly.models.JarDependency
import configuration.EMPTY_CLIENTS
import javassist.bytecode.ClassFile
import javassist.bytecode.ConstPool
import org.apache.maven.artifact.Artifact
import org.apache.maven.model.Resource
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.component.annotations.Configuration
import org.eclipse.aether.RepositorySystemSession
import persisted.HandlerInformation
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.*
import java.io.FileOutputStream
import java.util.jar.*
import javax.tools.Diagnostic


class Assembler(
        mavenProject: MavenProject,
        repoSession: RepositorySystemSession,
        private val log: Log
) {

    private val localRepoPath = repoSession.localRepository.basedir.absolutePath

    private val mavenDependencies: MutableMap<String, String> = mutableMapOf()

    private val extraDependencies: MutableSet<String> = mutableSetOf()

    private val alreadyProcessed: MutableMap<String, MutableSet<String>> = mutableMapOf()

    private val resourceDirectories = mavenProject.resources as List<Resource>

    init {
        val artifacts = mavenProject.artifacts as Set<Artifact>

        artifacts.forEach {
            processArtifact(it)
        }
    }

    fun assembleProject(handlers: List<HandlerInformation>) {

        log.info("Processing Dependencies")

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


        log.info("Creating Jars")

        createJars(jarDependencies, outputs)
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

    private fun createJars(jarDependencies: JarDependencies, outputs: List<JarOutputStream>) {
        val alreadyAdded = mutableSetOf<String>()
        var count = 1
        val total = jarDependencies.inJarDependencies.size
        jarDependencies.inJarDependencies.forEach { (sourceJar, dependency) ->
            log.info("Adding $sourceJar [$count/$total]")
            addFromJarToJar(sourceJar, dependency, alreadyAdded)
            count++
        }

        log.info("Adding local project classes")
        jarDependencies.localDependencies.forEach { (classPath, targets) ->
            addLocalToJar(classPath, targets)
        }

        log.info("Adding resources")
        addResourcesToJars(outputs)

        outputs.forEach { it.close() }
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

    private fun addFromJarToJar(jarFilePath: String, jarDependencies: JarDependency, alreadyAdded: MutableSet<String>) {
        val jarFile = JarFile(jarFilePath)
        if (jarDependencies.allClasses.isNotEmpty()) {
            for (entry in jarFile.entries()) {
                if (entry.name != "META-INF/MANIFEST.MF" && entry.name != "META-INF" ) {
                    if (!alreadyAdded.contains(entry.name)) {
                        addInputStreamToJars(entry.name, entry.lastModifiedTime.toMillis(), jarFile.getInputStream(entry), jarDependencies.allClasses)
                        alreadyAdded.add(entry.name)
                    }
                }
            }
        }

        jarDependencies.classOutputs.forEach { (dependency, targets) ->
            if (!alreadyAdded.contains(dependency)) {
                val entry = jarFile.getJarEntry(dependency)
                addInputStreamToJars(dependency, entry.lastModifiedTime.toMillis(), jarFile.getInputStream(entry), targets)
                alreadyAdded.add(dependency)
            }
        }
    }

    private fun addLocalToJar(classPath: String, targets: List<JarOutputStream>) {
        val projectFilePath = "target" + File.separator + "classes" + File.separator + classPath.replace('/', File.separatorChar)
        val projectFile = File(projectFilePath)
        if (projectFile.exists()) {
            addInputStreamToJars(classPath, projectFile.lastModified(), projectFile.inputStream(), targets)
        }
    }


    private fun addInputStreamToJars(path: String, lastModifiedTime: Long, inputStream: InputStream, targets: List<JarOutputStream>) {
        val newEntry = JarEntry(path)

        newEntry.time = lastModifiedTime

        targets.forEach {
            it.putNextEntry(newEntry)
        }

        val buffer = ByteArray(1024)
        while (true) {
            val count = inputStream.read(buffer)
            if (count == -1) break
            targets.forEach {
                it.write(buffer, 0, count)
            }
        }
        targets.forEach {
            it.closeEntry()
        }
        inputStream.close()
    }

    private fun addResourcesToJars(targets: List<JarOutputStream>) {
        for (resourceDirectory in resourceDirectories) {
            val fileTargetDirectory = if (resourceDirectory.targetPath == null) {
                ""
            } else {
                resourceDirectory.targetPath
            }

            val rootDirectory = File(resourceDirectory.directory)
            rootDirectory.listFiles().forEach {
                addRecursiveFilesToJars(fileTargetDirectory, it, targets)
            }
        }
    }

    private fun addRecursiveFilesToJars(path: String, file: File, targets: List<JarOutputStream>) {
        val filePath = if (path == "") file.name else "$path/${file.name}"
        if (file.isDirectory) {
            file.listFiles().forEach {
                addRecursiveFilesToJars(filePath, it, targets)
            }
        } else if (file.isFile) {
            addInputStreamToJars(filePath, file.lastModified(), file.inputStream(), targets)
        }
    }

    data class EntireJarDependency(val allClasses: Boolean, val specificClasses: MutableList<String>)

}