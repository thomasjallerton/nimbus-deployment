package mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import persisted.FileUploadDescription;
import persisted.NimbusState;
import services.*;
import services.CloudFormationService.CreateStackResponse;
import services.CloudFormationService.FindExportResponse;

import java.io.File;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static configuration.ConfigurationKt.*;

@Mojo(name = "deploy")
public class DeploymentMojo extends AbstractMojo {

    private Log logger;

    @Parameter(property = "region", defaultValue = "eu-west-1")
    private String region;

    @Parameter(property = "stage", defaultValue = "dev")
    private String stage;

    @Parameter(property = "shadedJarPath", defaultValue = "target/lambda.jar")
    private String lambdaPath;

    @Parameter(property = "compiledSourcePath", defaultValue = "target/generated-sources/annotations/")
    private String compiledSourcePath;

    public DeploymentMojo() {
        super();
        logger = getLog();
    }


    public void execute() throws MojoExecutionException, MojoFailureException {
        NimbusState nimbusState = new NimbusStateService(logger).getNimbusState(compiledSourcePath);

        CloudFormationService cloudFormationService = new CloudFormationService(logger, region);
        S3Service s3Service = new S3Service(region, nimbusState, logger);
        FileService fileService = new FileService(logger);

        String stackName = nimbusState.getProjectName() + "-" + stage;
        logger.info("Beginning deployment for project: " + nimbusState.getProjectName() + ", stage: " + stage);
        //Try to create stack
        CreateStackResponse createSuccessful = cloudFormationService.createStack(stackName, stage, compiledSourcePath);
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

        logger.info("Uploading lambda file");
        boolean uploadSuccessful = s3Service.uploadLambdaJarToS3(lambdaBucketName.getResult(), lambdaPath, "lambdacode");
        if (!uploadSuccessful) throw new MojoFailureException("Failed uploading lambda code");

        logger.info("Uploading cloudformation file");
        boolean cloudFormationUploadSuccessful = s3Service.uploadLambdaJarToS3(lambdaBucketName.getResult(), compiledSourcePath + STACK_UPDATE_FILE + "-" + stage + ".json", "update-template");
        if (!cloudFormationUploadSuccessful) throw new MojoFailureException("Failed uploading cloudformation update code");

        URL cloudformationUrl = s3Service.getUrl(lambdaBucketName.getResult(), "update-template");

        boolean updating = cloudFormationService.updateStack(stackName, cloudformationUrl);

        if (!updating) throw new MojoFailureException("Unable to update stack");

        logger.info("Updating stack");

        cloudFormationService.pollStackStatus(stackName, 0);

        logger.info("Updated stack successfully, deployment complete");

        Map<String, String> substitutionParams = new HashMap<>();

        String httpUrl = null;
        if (nimbusState.getHasHttpServerlessFunctions()) {
            FindExportResponse exportResponse = cloudFormationService.findExport(
                    nimbusState.getProjectName() + "-" + stage + "-" + REST_API_URL_OUTPUT
            );
            if (exportResponse.getSuccessful()) {
                httpUrl = exportResponse.getResult();
            }
        }

        substitutionParams.put(REST_API_URL_SUBSTITUTE, httpUrl);

        if (nimbusState.getFileUploads().size() > 0) {
            logger.info("Starting File Uploads");

            Map<String, List<FileUploadDescription>> bucketUploads = nimbusState.getFileUploads().get(stage);
            for (Map.Entry<String, List<FileUploadDescription>> bucketUpload : bucketUploads.entrySet()) {
                String bucketName = bucketUpload.getKey();
                for (FileUploadDescription fileUploadDescription: bucketUpload.getValue()) {
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
            logger.info("Starting after deployment script");

            LambdaService lambdaClient = new LambdaService(logger, region);

            List<String> afterDeployments = nimbusState.getAfterDeployments().get(stage);
            if (afterDeployments != null) {
                for (String lambda : afterDeployments) {
                    lambdaClient.invokeNoArgs(lambda);
                }
            }
        }

        logger.info("Deployment completed");

        if (httpUrl != null) {
            logger.info("Created Rest Api. Base URL is " + httpUrl);
        }
    }
}
