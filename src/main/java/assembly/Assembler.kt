package assembly

import assembly.models.MavenDependency
import com.nimbusframework.nimbuscore.persisted.HandlerInformation
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import org.eclipse.aether.RepositorySystemSession
import services.FileService
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarOutputStream
import java.util.jar.Manifest


class Assembler(
        private val mavenProject: MavenProject,
        repoSession: RepositorySystemSession,
        private val addEntireJar: Boolean,
        private val log: Log
) {

    private val mavenDependencies: Map<String, MavenDependency>
    private val localResources: Map<String, String>

    init {
        val mavenRepositoryAnalyser = MavenRepositoryAnalyser(repoSession, mavenProject)
        val localRepositoryAnalyser = LocalRepositoryAnalyser(mavenProject)
        mavenDependencies = mavenRepositoryAnalyser.analyseRepository()
        localResources = localRepositoryAnalyser.getLocalResources()
    }

    fun assembleProject(handlers: Set<HandlerInformation>) {

        log.info("Processing Dependencies")

        val targetDirectory = FileService.addDirectorySeparatorIfNecessary(mavenProject.build.outputDirectory)
        val dependencyProcessor = DependencyProcessor(mavenDependencies, localResources, targetDirectory, addEntireJar)

        val outputs: MutableList<JarOutputStream> = mutableListOf()
        val handlerWithOutput = handlers.map {
            val fileName = it.handlerClassPath.substringAfterLast(File.separatorChar)
            val targetJar = JarOutputStream(FileOutputStream("$targetDirectory/$fileName.jar"), Manifest())
            outputs.add(targetJar)
            Pair(it, targetJar)
        }

        val jarDependencies = dependencyProcessor.determineDependencies(handlerWithOutput)

        log.info("Creating Jars")
        val jarsCreator = JarsCreator(log, targetDirectory)

        jarsCreator.createJars(jarDependencies, outputs)

        handlerWithOutput.forEach { it.second.close() }
    }


}