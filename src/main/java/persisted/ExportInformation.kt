package persisted

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExportInformation(
        val exportName: String = "",
        val exportMessage: String = "",
        val substitutionVariable: String = ""
)