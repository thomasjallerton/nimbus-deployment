package assembly.models

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStreamAsync

class JarOutputStreamAsync(outputStream: OutputStream, man: Manifest) : ZipOutputStreamAsync(outputStream) {

    private val JAR_MAGIC = 0xCAFE

    /**
     * Creates a new `JarOutputStream` with the specified
     * `Manifest`. The man is written as the first
     * entry to the output stream.
     *
     * @param out the actual output stream
     * @param man the optional `Manifest`
     */
    init {
        val e = ZipEntry(JarFile.MANIFEST_NAME)
        putNextEntry(e)
        man.write(BufferedOutputStream(this))
        closeEntry()
    }

    /**
     * Begins writing a new JAR file entry and positions the stream
     * to the start of the entry data. This method will also close
     * any previous entry. The default compression method will be
     * used if no compression method was specified for the entry.
     * The current time will be used if the entry has no set modification
     * time.
     *
     * @param ze the ZIP/JAR entry to be written
     * @exception ZipException if a ZIP error has occurred
     * @exception IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    override fun putNextEntry(ze: ZipEntry) {
        if (firstEntry) {
            // Make sure that extra field data for first JAR
            // entry includes JAR magic number id.
            var edata: ByteArray? = ze.extra
            if (edata == null || !hasMagic(edata)) {
                if (edata == null) {
                    edata = ByteArray(4)
                } else {
                    // Prepend magic to existing extra data
                    val tmp = ByteArray(edata.size + 4)
                    System.arraycopy(edata, 0, tmp, 4, edata.size)
                    edata = tmp
                }
                set16(edata, 0, JAR_MAGIC) // extra field id
                set16(edata, 2, 0)         // extra field size
                ze.extra = edata
            }
            firstEntry = false
        }
        super.putNextEntry(ze)
    }

    private var firstEntry = true

    /*
     * Returns true if specified byte array contains the
     * jar magic extra field id.
     */
    private fun hasMagic(edata: ByteArray): Boolean {
        try {
            var i = 0
            while (i < edata.size) {
                if (get16(edata, i) == JAR_MAGIC) {
                    return true
                }
                i += get16(edata, i + 2) + 4
            }
        } catch (e: ArrayIndexOutOfBoundsException) {
            // Invalid extra field data
        }

        return false
    }

    /*
     * Fetches unsigned 16-bit value from byte array at specified offset.
     * The bytes are assumed to be in Intel (little-endian) byte order.
     */
    private fun get16(b: ByteArray, off: Int): Int {
        return java.lang.Byte.toUnsignedInt(b[off]) or (java.lang.Byte.toUnsignedInt(b[off + 1]) shl 8)
    }

    /*
     * Sets 16-bit value at specified offset. The bytes are assumed to
     * be in Intel (little-endian) byte order.
     */
    private fun set16(b: ByteArray, off: Int, value: Int) {
        b[off + 0] = value.toByte()
        b[off + 1] = (value shr 8).toByte()
    }
}