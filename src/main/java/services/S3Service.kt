package services

import com.amazonaws.AmazonServiceException
import com.amazonaws.SdkClientException
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.ListVersionsRequest
import org.apache.maven.plugin.logging.Log
import persisted.NimbusState
import java.io.File
import java.net.URL

class S3Service(
        region: String,
        private val config: NimbusState,
        private val logger: Log
) {

    private val s3Client = AmazonS3ClientBuilder.standard()
            .withRegion(region)
            .build()

    fun uploadLambdaJarToS3(bucketName: String, filePath: String, s3Path: String): Boolean {
        return uploadToS3(bucketName, filePath, "nimbus/${config.projectName}/" +
                config.compilationTimeStamp + "/" + s3Path)
    }

    fun uploadToS3(bucketName: String, filePath: String, s3Path: String): Boolean {
        try {
            //Upload to S3

            logger.info("$bucketName $filePath $s3Path")

            val file = File(filePath)
            if (file.isFile) {
                s3Client.putObject(bucketName, s3Path, file)
                logger.info("Uploaded file")
            } else if (file.isDirectory){
                val newPath = if (s3Path.endsWith("/") || s3Path.isEmpty()) {
                    s3Path
                } else {
                    "$s3Path/"
                }
                uploadDirectoryToS3(bucketName, file, newPath)
                logger.info("Successfully uploaded directory $filePath")
            }
            return true
        } catch (e: AmazonServiceException) {
            // The call was transmitted successfully, but Amazon S3 couldn't process
            // it, so it returned an error response.
            logger.error(e)
            e.printStackTrace()
        } catch (e: SdkClientException) {
            // Amazon S3 couldn't be contacted for a response, or the client
            // couldn't parse the response from Amazon S3.
            logger.error(e)
            e.printStackTrace()
        }
        return false
    }

    private fun uploadDirectoryToS3(bucketName: String, directory: File, s3Path: String) {
        for (file in directory.listFiles()) {
            val newPath = if (s3Path.isEmpty()) {
                file.name
            } else {
                "$s3Path/${file.name}"
            }

            if (file.isFile) {
                s3Client.putObject(bucketName, newPath, file)
            } else if (file.isDirectory){
                uploadDirectoryToS3(bucketName, file, newPath)
            }
        }
    }

    fun getUrl(bucketName: String, s3Path: String): URL {
        return s3Client.getUrl(bucketName, "nimbus/${config.projectName}/" + config.compilationTimeStamp + "/" + s3Path)
    }

    fun deleteBucket(bucketName: String) {
        try {

            // Delete all objects from the bucket. This is sufficient
            // for unversioned buckets. For versioned buckets, when you attempt to delete objects, Amazon S3 inserts
            // delete markers for all objects, but doesn't delete the object versions.
            // To delete objects from versioned buckets, delete all of the object versions before deleting
            // the bucket (see below for an example).
            var objectListing = s3Client.listObjects(bucketName)
            while (true) {
                val objIter = objectListing.objectSummaries.iterator()
                while (objIter.hasNext()) {
                    s3Client.deleteObject(bucketName, objIter.next().key)
                }

                // If the bucket contains many objects, the listObjects() call
                // might not return all of the objects in the first listing. Check to
                // see whether the listing was truncated. If so, retrieve the next page of objects
                // and delete them.
                if (objectListing.isTruncated) {
                    objectListing = s3Client.listNextBatchOfObjects(objectListing)
                } else {
                    break
                }
            }

            // Delete all object versions (required for versioned buckets).
            var versionList = s3Client.listVersions(ListVersionsRequest().withBucketName(bucketName))
            while (true) {
                val versionIter = versionList.versionSummaries.iterator()
                while (versionIter.hasNext()) {
                    val vs = versionIter.next()
                    s3Client.deleteVersion(bucketName, vs.key, vs.versionId)
                }

                if (versionList.isTruncated) {
                    versionList = s3Client.listNextBatchOfVersions(versionList)
                } else {
                    break
                }
            }

            // After all objects and object versions are deleted, delete the bucket.
            s3Client.deleteBucket(bucketName)
        } catch (e: AmazonServiceException) {
            // The call was transmitted successfully, but Amazon S3 couldn't process
            // it, so it returned an error response.
            e.printStackTrace()
        } catch (e: SdkClientException) {
            // Amazon S3 couldn't be contacted for a response, or the client couldn't
            // parse the response from Amazon S3.
            e.printStackTrace()
        }

    }
}