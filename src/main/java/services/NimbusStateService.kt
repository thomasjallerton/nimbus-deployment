package services

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.maven.plugin.logging.Log
import persisted.NimbusState

class NimbusStateService(private val logger: Log) {

    fun getNimbusState(): NimbusState {
        val fileService = FileService(logger)
        val stateText = fileService.getFileText(".nimbus/nimbus-state.json")
        val mapper = ObjectMapper()

        try {
            return mapper.readValue(stateText, NimbusState::class.java)
        } catch (e: Exception) {
            logger.error(e)
        }

        return NimbusState()
    }
}