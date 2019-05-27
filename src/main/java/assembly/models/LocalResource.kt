package assembly.models

import java.util.jar.JarOutputStream

data class LocalResource(val filePath: String, val targets: MutableList<JarOutputStream> = mutableListOf())