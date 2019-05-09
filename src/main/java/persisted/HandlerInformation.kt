package persisted

import assembly.ClientType
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class HandlerInformation(
        val handlerClassPath: String = "",
        val handlerFile: String = "",
        val usesClients: Set<ClientType> = setOf(),
        val extraDependencies: Set<String> = setOf(),
        val replacementVariable: String = ""
)