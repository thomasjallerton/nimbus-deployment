package java.util.zip

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.zip.ZipConstants64.*
import java.util.zip.ZipUtils.fileTimeToUnixTime
import java.util.zip.ZipUtils.get16

open class ZipOutputStreamAsync(out: OutputStream, charset: Charset?) : DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION, true)) {

    /**
     * Compression method for uncompressed (STORED) entries.
     */
    val STORED = ZipEntry.STORED

    /**
     * Compression method for compressed (DEFLATED) entries.
     */
    val DEFLATED = ZipEntry.DEFLATED

    private val inhibitZip64 = java.lang.Boolean.parseBoolean(
            java.security.AccessController.doPrivileged(
                    sun.security.action.GetPropertyAction(
                            "jdk.util.zip.inhibitZip64", "false")))

    private class XEntry(internal val entry: ZipEntry, internal val offset: Long)

    private var current: XEntry? = null
    private val xentries = Vector<XEntry>()
    private val names = HashSet<String>()
    private val crc = CRC32()
    private var written: Long = 0
    private var locoff: Long = 0
    private var comment: ByteArray? = null
    private var method = DEFLATED
    private var finished: Boolean = false

    private var closed = false

    private val zc: ZipCoder

    @Throws(ZipException::class)
    private fun version(e: ZipEntry): Int {
        return when (e.method) {
            DEFLATED -> 20
            STORED -> 10
            else -> throw ZipException("unsupported compression method")
        }
    }

    /**
     * Checks to make sure that this stream has not been closed.
     */
    @Throws(IOException::class)
    private fun ensureOpen() {
        if (closed) {
            throw IOException("Stream closed")
        }
    }


    /**
     * Creates a new ZIP output stream.
     *
     *
     * The UTF-8 [charset][java.nio.charset.Charset] is used
     * to encode the entry names and comments.
     *
     * @param out the actual output stream
     */
    constructor(out: OutputStream) : this(out, StandardCharsets.UTF_8)

    init {

        if (charset == null)
            throw NullPointerException("charset is null")
        this.zc = ZipCoder.get(charset)
        usesDefaultDeflater = true
    }

    /**
     * Sets the ZIP file comment.
     * @param comment the comment string
     * @exception IllegalArgumentException if the length of the specified
     * ZIP file comment is greater than 0xFFFF bytes
     */
    fun setComment(comment: String?) {
        if (comment != null) {
            this.comment = zc.getBytes(comment)
            if (this.comment!!.size > 0xffff)
                throw IllegalArgumentException("ZIP file comment too long.")
        }
    }

    /**
     * Sets the default compression method for subsequent entries. This
     * default will be used whenever the compression method is not specified
     * for an individual ZIP file entry, and is initially set to DEFLATED.
     * @param method the default compression method
     * @exception IllegalArgumentException if the specified compression method
     * is invalid
     */
    fun setMethod(method: Int) {
        if (method != DEFLATED && method != STORED) {
            throw IllegalArgumentException("invalid compression method")
        }
        this.method = method
    }

    /**
     * Sets the compression level for subsequent entries which are DEFLATED.
     * The default setting is DEFAULT_COMPRESSION.
     * @param level the compression level (0-9)
     * @exception IllegalArgumentException if the compression level is invalid
     */
    fun setLevel(level: Int) {
        def.setLevel(level)
    }

