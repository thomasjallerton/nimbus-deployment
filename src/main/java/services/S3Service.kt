package services

import com.amazonaws.AmazonServiceException
import com.amazonaws.SdkClientException
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import org.apache.maven.plugin.logging.Log
import persisted.NimbusState
import java.io.File

class S3Service(
        private val region: String,
        private val jarPath: String,
        private val config: NimbusState,
        private val logger: Log
) {

    fun uploadToS3(bucketName: String): Boolean {
        try {
            //Upload to S3
            val s3Client = AmazonS3ClientBuilder.standard()
                    .withRegion(region)
                    .build()

            val lambdaFile = File(jarPath)
            s3Client.putObject(bucketName, "nimbus/projectname/" +
                    config.compilationTimeStamp + "/lambdacode", lambdaFile)

            logger.info("Uploaded lambda file")
            return true
        } catch (e: AmazonServiceException) {
            // The call was transmitted successfully, but Amazon S3 couldn't process
            // it, so it returned an error response.
            e.printStackTrace()
        } catch (e: SdkClientException) {
            // Amazon S3 couldn't be contacted for a response, or the client
            // couldn't parse the response from Amazon S3.
            e.printStackTrace()
        }
        return false
    }
}