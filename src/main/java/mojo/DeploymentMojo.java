package mojo;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import persisted.NimbusState;
import services.CloudFormationService;
import services.CloudFormationService.CreateStackResponse;
import services.CloudFormationService.FindExportResponse;
import services.LambdaService;
import services.NimbusStateService;
import services.S3Service;

import java.net.URL;
import java.util.List;
import java.util.Map;

import static configuration.ConfigurationKt.DEPLOYMENT_BUCKET_NAME;
import static configuration.ConfigurationKt.STACK_UPDATE_FILE;

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

        if (nimbusState.getFileUploads().size() > 0) {
            logger.info("Starting File Uploads");

            Map<String, Map<String, String>> bucketUploads = nimbusState.getFileUploads().get(stage);
            for (Map.Entry<String, Map<String, String>> bucketUpload : bucketUploads.entrySet()) {
                String bucketName = bucketUpload.getKey();
                for (Map.Entry<String, String> files: bucketUpload.getValue().entrySet()) {
                    String localFile = files.getKey();
                    String targetFile = files.getValue();
                    s3Service.uploadToS3(bucketName, localFile, targetFile);
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
    }
}
