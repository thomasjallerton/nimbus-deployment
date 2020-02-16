package mojo;

import assembly.Assembler;
import assembly.FunctionHasher;
import com.nimbusframework.nimbuscore.persisted.ExportInformation;
import com.nimbusframework.nimbuscore.persisted.FileUploadDescription;
import com.nimbusframework.nimbuscore.persisted.HandlerInformation;
import com.nimbusframework.nimbuscore.persisted.NimbusState;
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
import persisted.*;
import services.*;
import services.CloudFormationService.CreateStackResponse;
import services.CloudFormationService.FindExportResponse;

import java.io.File;
import java.net.URL;
import java.util.*;

import static configuration.ConfigurationKt.*;

@Mojo(name = "deploy",
        requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class DeploymentMojo extends AbstractMojo {

    private Log logger;

    @Parameter(property = "region", defaultValue = "eu-west-1")
    private String region;

    @Parameter(property = "stage", defaultValue = "dev")
    private String stage;

    @Parameter(property = "shadedJarPath", defaultValue = "target/functions.jar")
    private String shadedJarPath;

    @Parameter(property = "compiledSourcePath", defaultValue = "target/generated-sources/annotations/")
    private String compiledSourcePath;

    //Assembly information
    @Component
    private RepositorySystem repoSystem;

    @Parameter(property = "repoSession", defaultValue = "${repositorySystemSession}")
    private RepositorySystemSession repoSession;

    @Parameter(property = "addEntireJar", defaultValue = "false")
    private String addEntireJar;

    @Parameter(defaultValue = "${project.remotePluginRepositories}")
    private List<RemoteRepository> remoteRepos;

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject mavenProject;

    public DeploymentMojo() {
        super();
        logger = getLog();
    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        String compiledSourcePathFixed = FileService.addDirectorySeparatorIfNecessary(compiledSourcePath);
        PersistedStateService persistedStateService = new PersistedStateService(logger, compiledSourcePathFixed);

        FileService fileService = new FileService(logger);

        NimbusState nimbusState = persistedStateService.getNimbusState();
        DeploymentInformation deploymentInformation = persistedStateService.getDeploymentInformation(stage);

        CloudFormationService cloudFormationService = new CloudFormationService(logger, region);
        S3Service s3Service = new S3Service(region, nimbusState, logger);

        String stackName = nimbusState.getProjectName() + "-" + stage;
        logger.info("Beginning deployment for project: " + nimbusState.getProjectName() + ", stage: " + stage);
        //Try to create stack
        CreateStackResponse createSuccessful = cloudFormationService.createStack(stackName, stage, compiledSourcePathFixed);
        if (!createSuccessful.getSuccessful()) throw new MojoFailureException("Unable to create stack");

        if (createSuccessful.getAlreadyExists()) {
            logger.info("Stack already exists, proceeding to update");
        } else {
            logger.info("Creating stack");
            logger.info("Polling stack create progress");
            cloudFormationService.pollStackStatus(stackName, 0);
            logger.info("Stack created");
        }


        FindExportResponse lambdaBucketName = cloudFormationService.findExport(
                nimbusState.getProjectName() + "-" + stage + "-" + DEPLOYMENT_BUCKET_NAME);

        if (!lambdaBucketName.getSuccessful()) throw new MojoFailureException("Unable to find deployment bucket");

        Map<String, DeployedFunctionInformation> newFunctions = new HashMap<>();
        DeploymentInformation newDeployment = new DeploymentInformation(nimbusState.getCompilationTimeStamp(), newFunctions);
        FunctionHasher functionHasher = new FunctionHasher(FileService.addDirectorySeparatorIfNecessary(mavenProject.getBuild().getOutputDirectory()));
        Map<String, String> versionToReplace = new HashMap<>();
        String s3MostRecentDeployedTimestamp = s3Service.readFileFromS3(lambdaBucketName.getResult(), S3_DEPLOYMENT_PATH);

        //Assemble project if necessary
        if (nimbusState.getAssemble()) {
            boolean addEntireJarBool = Boolean.parseBoolean(addEntireJar);
            Assembler assembler = new Assembler(mavenProject, repoSession, addEntireJarBool, logger);
            Set<HandlerInformation> functionsToDeploy = new HashSet<>();

            Map<String, DeployedFunctionInformation> functionDeployments = deploymentInformation.getMostRecentDeployedFunctions();
            if (deploymentInformation.getMostRecentCompilationTimestamp().equals(s3MostRecentDeployedTimestamp)) {
                //This means that the most recent deployment was done on this machine and we can properly process updated functions
                for (HandlerInformation handlerInformation : nimbusState.getHandlerFiles()) {
                    if (handlerInformation.getStages().contains(stage)) {
                        String classPath = handlerInformation.getHandlerClassPath();
                        DeployedFunctionInformation functionDeployment = functionDeployments.get(classPath);
                        String currentHash = functionHasher.determineFunctionHash(classPath);

                        if (functionDeployment == null || !currentHash.equals(functionDeployment.getMostRecentDeployedHash())) {
                            functionsToDeploy.add(handlerInformation);
                            String version = nimbusState.getCompilationTimeStamp() + "/" + handlerInformation.getHandlerFile();
                            DeployedFunctionInformation newFunction = new DeployedFunctionInformation(version, currentHash);
                            newFunctions.put(classPath, newFunction);
                            versionToReplace.put(handlerInformation.getReplacementVariable(), version);
                        } else {
                            newFunctions.put(classPath, functionDeployment);
                            versionToReplace.put(handlerInformation.getReplacementVariable(), functionDeployment.getMostRecentDeployedVersion());
                        }
                    }
                }
            } else {
                for (HandlerInformation handlerInformation : nimbusState.getHandlerFiles()) {
                    if (handlerInformation.getStages().contains(stage)) {

                        String classPath = handlerInformation.getHandlerClassPath();
                        String currentHash = functionHasher.determineFunctionHash(classPath);

                        functionsToDeploy.add(handlerInformation);
                        String version = nimbusState.getCompilationTimeStamp() + "/" + handlerInformation.getHandlerFile();
                        DeployedFunctionInformation newFunction = new DeployedFunctionInformation(version, currentHash);
                        newFunctions.put(classPath, newFunction);
                        versionToReplace.put(handlerInformation.getReplacementVariable(), version);
                    }
                }
            }


            logger.info("There are " + functionsToDeploy.size() + " functions to deploy");
            assembler.assembleProject(functionsToDeploy);

            int numberOfHandlers = nimbusState.getHandlerFiles().size();
            int deployingHandlers = functionsToDeploy.size();
            if (deployingHandlers < numberOfHandlers) {
                logger.info("Detected only " + deployingHandlers + " out of " + numberOfHandlers + " need to be deployed.");
            }
            int count = 1;
            for (HandlerInformation handler : functionsToDeploy) {
                logger.info("Uploading lambda handler " + count + "/" + deployingHandlers);
                count++;
                String path = FileService.addDirectorySeparatorIfNecessary(mavenProject.getBuild().getOutputDirectory()) + handler.getHandlerFile();
                boolean uploadSuccessful = s3Service.uploadFileToCompilationFolder(lambdaBucketName.getResult(), path, handler.getHandlerFile());
                if (!uploadSuccessful)
                    throw new MojoFailureException("Failed uploading lambda code, have you run the assemble goal?");
            }
        } else {
            for (HandlerInformation handlerInformation : nimbusState.getHandlerFiles()) {
                String classPath = handlerInformation.getHandlerClassPath();
                String currentHash = functionHasher.determineFunctionHash(classPath);
                String version = nimbusState.getCompilationTimeStamp() + "/" + "lambdacode";
                DeployedFunctionInformation newFunction = new DeployedFunctionInformation(version, currentHash);
                newFunctions.put(classPath, newFunction);
                versionToReplace.put(handlerInformation.getReplacementVariable(), version);
            }

            logger.info("Uploading lambda file");
            boolean uploadSuccessful = s3Service.uploadFileToCompilationFolder(lambdaBucketName.getResult(), shadedJarPath, "lambdacode");
            if (!uploadSuccessful) throw new MojoFailureException("Failed uploading lambda code");
        }

        persistedStateService.saveDeploymentInformation(newDeployment, stage);
        s3Service.uploadToS3(lambdaBucketName.getResult(), nimbusState.getCompilationTimeStamp(), S3_DEPLOYMENT_PATH);

        File fixedUpdateTemplate = fileService.replaceInFile(versionToReplace, new File(compiledSourcePathFixed + STACK_UPDATE_FILE + "-" + stage + ".json"));

        //Try to update stack
        logger.info("Uploading cloudformation file");
        boolean cloudFormationUploadSuccessful = s3Service.uploadFileToCompilationFolder(lambdaBucketName.getResult(), fixedUpdateTemplate.getPath(), "update-template");
        if (!cloudFormationUploadSuccessful)
            throw new MojoFailureException("Failed uploading cloudformation update code");

        URL cloudformationUrl = s3Service.getUrl(lambdaBucketName.getResult(), "update-template");

        boolean updating = cloudFormationService.updateStack(stackName, cloudformationUrl);

        if (!updating) throw new MojoFailureException("Unable to update stack");

        logger.info("Updating stack");

        cloudFormationService.pollStackStatus(stackName, 0);

        logger.info("Updated stack successfully, deployment complete");


        //Deal with substitutions
        Map<String, String> substitutionParams = new HashMap<>();
        Map<String, String> outputMessages = new HashMap<>();

        List<ExportInformation> exports = nimbusState.getExports().getOrDefault(stage, new LinkedList<>());
        for (ExportInformation export : exports) {
            FindExportResponse exportResponse = cloudFormationService.findExport(export.getExportName());
            if (exportResponse.getSuccessful()) {
                String result = exportResponse.getResult();
                substitutionParams.put(export.getSubstitutionVariable(), result);
                outputMessages.put(export.getExportMessage(), result);
            }
        }

        if (nimbusState.getFileUploads().size() > 0) {
            logger.info("Starting File Uploads");

            Map<String, List<FileUploadDescription>> bucketUploads = nimbusState.getFileUploads().get(stage);
            for (Map.Entry<String, List<FileUploadDescription>> bucketUpload : bucketUploads.entrySet()) {
                String bucketName = bucketUpload.getKey();
                for (FileUploadDescription fileUploadDescription : bucketUpload.getValue()) {
                    String localFile = fileUploadDescription.getLocalFile();
                    String targetFile = fileUploadDescription.getTargetFile();

                    if (fileUploadDescription.getSubstituteVariables()) {
                        s3Service.uploadToS3(bucketName, localFile, targetFile,
                                (file) -> fileService.replaceInFile(substitutionParams, file));
                    } else {
                        s3Service.uploadToS3(bucketName, localFile, targetFile, (file) -> file);
                    }
                }
            }
        }

        if (nimbusState.getAfterDeployments().size() > 0) {
            if (nimbusState.getAfterDeployments().size() == 1) {
                logger.info("Starting after deployment script");
            } else {
                logger.info("Starting after deployment scripts");
            }

            LambdaService lambdaClient = new LambdaService(logger, region);

            List<String> afterDeployments = nimbusState.getAfterDeployments().get(stage);
            if (afterDeployments != null) {
                for (String lambda : afterDeployments) {
                    lambdaClient.invokeNoArgs(lambda);
                }
            }
        }

        logger.info("Deployment completed");

        for (Map.Entry<String, String> entry : outputMessages.entrySet()) {
            logger.info(entry.getKey() + entry.getValue());
        }
    }
}
