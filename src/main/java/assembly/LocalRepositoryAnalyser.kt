package assembly

import org.apache.maven.model.Resource
import org.apache.maven.project.MavenProject
import java.io.File

class LocalRepositoryAnalyser(
        private val mavenProject: MavenProject
) {

    fun getLocalResources(): Map<String, String> {
        val resources: MutableMap<String, String> = mutableMapOf()
        val resourceDirectories = mavenProject.resources as List<Resource>
        for (resource in resourceDirectories) {
            val fileTargetDirectory = if (resource.targetPath == null) {
                ""
            } else {
                resource.targetPath
            }

            val rootDirectory = File(resource.directory)
            if (rootDirectory.exists()) {
                rootDirectory.listFiles().forEach {
                    addRecursiveFilesToResources(fileTargetDirectory, it, resources, rootDirectory)
                }
            }
        }
        return resources
    }

    private fun addRecursiveFilesToResources(path: String, file: File, localResources: MutableMap<String, String>, rootDirectory: File) {
        val filePath = if (path == "") file.name else "$path/${file.name}"
        if (file.isDirectory) {
            file.listFiles().forEach {
                addRecursiveFilesToResources(filePath, it, localResources, rootDirectory)
            }
        } else if (file.isFile) {
            localResources[file.toRelativeString(rootDirectory)] = file.absolutePath
        }
    }
}