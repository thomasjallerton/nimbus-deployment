package assembly

import assembly.models.AssemblyDependencies
import assembly.models.JarDependency
import assembly.models.JarDrivers
import org.apache.maven.model.Resource
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import services.FileService
import java.io.File
import java.io.InputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class JarsCreator(
        private val log: Log,
        private val targetDirectory: String
) {

    fun createJars(assemblyDependencies: AssemblyDependencies, outputs: List<JarOutputStream>) {
        val alreadyAdded = mutableSetOf<String>()
        var count = 1
        val total = assemblyDependencies.externalDependencies.size

        val jarDrivers: MutableMap<JarOutputStream, JarDrivers> = mutableMapOf()
        outputs.forEach { jarDrivers[it] = JarDrivers() }

        assemblyDependencies.externalDependencies.forEach { (sourceJar, dependency) ->
            log.info("Adding $sourceJar [$count/$total]")
            addFromJarToJar(sourceJar, dependency, alreadyAdded, jarDrivers)
            count++
        }

        jarDrivers.forEach { (outputStream, drivers) -> drivers.writeDrivers(outputStream) }

        log.info("Adding local project classes")
        assemblyDependencies.localDependencies.forEach { (classPath, targets) ->
            addLocalClassToJar(classPath, targets)
        }

        log.info("Adding local resources")
        assemblyDependencies.localResources.forEach { (classPath, localResource) ->
            addLocalResourceToJar(classPath, localResource.filePath, localResource.targets)
        }
    }


    private fun addFromJarToJar(jarFilePath: String, jarDependencies: JarDependency, alreadyAdded: MutableSet<String>, drivers: Map<JarOutputStream, JarDrivers>) {
        val jarFile = JarFile(jarFilePath)
        if (jarDependencies.allClasses.isNotEmpty()) {
            for (entry in jarFile.entries()) {
                if (entry.name != "META-INF/MANIFEST.MF" && entry.name != "META-INF") {
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
        } else {
            jarDependencies.specificClasses.forEach { (dependency, targets) ->
                if (!alreadyAdded.contains(dependency)) {
                    val entry = jarFile.getJarEntry(dependency)
                    if (dependency.endsWith(".driver", ignoreCase = true)) {
                        val contents = jarFile.getInputStream(entry).bufferedReader().use { it.readText() }
                        targets.forEach {
                            drivers[it]?.addDriver(entry.name, contents)
                        }
                    } else if (!alreadyAdded.contains(dependency)) {
                        addInputStreamToJars(dependency, entry.lastModifiedTime.toMillis(), jarFile.getInputStream(entry), targets)
                        alreadyAdded.add(dependency)
                    }
                }
            }
        }
    }

    private fun addLocalClassToJar(jarPath: String, targets: List<JarOutputStream>) {
        val projectFilePath = targetDirectory + jarPath.replace('/', File.separatorChar)
        val projectFile = File(projectFilePath)
        if (projectFile.exists()) {
            addInputStreamToJars(jarPath, projectFile.lastModified(), projectFile.inputStream(), targets)
        }
    }

    private fun addLocalResourceToJar(targetPath: String, localPath: String, targets: List<JarOutputStream>) {
        val projectFile = File(localPath)
        if (projectFile.exists()) {
            addInputStreamToJars(targetPath, projectFile.lastModified(), projectFile.inputStream(), targets)
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
}