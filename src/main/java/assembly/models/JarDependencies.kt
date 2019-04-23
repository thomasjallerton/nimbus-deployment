package assembly.models

import java.util.jar.JarOutputStream

data class JarDependencies(
        //Jar -> It's Dependencies
        val inJarDependencies: MutableMap<String, JarDependency> = mutableMapOf(),
        //ClassPath -> Jar's to write to
        val localDependencies: MutableMap<String, MutableList<JarOutputStream>> = mutableMapOf()
)