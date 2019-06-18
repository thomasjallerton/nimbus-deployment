package assembly.models

import java.util.jar.JarOutputStream

data class AssemblyDependencies(
        //Path of jar -> It's Dependencies
        val externalDependencies: MutableMap<String, JarDependency> = mutableMapOf(),
        //Jar path (i.e. with '/') -> Jar's to write to
        val localDependencies: MutableMap<String, MutableList<JarOutputStream>> = mutableMapOf(),
        //Path of local resource -> Jar's to write to
        val localResources: MutableMap<String, LocalResource> = mutableMapOf()
)