package mojo;

import assembly.Assembler;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import persisted.NimbusState;
import services.FileService;
import services.PersistedStateService;

import java.util.List;

@Mojo(name = "assemble",
        requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class AssemblyMojo extends AbstractMojo {

    private Log logger;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(property = "localrepository", defaultValue = "${repositorySystemSession}")
    private RepositorySystemSession repoSession;

    @Parameter(property = "compiledSourcePath", defaultValue = "target/generated-sources/annotations/")
    private String compiledSourcePath;

    @Parameter(defaultValue = "${project.remotePluginRepositories}")
    private List<RemoteRepository> remoteRepos;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject mavenProject;

    public AssemblyMojo() {
        super();
        logger = getLog();
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        NimbusState nimbusState = new PersistedStateService(logger, FileService.addDirectorySeparatorIfNecessary(compiledSourcePath)).getNimbusState();
        Assembler assembler = new Assembler(mavenProject, repoSession, logger);
        assembler.assembleProject(nimbusState.getHandlerFiles());
    }
}