    /**
     * Begins writing a new ZIP file entry and positions the stream to the
     * start of the entry data. Closes the current entry if still active.
     * The default compression method will be used if no compression method
     * was specified for the entry, and the current time will be used if
     * the entry has no set modification time.
     * @param e the ZIP entry to be written
     * @exception ZipException if a ZIP format error has occurred
     * @exception IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    open fun putNextEntry(e: ZipEntry) {
        ensureOpen()
        if (current != null) {
            closeEntry()       // close previous entry
        }
        if (e.xdostime == -1L) {
            // by default, do NOT use extended timestamps in extra
            // data, for now.
            e.time = System.currentTimeMillis()
        }
        if (e.method == -1) {
            e.method = method  // use default method
        }
        // store size, compressed size, and crc-32 in LOC header
        e.flag = 0
        when (e.method) {
            DEFLATED ->
                // store size, compressed size, and crc-32 in data descriptor
                // immediately following the compressed entry data
                if (e.size == -1L || e.csize == -1L || e.crc == -1L)
                    e.flag = 8
            STORED -> {
                // compressed size, uncompressed size, and crc-32 must all be
                // set for entries using STORED compression method
                when {
                    e.size == -1L -> e.size = e.csize
                    e.csize == -1L -> e.csize = e.size
                    e.size != e.csize -> throw ZipException(
                            "STORED entry where compressed != uncompressed size")
                }
                if (e.size == -1L || e.crc == -1L) {
                    throw ZipException(
                            "STORED entry missing size, compressed size, or crc-32")
                }
            }
            else -> throw ZipException("unsupported compression method")
        }
        if (!names.add(e.name)) {
            throw ZipException("duplicate entry: " + e.name)
        }
        if (zc.isUTF8)
            e.flag = e.flag or EFS
        current = XEntry(e, written)
        xentries.add(current)
        writeLOC(current!!)
    }

    @Throws(IOException::class)
    fun closeEntry() {
        ensureOpen()
        if (current != null) {
            val e = current!!.entry
            when (e.method) {
                DEFLATED -> {
                    def.finish()
                    while (!def.finished()) {
                        deflate()
                    }
                    if (e.flag and 8 == 0) {
                        // verify size, compressed size, and crc-32 settings
                        if (e.size != def.bytesRead) {
                            throw ZipException(
                                    "invalid entry size (expected " + e.size +
                                            " but got " + def.bytesRead + " bytes)")
                        }
                        if (e.csize != def.bytesWritten) {
                            throw ZipException(
                                    "invalid entry compressed size (expected " +
                                            e.csize + " but got " + def.bytesWritten + " bytes)")
                        }
                        if (e.crc != crc.value) {
                            throw ZipException(
                                    "invalid entry CRC-32 (expected 0x" +
                                            java.lang.Long.toHexString(e.crc) + " but got 0x" +
                                            java.lang.Long.toHexString(crc.value) + ")")
                        }
                    } else {
                        e.size = def.bytesRead
                        e.csize = def.bytesWritten
                        e.crc = crc.value
                        writeEXT(e)
                    }
                    def.reset()
                    written += e.csize
                }
                STORED -> {
                    if (e.size != written - locoff) {
                        throw ZipException(
                                "invalid entry size (expected " + e.size +
                                        " but got " + (written - locoff) + " bytes)")
                    }
                    if (e.crc != crc.value) {
                        throw ZipException(
                                ("invalid entry crc-32 (expected 0x" +
                                        java.lang.Long.toHexString(e.crc) + " but got 0x" +
                                        java.lang.Long.toHexString(crc.value) + ")"))
                    }
                }
                else -> throw ZipException("invalid compression method")
            }
            crc.reset()
            current = null
        }
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        ensureOpen()
        if (off < 0 || len < 0 || off > b.size - len) {
            throw IndexOutOfBoundsException()
        } else if (len == 0) {
            return
        }

        if (current == null) {
            throw ZipException("no current ZIP entry")
        }
        val entry = current!!.entry
        when (entry.method) {
            DEFLATED -> super.write(b, off, len)
            STORED -> {
                written += len.toLong()
                if (written - locoff > entry.size) {
                    throw ZipException(
                            "attempt to write past end of STORED entry")
                }
                out.write(b, off, len)
            }
            else -> throw ZipException("invalid compression method")
        }
        crc.update(b, off, len)
    }

    @Throws(IOException::class)
    override fun finish() {
        ensureOpen()
        if (finished) {
            return
        }
        if (current != null) {
            closeEntry()
        }
        // write central directory
        val off = written
        for (xentry in xentries)
            writeCEN(xentry)
        writeEND(off, written - off)
        finished = true
    }

    /**
     * Closes the ZIP output stream as well as the stream being filtered.
     * @exception ZipException if a ZIP file error has occurred
     * @exception IOException if an I/O error has occurred
     */
    @Throws(IOException::class)
    override fun close() {
        if (!closed) {
            super.close()
            closed = true
        }
    }

