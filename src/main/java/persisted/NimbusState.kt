package persisted

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class NimbusState(
        val projectName: String = "",
        val compilationTimeStamp: String  = "",
        val afterDeployments: Map<String, List<String>> = mutableMapOf(),
        val fileUploads: MutableMap<String, MutableMap<String, List<FileUploadDescription>>> = mutableMapOf(),
        val exports: MutableMap<String, MutableList<ExportInformation>> = mutableMapOf(),
        val handlerFiles: MutableSet<HandlerInformation> = mutableSetOf(),
        val assemble: Boolean = false
)