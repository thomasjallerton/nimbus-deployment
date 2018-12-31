package mojo;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.SdkClientException;
import com.amazonaws.services.cloudformation.AmazonCloudFormation;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder;
import com.amazonaws.services.cloudformation.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import persisted.NimbusState;
import services.AwsService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

@Mojo(name = "deploy")
public class DeploymentMojo extends AbstractMojo {

    private Log logger;
    private NimbusState persisted;

    @Parameter(property = "region", defaultValue = "eu-west-1")
    private String region;

    @Parameter(property = "shadedJarPath", defaultValue = "target/lambda.jar")
    private String lambdaPath;

    private AwsService awsService;

    public void execute() throws MojoExecutionException, MojoFailureException {
        logger = getLog();
        awsService = new AwsService(logger);
        getPersisted();

        AmazonCloudFormation client = AmazonCloudFormationClientBuilder.standard()
                .withRegion(region)
                .build();

        String projectName = "nimbus-project";
        String templateText = getFileText(".nimbus/cloudformation-stack-create.json");
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

    private String getFileText(final String path) {

        try {
            byte[] encoded = Files.readAllBytes(Paths.get(path));
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
            s3Client.putObject(bucketName, "nimbus/projectname/" +
                    persisted.getCompilationTimeStamp() + "/lambdacode", lambdaFile);

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
        String templateText = getFileText(".nimbus/cloudformation-stack-update.json");
        UpdateStackRequest updateStackRequest = new UpdateStackRequest()
                .withStackName(projectName)
                .withCapabilities("CAPABILITY_NAMED_IAM")
                .withTemplateBody(templateText);

        client.updateStack(updateStackRequest);

        logger.info("Updating stack");
    }

    private void getPersisted() {
        String stateText = getFileText(".nimbus/nimbus-state.json");
        ObjectMapper mapper = new ObjectMapper();

        try {
            persisted = mapper.readValue(stateText, NimbusState.class);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
