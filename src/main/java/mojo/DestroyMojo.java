package mojo;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import persisted.NimbusState;
import services.CloudFormationService;
import services.CloudFormationService.FindExportResponse;
import services.NimbusStateService;
import services.S3Service;

import java.util.Iterator;

import static configuration.ConfigurationKt.DEPLOYMENT_BUCKET_NAME;

@Mojo(name = "destroy-stack")
public class DestroyMojo extends AbstractMojo {

    private Log logger;

    @Parameter(property = "region", defaultValue = "eu-west-1")
    private String region;

    private NimbusState nimbusState;

    public DestroyMojo() {
        super();
        logger = getLog();
        nimbusState = new NimbusStateService(logger).getNimbusState();
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        CloudFormationService cloudFormationService = new CloudFormationService(logger, region);
        S3Service s3Service = new S3Service(region, nimbusState, logger);

        FindExportResponse bucketName = cloudFormationService.findExport(
                 nimbusState.getProjectName() + "-" + DEPLOYMENT_BUCKET_NAME);

        if (!bucketName.getSuccessful()) {
            throw new MojoExecutionException("Unable to find S3 Bucket, does stack exist?");
        }

        logger.info("Found S3 bucket, about to empty");
        s3Service.deleteBucket(bucketName.getResult());

        logger.info("Emptied S3 bucket");

        boolean deleting = cloudFormationService.deleteStack(nimbusState.getProjectName());
        if (!deleting) throw new MojoFailureException("Unable to delete stack");
        logger.info("Deleting stack");

        cloudFormationService.pollStackStatus(nimbusState.getProjectName());

        logger.info("Deleted stack successfully");

    }
}
