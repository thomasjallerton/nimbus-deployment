package mojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import persisted.NimbusState;
import services.CloudFormationService;
import services.CloudFormationService.FindExportResponse;
import services.FileService;
import services.NimbusStateService;
import services.S3Service;

import static configuration.ConfigurationKt.DEPLOYMENT_BUCKET_NAME;

@Mojo(name = "deploy")
public class DeploymentMojo extends AbstractMojo {

    private Log logger;
    private NimbusState nimbusState;

    @Parameter(property = "region", defaultValue = "eu-west-1")
    private String region;

    @Parameter(property = "shadedJarPath", defaultValue = "target/lambda.jar")
    private String lambdaPath;

    public DeploymentMojo() {
        super();
        logger = getLog();
        nimbusState = new NimbusStateService(logger).getNimbusState();
    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        CloudFormationService cloudFormationService = new CloudFormationService(logger, region);
        S3Service s3Service = new S3Service(region, lambdaPath, nimbusState, logger);

        //Try to create stack
        boolean createSuccessful = cloudFormationService.createStack(nimbusState.getProjectName());
        if (!createSuccessful) return;

        FindExportResponse bucketName = cloudFormationService.findExport(
                nimbusState.getProjectName() + "-" + DEPLOYMENT_BUCKET_NAME, 15);

        if (!bucketName.getSuccessful()) return;

        boolean uploadSuccessful = s3Service.uploadToS3(bucketName.getResult());
        if (!uploadSuccessful) return;

        cloudFormationService.updateStack(nimbusState.getProjectName());
    }
}
