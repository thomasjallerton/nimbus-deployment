package services

import com.fasterxml.jackson.databind.ObjectMapper
import configuration.NIMBUS_STATE
import org.apache.maven.plugin.logging.Log
import persisted.NimbusState

class NimbusStateService(private val logger: Log) {

    fun getNimbusState(compiledSourcePath: String): NimbusState {
        val fileService = FileService(logger)
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
}