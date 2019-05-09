package persisted

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class FileUploadDescription(
        val localFile: String = "",
        val targetFile: String = "",
        val substituteVariables: Boolean = false
)