    @Throws(IOException::class)
    private fun writeLOC(xentry: XEntry) {
        val e = xentry.entry
        val flag = e.flag
        var hasZip64 = false
        var elen = getExtraLen(e.extra)

        writeInt(ZipConstants.LOCSIG)               // LOC header signature
        if (flag and 8 == 8) {
            writeShort(version(e))     // version needed to extract
            writeShort(flag)           // general purpose bit flag
            writeShort(e.method)       // compression method
            writeInt(e.xdostime)       // last modification time
            // store size, uncompressed size, and crc-32 in data descriptor
            // immediately following compressed entry data
            writeInt(0)
            writeInt(0)
            writeInt(0)
        } else {
            if (e.csize >= ZIP64_MAGICVAL || e.size >= ZIP64_MAGICVAL) {
                hasZip64 = true
                writeShort(45)         // ver 4.5 for zip64
            } else {
                writeShort(version(e)) // version needed to extract
            }
            writeShort(flag)           // general purpose bit flag
            writeShort(e.method)       // compression method
            writeInt(e.xdostime)       // last modification time
            writeInt(e.crc)            // crc-32
            if (hasZip64) {
                writeInt(ZIP64_MAGICVAL)
                writeInt(ZIP64_MAGICVAL)
                elen += 20        //headid(2) + size(2) + size(8) + csize(8)
            } else {
                writeInt(e.csize)  // compressed size
                writeInt(e.size)   // uncompressed size
            }
        }
        val nameBytes = zc.getBytes(e.name)
        writeShort(nameBytes.size)

        var elenEXTT = 0               // info-zip extended timestamp
        var flagEXTT = 0
        if (e.mtime != null) {
            elenEXTT += 4
            flagEXTT = flagEXTT or EXTT_FLAG_LMT
        }
        if (e.atime != null) {
            elenEXTT += 4
            flagEXTT = flagEXTT or EXTT_FLAG_LAT
        }
        if (e.ctime != null) {
            elenEXTT += 4
            flagEXTT = flagEXTT or EXTT_FLAT_CT
        }
        if (flagEXTT != 0)
            elen += elenEXTT + 5    // headid(2) + size(2) + flag(1) + data
        writeShort(elen)
        writeBytes(nameBytes, 0, nameBytes.size)
        if (hasZip64) {
            writeShort(ZIP64_EXTID)
            writeShort(16)
            writeLong(e.size)
            writeLong(e.csize)
        }
        if (flagEXTT != 0) {
            writeShort(EXTID_EXTT)
            writeShort(elenEXTT + 1)      // flag + data
            writeByte(flagEXTT)
            if (e.mtime != null)
                writeInt(fileTimeToUnixTime(e.mtime))
            if (e.atime != null)
                writeInt(fileTimeToUnixTime(e.atime))
            if (e.ctime != null)
                writeInt(fileTimeToUnixTime(e.ctime))
        }
        writeExtra(e.extra)
        locoff = written
    }

    @Throws(IOException::class)
    private fun writeEXT(e: ZipEntry) {
        writeInt(ZipConstants.EXTSIG)           // EXT header signature
        writeInt(e.crc)            // crc-32
        if (e.csize >= ZIP64_MAGICVAL || e.size >= ZIP64_MAGICVAL) {
            writeLong(e.csize)
            writeLong(e.size)
        } else {
            writeInt(e.csize)          // compressed size
            writeInt(e.size)           // uncompressed size
        }
    }

