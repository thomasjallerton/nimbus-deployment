package mojo;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder;
import com.amazonaws.services.cloudformation.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import services.AwsService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

@Mojo(name = "deploy")
public class DeploymentMojo extends AbstractMojo {

    Log logger;

    @Parameter(property = "region", defaultValue = "eu-west-1")
    private String region;

    @Parameter(property = "shadedJarPath", defaultValue = "target/lambda.jar")
    private String lambdaPath;

    private AwsService awsService;

    public void execute() throws MojoExecutionException, MojoFailureException {
        logger = getLog();
        awsService = new AwsService(logger);

        AmazonCloudFormation client = AmazonCloudFormationClientBuilder.standard()
                .withRegion(region)
                .build();

        String projectName = "nimbus-project";
        String templateText = getTemplateText(".nimbus/cloudformation-stack-create.json");
        CreateStackRequest createStackRequest = new CreateStackRequest()
                .withStackName(projectName)
                .withCapabilities("CAPABILITY_NAMED_IAM")
                .withTemplateBody(templateText);

        //Try to create stack
        try {
            client.createStack(createStackRequest);
            logger.info("Creating Stack");
        } catch (AlreadyExistsException e) {
            logger.info("Stack already exists, proceeding to update");
        }

        String bucketName = awsService.findBucketName(client, 15);

        if (bucketName.equals("")) return;

        uploadToS3(bucketName);

        updateStack(client);
    }

    private String getTemplateText(final String templatePath) {

        try {
            byte[] encoded = Files.readAllBytes(Paths.get(templatePath));
            return new String(encoded);
        } catch (IOException e) {
            logger.error(e);
        }
        return "";
    }


    private void uploadToS3(String bucketName) {
        try {

            //Upload to S3
            AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                    .withRegion(region)
                    .build();

            File lambdaFile = new File(lambdaPath);
            s3Client.putObject(bucketName, "nimbus/projectname/lambdacode", lambdaFile);

            logger.info("Uploaded lambda file");

        } catch(AmazonServiceException e) {
            // The call was transmitted successfully, but Amazon S3 couldn't process
            // it, so it returned an error response.
            e.printStackTrace();
        } catch(SdkClientException e) {
            // Amazon S3 couldn't be contacted for a response, or the client
            // couldn't parse the response from Amazon S3.
            e.printStackTrace();
        }
    }

    private void updateStack(AmazonCloudFormation client) {
        String projectName = "nimbus-project";
        String templateText = getTemplateText(".nimbus/cloudformation-stack-update.json");
        UpdateStackRequest updateStackRequest = new UpdateStackRequest()
                .withStackName(projectName)
                .withCapabilities("CAPABILITY_NAMED_IAM")
                .withTemplateBody(templateText);

        client.updateStack(updateStackRequest);

        logger.info("Updating stack");
    }
}
