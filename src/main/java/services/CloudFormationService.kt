package services

import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder
import com.amazonaws.services.cloudformation.model.*
import configuration.STACK_CREATE_FILE
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugin.logging.Log
import java.net.URL

class CloudFormationService(private val logger: Log, region: String) {
    private val client: AmazonCloudFormation = AmazonCloudFormationClientBuilder.standard()
            .withRegion(region)
            .build()

    private val fileService: FileService = FileService(logger)

    fun findExport(exportName: String): FindExportResponse {
        val listExportsRequest = ListExportsRequest()

        try {
            val exportsResults = client.listExports(listExportsRequest)
            val exports = exportsResults.exports

            for (export in exports) {
                if (export.name == exportName) {
                    return FindExportResponse(true, export.value)
                }
            }

        } catch (e: InterruptedException) {
            logger.error(e.localizedMessage)
        } catch (e: Exception) {
            logger.error(e.localizedMessage)
        }
        return FindExportResponse(false, "")
    }

    data class FindExportResponse(val successful: Boolean, val result: String)


    fun updateStack(projectName: String, url: URL): Boolean {
        val updateStackRequest = UpdateStackRequest()
                .withStackName(projectName)
                .withCapabilities("CAPABILITY_NAMED_IAM")
                .withTemplateURL(url.toString())
        return try {
            client.updateStack(updateStackRequest)
            true
        } catch (e: Exception) {
            if (e.message!!.contains("No updates are to be performed")) return true
            logger.error(e.localizedMessage)
            false
        }
    }

    fun createStack(projectName: String, stage: String, compiledSourcesPath: String): CreateStackResponse {
        val templateText = fileService.getFileText("$compiledSourcesPath$STACK_CREATE_FILE-$stage.json")
        val createStackRequest = CreateStackRequest()
                .withStackName(projectName)
                .withCapabilities("CAPABILITY_NAMED_IAM")
                .withTemplateBody(templateText)
        try {
            client.createStack(createStackRequest)
            return CreateStackResponse(true, false)
        } catch (e: AlreadyExistsException) {
            return CreateStackResponse(true, true)
        } catch (e: java.lang.Exception) {
            logger.error(e.localizedMessage)
        }
        return CreateStackResponse(false, false)
    }

    data class CreateStackResponse(val successful: Boolean, val alreadyExists: Boolean)

    fun deleteStack(stackName: String): Boolean {
        val deleteStackRequest = DeleteStackRequest()
                .withStackName(stackName)

        return try {
            client.deleteStack(deleteStackRequest)
            true
        } catch (e: java.lang.Exception) {
            logger.error(e.localizedMessage)
            false
        }
    }

    fun getStackStatus(stackName: String): String {
        val describeStackRequest = DescribeStacksRequest()
                .withStackName(stackName)
        try {
            val response = client.describeStacks(describeStackRequest)
            val stacks = response.stacks

            if (stacks.size == 1) {
                return stacks[0].stackStatus
            }
        } catch (e: java.lang.Exception) {
            if (!e.localizedMessage.contains("does not exist")) {
                logger.error(e.localizedMessage)
            }
        }
        return "STACK_NOT_FOUND"
    }

    fun getStackErrorReason(stackName: String): String {
        val describeStackRequest = DescribeStackEventsRequest()
                .withStackName(stackName)

        val response = client.describeStackEvents(describeStackRequest)
        val events = response.stackEvents

        for (event in events) {
            if (isErrorStatus(event.resourceStatus)
                    && !event.resourceStatusReason.contains("Resource update cancelled")
                    && !event.resourceStatusReason.contains("Resource creation cancelled")) {
                return event.resourceStatusReason
            }
        }
        return "Couldn't find error, look at CloudFormation stack log"
    }

    fun isErrorStatus(stackStatus: String): Boolean {
        return when (stackStatus) {
            "CREATE_FAILED" -> true
            "DELETE_FAILED" -> true
            "ROLLBACK_FAILED" -> true
            "UPDATE_ROLLBACK_FAILED" -> true
            "UPDATE_FAILED" -> true
            else -> false
        }
    }

    fun canContinue(stackStatus: String): ContinueResponse {
        return when (stackStatus) {
            "CREATE_COMPLETE" -> ContinueResponse(true, false)
            "CREATE_FAILED" -> ContinueResponse(true, true)
            "CREATE_IN_PROGRESS" -> ContinueResponse(false, false)
            "DELETE_COMPLETE" -> ContinueResponse(true, false)
            "DELETE_FAILED" -> ContinueResponse(true, true)
            "DELETE_IN_PROGRESS" -> ContinueResponse(false, false)
            "REVIEW_IN_PROGRESS" -> ContinueResponse(false, false)
            "ROLLBACK_COMPLETE" -> ContinueResponse(true, false)
            "ROLLBACK_FAILED" -> ContinueResponse(true, true)
            "ROLLBACK_IN_PROGRESS" -> ContinueResponse(false, false)
            "UPDATE_COMPLETE" -> ContinueResponse(true, false)
            "UPDATE_COMPLETE_CLEANUP_IN_PROGRESS" -> ContinueResponse(false, false)
            "UPDATE_IN_PROGRESS" -> ContinueResponse(false, false)
            "UPDATE_ROLLBACK_COMPLETE" -> ContinueResponse(true, true)
            "UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS" -> ContinueResponse(false, false)
            "UPDATE_ROLLBACK_FAILED" -> ContinueResponse(true, true)
            "UPDATE_ROLLBACK_IN_PROGRESS" -> ContinueResponse(false, false)
            "STACK_NOT_FOUND" -> ContinueResponse(true, false)
            else -> ContinueResponse(false, false)
        }
    }

    data class ContinueResponse(val canContinue: Boolean, val needErrorMessage: Boolean)

    @Throws(MojoFailureException::class)
    fun pollStackStatus(projectName: String, count: Int = 0) {
        val status = getStackStatus(projectName)
        val continueResponse = canContinue(status)

        if (continueResponse.canContinue) {
            if (continueResponse.needErrorMessage) {
                val errorMessage = getStackErrorReason(projectName)
                throw MojoFailureException(errorMessage)
            }
            println()
        } else {
            try {
                Thread.sleep(2000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }

            if (count < 50) {
                print("*")
                pollStackStatus(projectName, count + 1)
            } else {
                println("*")
                pollStackStatus(projectName, 0)
            }
        }
    }
}