    /*
     * Write central directory (CEN) header for specified entry.
     * REMIND: add support for file attributes
     */
    @Throws(IOException::class)
    private fun writeCEN(xentry: XEntry) {
        val e = xentry.entry
        val flag = e.flag
        val version = version(e)
        var csize = e.csize
        var size = e.size
        var offset = xentry.offset
        var elenZIP64 = 0
        var hasZip64 = false

        if (e.csize >= ZIP64_MAGICVAL) {
            csize = ZIP64_MAGICVAL
            elenZIP64 += 8              // csize(8)
            hasZip64 = true
        }
        if (e.size >= ZIP64_MAGICVAL) {
            size = ZIP64_MAGICVAL    // size(8)
            elenZIP64 += 8
            hasZip64 = true
        }
        if (xentry.offset >= ZIP64_MAGICVAL) {
            offset = ZIP64_MAGICVAL
            elenZIP64 += 8              // offset(8)
            hasZip64 = true
        }
        writeInt(ZipConstants.CENSIG)           // CEN header signature
        if (hasZip64) {
            writeShort(45)         // ver 4.5 for zip64
            writeShort(45)
        } else {
            writeShort(version)    // version made by
            writeShort(version)    // version needed to extract
        }
        writeShort(flag)           // general purpose bit flag
        writeShort(e.method)       // compression method
        writeInt(e.xdostime)       // last modification time
        writeInt(e.crc)            // crc-32
        writeInt(csize)            // compressed size
        writeInt(size)             // uncompressed size
        val nameBytes = zc.getBytes(e.name)
        writeShort(nameBytes.size)

        var elen = getExtraLen(e.extra)
        if (hasZip64) {
            elen += elenZIP64 + 4// + headid(2) + datasize(2)
        }
        // cen info-zip extended timestamp only outputs mtime
        // but set the flag for a/ctime, if present in loc
        var flagEXTT = 0
        if (e.mtime != null) {
            elen += 4              // + mtime(4)
            flagEXTT = flagEXTT or EXTT_FLAG_LMT
        }
        if (e.atime != null) {
            flagEXTT = flagEXTT or EXTT_FLAG_LAT
        }
        if (e.ctime != null) {
            flagEXTT = flagEXTT or EXTT_FLAT_CT
        }
        if (flagEXTT != 0) {
            elen += 5             // headid + sz + flag
        }
        writeShort(elen)
        val commentBytes: ByteArray?
        if (e.comment != null) {
            commentBytes = zc.getBytes(e.comment)
            writeShort(Math.min(commentBytes!!.size, 0xffff))
        } else {
            commentBytes = null
            writeShort(0)
        }
        writeShort(0)              // starting disk number
        writeShort(0)              // internal file attributes (unused)
        writeInt(0)                // external file attributes (unused)
        writeInt(offset)           // relative offset of local header
        writeBytes(nameBytes, 0, nameBytes.size)

        // take care of EXTID_ZIP64 and EXTID_EXTT
        if (hasZip64) {
            writeShort(ZIP64_EXTID)// Zip64 extra
            writeShort(elenZIP64)
            if (size == ZIP64_MAGICVAL)
                writeLong(e.size)
            if (csize == ZIP64_MAGICVAL)
                writeLong(e.csize)
            if (offset == ZIP64_MAGICVAL)
                writeLong(xentry.offset)
        }
        if (flagEXTT != 0) {
            writeShort(EXTID_EXTT)
            if (e.mtime != null) {
                writeShort(5)      // flag + mtime
                writeByte(flagEXTT)
                writeInt(fileTimeToUnixTime(e.mtime))
            } else {
                writeShort(1)      // flag only
                writeByte(flagEXTT)
            }
        }
        writeExtra(e.extra)
        if (commentBytes != null) {
            writeBytes(commentBytes, 0, Math.min(commentBytes.size, 0xffff))
        }
    }

    @Throws(IOException::class)
    private fun writeEND(off: Long, len: Long) {
        var hasZip64 = false
        var xlen = len
        var xoff = off
        if (xlen >= ZIP64_MAGICVAL) {
            xlen = ZIP64_MAGICVAL
            hasZip64 = true
        }
        if (xoff >= ZIP64_MAGICVAL) {
            xoff = ZIP64_MAGICVAL
            hasZip64 = true
        }
        var count = xentries.size
        if (count >= ZIP64_MAGICCOUNT) {
            hasZip64 = hasZip64 or !inhibitZip64
            if (hasZip64) {
                count = ZIP64_MAGICCOUNT
            }
        }
        if (hasZip64) {
            val off64 = written
            //zip64 end of central directory record
            writeInt(ZIP64_ENDSIG)        // zip64 END record signature
            writeLong((ZIP64_ENDHDR - 12).toLong())  // size of zip64 end
            writeShort(45)                // version made by
            writeShort(45)                // version needed to extract
            writeInt(0)                   // number of this disk
            writeInt(0)                   // central directory start disk
            writeLong(xentries.size.toLong())    // number of directory entires on disk
            writeLong(xentries.size.toLong())    // number of directory entires
            writeLong(len)                // length of central directory
            writeLong(off)                // offset of central directory

            //zip64 end of central directory locator
            writeInt(ZIP64_LOCSIG)        // zip64 END locator signature
            writeInt(0)                   // zip64 END start disk
            writeLong(off64)              // offset of zip64 END
            writeInt(1)                   // total number of disks (?)
        }
        writeInt(ZipConstants.ENDSIG)                 // END record signature
        writeShort(0)                    // number of this disk
        writeShort(0)                    // central directory start disk
        writeShort(count)                // number of directory entries on disk
        writeShort(count)                // total number of directory entries
        writeInt(xlen)                   // length of central directory
        writeInt(xoff)                   // offset of central directory
        if (comment != null) {            // zip file comment
            writeShort(comment!!.size)
            writeBytes(comment!!, 0, comment!!.size)
        } else {
            writeShort(0)
        }
    }

