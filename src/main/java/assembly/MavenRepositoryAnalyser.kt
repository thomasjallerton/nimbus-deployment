package assembly

import assembly.models.MavenDependency
import org.apache.maven.artifact.Artifact
import org.apache.maven.project.MavenProject
import org.eclipse.aether.RepositorySystemSession
import java.io.File
import java.util.jar.JarFile

class MavenRepositoryAnalyser(
        repoSession: RepositorySystemSession,
        private val mavenProject: MavenProject
) {

    private val localRepoPath = repoSession.localRepository.basedir.absolutePath

    //Keys are of format 'path/path/file.extension'
    private val mavenDependencies: MutableMap<String, MavenDependency> = mutableMapOf()

    fun analyseRepository(): Map<String, MavenDependency> {
        val processingMavenDependencies: MutableMap<String, MavenDependency> = mutableMapOf()
        val artifacts = mavenProject.artifacts as Set<Artifact> // Includes transitive dependencies
        artifacts.forEach {
            processArtifact(it, processingMavenDependencies)
        }

        return processingMavenDependencies
    }

    private fun processArtifact(
            artifact: Artifact,
            processingMavenDependencies: MutableMap<String, MavenDependency>
    ) {

        val identifier = (artifact.groupId + "." + artifact.artifactId).replace(".", File.separator)

        val files = File(toDirectoryPath(identifier, artifact)).listFiles()

        if (files != null) {
            processDirectory(files, processingMavenDependencies)
            return
        }

        //Used for javax.inject (path is javax/inject/javax.inject)
        val secondIdentifierType = artifact.groupId.replace(".", File.separator) + File.separator + artifact.artifactId
        val secondFiles = File(toDirectoryPath(secondIdentifierType, artifact)).listFiles()
        if (secondFiles != null) {
            processDirectory(secondFiles, processingMavenDependencies)
            return
        }
    }

    private fun toDirectoryPath(identifier: String, artifact: Artifact): String {
        return localRepoPath + File.separator + identifier + File.separator + artifact.version
    }

    private fun processDirectory(files: Array<out File>, processingMavenDependencies: MutableMap<String, MavenDependency>) {
        files.forEach { file ->
            if (file.extension == "jar") {
                val jarFile = JarFile(file)
                val entries = jarFile.entries()
                for (entry in entries) {
                    val mavenDependency = mavenDependencies.getOrPut(file.absolutePath) { MavenDependency(file.absolutePath) }
                    processingMavenDependencies[entry.name] = mavenDependency

                    if (entry.name.endsWith(".driver", ignoreCase = true)) {
                        mavenDependency.requiredClasses.add(entry.name)
                    }
                }
            }
        }
    }
}