package services

import org.apache.maven.plugin.logging.Log
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

class FileService(private val logger: Log) {


    fun getFileText(path: String): String {

        try {
            val encoded = Files.readAllBytes(Paths.get(path))
            return String(encoded)
        } catch (e: IOException) {
            logger.error(e)
        }

        return ""
    }
}