    /*
     * Returns the length of extra data without EXTT and ZIP64.
     */
    private fun getExtraLen(extra: ByteArray?): Int {
        if (extra == null)
            return 0
        var skipped = 0
        val len = extra.size
        var off = 0
        while (off + 4 <= len) {
            val tag = get16(extra, off)
            val sz = get16(extra, off + 2)
            if (sz < 0 || off + 4 + sz > len) {
                break
            }
            if (tag == EXTID_EXTT || tag == EXTID_ZIP64) {
                skipped += sz + 4
            }
            off += sz + 4
        }
        return len - skipped
    }

    /*
     * Writes extra data without EXTT and ZIP64.
     *
     * Extra timestamp and ZIP64 data is handled/output separately
     * in writeLOC and writeCEN.
     */
    @Throws(IOException::class)
    private fun writeExtra(extra: ByteArray?) {
        if (extra != null) {
            val len = extra.size
            var off = 0
            while (off + 4 <= len) {
                val tag = get16(extra, off)
                val sz = get16(extra, off + 2)
                if (sz < 0 || off + 4 + sz > len) {
                    writeBytes(extra, off, len - off)
                    return
                }
                if (tag != EXTID_EXTT && tag != EXTID_ZIP64) {
                    writeBytes(extra, off, sz + 4)
                }
                off += sz + 4
            }
            if (off < len) {
                writeBytes(extra, off, len - off)
            }
        }
    }

    /*
     * Writes a 8-bit byte to the output stream.
     */
    @Throws(IOException::class)
    private fun writeByte(v: Int) {
        val out = this.out
        out.write(v and 0xff)
        written += 1
    }

    /*
     * Writes a 16-bit short to the output stream in little-endian byte order.
     */
    @Throws(IOException::class)
    private fun writeShort(v: Int) {
        val out = this.out
        out.write(v.ushr(0) and 0xff)
        out.write(v.ushr(8) and 0xff)
        written += 2
    }

    /*
     * Writes a 32-bit int to the output stream in little-endian byte order.
     */
    @Throws(IOException::class)
    private fun writeInt(v: Long) {
        val out = this.out
        out.write((v.ushr(0) and 0xff).toInt())
        out.write((v.ushr(8) and 0xff).toInt())
        out.write((v.ushr(16) and 0xff).toInt())
        out.write((v.ushr(24) and 0xff).toInt())
        written += 4
    }

    /*
     * Writes a 64-bit int to the output stream in little-endian byte order.
     */
    @Throws(IOException::class)
    private fun writeLong(v: Long) {
        val out = this.out
        out.write((v.ushr(0) and 0xff).toInt())
        out.write((v.ushr(8) and 0xff).toInt())
        out.write((v.ushr(16) and 0xff).toInt())
        out.write((v.ushr(24) and 0xff).toInt())
        out.write((v.ushr(32) and 0xff).toInt())
        out.write((v.ushr(40) and 0xff).toInt())
        out.write((v.ushr(48) and 0xff).toInt())
        out.write((v.ushr(56) and 0xff).toInt())
        written += 8
    }

    /*
     * Writes an array of bytes to the output stream.
     */
    @Throws(IOException::class)
    private fun writeBytes(b: ByteArray, off: Int, len: Int) {
        super.out.write(b, off, len)
        written += len.toLong()
    }
}