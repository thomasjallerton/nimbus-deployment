package assembly.models

import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class JarDrivers {

    private val drivers: MutableMap<String, String> = mutableMapOf()

    fun addDriver(entry: String, contents: String) {
        drivers[entry] = if (drivers.containsKey(entry)) {
            val current = drivers[entry]!!
            if (current.endsWith('\n')) {
                current + contents
            } else {
                current + '\n' + contents
            }
        } else {
            contents
        }
    }

    fun writeDrivers(jarOutput: JarOutputStream) {
        for ((entry, contents) in drivers) {
            val newEntry = JarEntry(entry)
            jarOutput.putNextEntry(newEntry)
            val writer = jarOutput.writer()
            writer.write(contents)
            writer.flush()
            jarOutput.closeEntry()
        }
    }
}