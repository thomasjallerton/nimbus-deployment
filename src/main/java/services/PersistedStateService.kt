package services

import com.fasterxml.jackson.databind.ObjectMapper
import configuration.MOST_RECENT_DEPLOYMENT
import configuration.NIMBUS_STATE
import org.apache.maven.plugin.logging.Log
import persisted.DeploymentInformation
import persisted.NimbusState
import software.amazon.ion.system.IonTextWriterBuilder.json



class PersistedStateService(private val logger: Log, private val compiledSourcePath: String) {
    private val fileService = FileService(logger)

    fun getNimbusState(): NimbusState {
        val stateText = try {
            fileService.getFileText(compiledSourcePath + NIMBUS_STATE)
        } catch (e: Exception) {
            logger.error("Couldn't open nimbus state file, must have compiled code beforehand")
            return NimbusState()
        }
        val mapper = ObjectMapper()

        try {
            return mapper.readValue(stateText, NimbusState::class.java)
        } catch (e: Exception) {
            logger.error(e)
        }

        return NimbusState()
    }

    fun getDeploymentInformation(stage: String): DeploymentInformation {
        val stateText = try {
            fileService.getFileText("$MOST_RECENT_DEPLOYMENT-$stage.json")
        } catch (e: Exception) {
            return DeploymentInformation()
        }
        val mapper = ObjectMapper()

        try {
            return mapper.readValue(stateText, DeploymentInformation::class.java)
        } catch (e: Exception) {
            logger.error(e)
        }

        return DeploymentInformation()
    }

    fun saveDeploymentInformation(deploymentInformation: DeploymentInformation, stage: String) {
        val jsonDeploymentInformation = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(deploymentInformation)
        fileService.saveFile(jsonDeploymentInformation, "$MOST_RECENT_DEPLOYMENT-$stage.json")
    }
}