package services

import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder
import com.amazonaws.services.cloudformation.model.*
import configuration.STACK_CREATE_FILE
import configuration.STACK_UPDATE_FILE
import org.apache.maven.plugin.logging.Log

class CloudFormationService(private val logger: Log, private val region: String) {
    private val client: AmazonCloudFormation = AmazonCloudFormationClientBuilder.standard()
            .withRegion(region)
            .build()

    private val fileService: FileService = FileService(logger)

    fun findExport(exportName: String, maxRetries: Int = 15): FindExportResponse {
        val listExportsRequest = ListExportsRequest()

        try {

            for (i in 1..maxRetries) {


                val exportsResults = client.listExports(listExportsRequest)
                val exports = exportsResults.exports

                for (export in exports) {
                    if (export.name == exportName) {
                        println()
                        return FindExportResponse(true, export.value)
                    }
                }

                Thread.sleep(3000)
                print("*")
            }
        } catch (e: InterruptedException) {
            logger.error(e)
        } catch (e: Exception) {
            logger.error(e)
        }
        print("Unable to find deployment bucket")
        return FindExportResponse(false, "")
    }

    fun updateStack(projectName: String) {
        val templateText = fileService.getFileText(STACK_UPDATE_FILE)
        val updateStackRequest = UpdateStackRequest()
                .withStackName(projectName)
                .withCapabilities("CAPABILITY_NAMED_IAM")
                .withTemplateBody(templateText)

        client.updateStack(updateStackRequest)

        logger.info("Updating stack")
    }

    fun createStack(projectName: String): Boolean {
        val templateText = fileService.getFileText(STACK_CREATE_FILE)
        val createStackRequest = CreateStackRequest()
                .withStackName(projectName)
                .withCapabilities("CAPABILITY_NAMED_IAM")
                .withTemplateBody(templateText)
        try {
            client.createStack(createStackRequest)
            logger.info("Creating Stack")
            return true
        } catch (e: AlreadyExistsException) {
            logger.info("Stack already exists, proceeding to update")
        } catch (e: java.lang.Exception) {
            logger.error(e)
        }
        return false
    }

    fun deleteStack(projectName: String): Boolean {
        val deleteStackRequest = DeleteStackRequest()
                .withStackName(projectName)

        return try {
            client.deleteStack(deleteStackRequest)
            true
        } catch (e: java.lang.Exception) {
            logger.error(e)
            false
        }
    }

    data class FindExportResponse(val successful: Boolean, val result: String)
}
