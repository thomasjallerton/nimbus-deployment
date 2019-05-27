package assembly.models

data class MavenDependency(val filePath: String, val requiredClasses: MutableSet<String> = mutableSetOf())