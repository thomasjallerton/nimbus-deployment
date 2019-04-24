package persisted

import assembly.ClientType

data class HandlerInformation(
        val handlerClassPath: String = "",
        val handlerFile: String = "",
        val usesClients: Set<ClientType> = setOf(),
        val extraDependencies: Set<String> = setOf()
)