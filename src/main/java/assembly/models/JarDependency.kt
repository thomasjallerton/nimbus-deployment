package assembly.models

import java.util.jar.JarOutputStream

data class JarDependency(
        val classOutputs:MutableMap<String, MutableList<JarOutputStream>> = mutableMapOf(),
        var allClasses: MutableList<JarOutputStream> = mutableListOf()
)