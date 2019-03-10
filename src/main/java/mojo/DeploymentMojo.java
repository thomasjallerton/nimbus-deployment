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

import static configuration.ConfigurationKt.DEPLOYMENT_BUCKET_NAME;
import static configuration.ConfigurationKt.STACK_UPDATE_FILE;

@Mojo(name = "deploy")
public class DeploymentMojo extends AbstractMojo {

    private Log logger;
    private NimbusState nimbusState;

    @Parameter(property = "region", defaultValue = "eu-west-1")
    private String region;

    @Parameter(property = "stage", defaultValue = "dev")
    private String stage;

    @Parameter(property = "shadedJarPath", defaultValue = "target/lambda.jar")
    private String lambdaPath;

    public DeploymentMojo() {
        super();
        logger = getLog();
        nimbusState = new NimbusStateService(logger).getNimbusState();
    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        CloudFormationService cloudFormationService = new CloudFormationService(logger, region);
        S3Service s3Service = new S3Service(region, nimbusState, logger);

        String stackName = nimbusState.getProjectName() + "-" + stage;
        logger.info("Beginning deployment for project: " + nimbusState.getProjectName() + ", stage: " + stage);
        //Try to create stack
        CreateStackResponse createSuccessful = cloudFormationService.createStack(stackName, stage);
        if (!createSuccessful.getSuccessful()) throw new MojoFailureException("Unable to create stack");

        if (createSuccessful.getAlreadyExists()) {
            logger.info("Stack already exists, proceeding to update");
        } else {
            logger.info("Creating stack");
            logger.info("Polling stack create progress");
            cloudFormationService.pollStackStatus(stackName, 0);
            logger.info("Stack created");
        }


        FindExportResponse bucketName = cloudFormationService.findExport(
                nimbusState.getProjectName() + "-" + stage + "-" + DEPLOYMENT_BUCKET_NAME);

        if (!bucketName.getSuccessful()) throw new MojoFailureException("Unable to find deployment bucket");

        logger.info("Uploading lambda file");
        boolean uploadSuccessful = s3Service.uploadToS3(bucketName.getResult(), lambdaPath, "lambdacode");
        if (!uploadSuccessful) throw new MojoFailureException("Failed uploading lambda code");

        logger.info("Uploading cloudformation file");
        boolean cloudFormationUploadSuccessful = s3Service.uploadToS3(bucketName.getResult(), STACK_UPDATE_FILE + "-" + stage + ".json", "update-template");
        if (!cloudFormationUploadSuccessful) throw new MojoFailureException("Failed uploading cloudformation update code");

        URL cloudformationUrl = s3Service.getUrl(bucketName.getResult(), "update-template");

        boolean updating = cloudFormationService.updateStack(stackName, cloudformationUrl);

        if (!updating) throw new MojoFailureException("Unable to update stack");

        logger.info("Updating stack");

        cloudFormationService.pollStackStatus(stackName, 0);

        logger.info("Updated stack successfully, deployment complete");

        if (nimbusState.getAfterDeployments().size() > 0) {
            logger.info("Starting after deployment script");

            LambdaService lambdaClient = new LambdaService(logger, region);

            for (String lambda : nimbusState.getAfterDeployments().get(stage)) {
                lambdaClient.invokeNoArgs(lambda);
            }
        }
    }
}
