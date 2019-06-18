package assembly.models

import java.util.jar.JarOutputStream

data class JarDependency(
        val specificClasses: MutableMap<String, MutableList<JarOutputStream>> = mutableMapOf(),
        val allClasses: MutableSet<JarOutputStream> = mutableSetOf()
)