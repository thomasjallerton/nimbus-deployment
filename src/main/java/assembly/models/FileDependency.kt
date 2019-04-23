package assembly.models

import java.util.jar.JarOutputStream

data class FileDependency(val outputFile: JarOutputStream, val dependencies: Set<String>)
