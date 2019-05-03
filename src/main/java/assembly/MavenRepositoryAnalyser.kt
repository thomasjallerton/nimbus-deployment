package assembly

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

    fun analyseRepository(): Pair<Map<String, String>, Set<String>> {
        val processingMavenDependencies: MutableMap<String, String> = mutableMapOf()
        val processingExtraDependencies: MutableSet<String> = mutableSetOf()
        val artifacts = mavenProject.artifacts as Set<Artifact>
        artifacts.forEach {
            processArtifact(it, processingMavenDependencies, processingExtraDependencies)
        }

        return Pair(processingMavenDependencies, processingExtraDependencies)
    }

    private fun processArtifact(
            artifact: Artifact,
            processingMavenDependencies: MutableMap<String, String>,
            processingExtraDependencies: MutableSet<String>
    ) {

        val identifier = (artifact.groupId + "." + artifact.artifactId).replace(".", File.separator)

        val directoryPath = localRepoPath + File.separator + identifier + File.separator + artifact.version

        val directory = File(directoryPath)

        if (directory.listFiles() == null) return

        directory.listFiles().forEach { file ->
            if (file.extension == "jar") {
                val jarFile = JarFile(file)
                val entries = jarFile.entries()
                for (entry in entries) {
                    processingMavenDependencies[entry.name] = file.absolutePath
                    if (entry.name.contains("nimbuscore/annotation/annotations")) {
                        val nimbusAnnotationDependency = entry.name.replace('/', '.').substringBefore(".class")
                        processingExtraDependencies.add(nimbusAnnotationDependency)
                    }
                }
            }
        }
    }
}