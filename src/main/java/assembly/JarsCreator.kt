package assembly

import assembly.models.JarDependencies
import assembly.models.JarDependency
import assembly.models.JarDrivers
import org.apache.maven.model.Resource
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import java.io.File
import java.io.InputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class JarsCreator(
        private val log: Log,
        mavenProject: MavenProject
){

    private val resourceDirectories = mavenProject.resources as List<Resource>

    fun createJars(jarDependencies: JarDependencies, outputs: List<JarOutputStream>) {
        val alreadyAdded = mutableSetOf<String>()
        var count = 1
        val total = jarDependencies.inJarDependencies.size

        val jarDrivers: MutableMap<JarOutputStream, JarDrivers> = mutableMapOf()
        outputs.forEach { jarDrivers[it] = JarDrivers() }

        jarDependencies.inJarDependencies.forEach { (sourceJar, dependency) ->
            log.info("Adding $sourceJar [$count/$total]")
            addFromJarToJar(sourceJar, dependency, alreadyAdded, jarDrivers)
            count++
        }

        jarDrivers.forEach { (outputStream, drivers) -> drivers.writeDrivers(outputStream) }

        log.info("Adding local project classes")
        jarDependencies.localDependencies.forEach { (classPath, targets) ->
            addLocalToJar(classPath, targets)
        }

        log.info("Adding resources")
        addResourcesToJars(outputs)

        outputs.forEach { it.close() }
    }



    private fun addFromJarToJar(jarFilePath: String, jarDependencies: JarDependency, alreadyAdded: MutableSet<String>, drivers: Map<JarOutputStream, JarDrivers>) {
        val jarFile = JarFile(jarFilePath)
        if (jarDependencies.allClasses.isNotEmpty()) {
            for (entry in jarFile.entries()) {
                if (entry.name != "META-INF/MANIFEST.MF" && entry.name != "META-INF" ) {
                    if (entry.name.endsWith(".driver", ignoreCase = true)) {
                        val contents = jarFile.getInputStream(entry).bufferedReader().use { it.readText() }
                        jarDependencies.allClasses.forEach {
                            drivers[it]?.addDriver(entry.name, contents)
                        }
                    } else if (!alreadyAdded.contains(entry.name)) {
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
}