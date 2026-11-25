package dev.petrov.ymplayer2.offline

import dev.petrov.ymplayer2.core.Track
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream

internal data class Mp3Picture(val mime: String, val bytes: ByteArray)

internal object Mp3Tags {
    private const val MAX_TAG = 16 * 1024 * 1024

    fun write(output: OutputStream, track: Track, index: Int, count: Int, picture: Mp3Picture?) {
        val frames = ByteArrayOutputStream()
        val data = DataOutputStream(frames)
        writeTrack(data, track, 3)
        textFrame(data, "TRCK", "${index + 1}/$count", 3)
        picture?.let { pictureFrame(data, it, 3) }
        writeTag(output, 3, frames.toByteArray())
    }

    /** Keep the original MPEG data and the frames of an ordinary ID3v2.3/v2.4 tag. */
    suspend fun embed(input: BufferedInputStream, output: OutputStream, track: Track, picture: Mp3Picture) {
        val tag = readTag(input)
        val major = tag?.major ?: 3
        val frames = ByteArrayOutputStream()
        val data = DataOutputStream(frames)
        if (tag == null) writeTrack(data, track, major)
        else {
            val present = copyFrames(tag, data)
            if ("TIT2" !in present) textFrame(data, "TIT2", track.title, major)
            if ("TPE1" !in present) textFrame(data, "TPE1", track.artist, major)
            if ("TALB" !in present) textFrame(data, "TALB", track.album, major)
            if ("TCON" !in present) textFrame(data, "TCON", track.genre, major)
        }
        pictureFrame(data, picture, major)
        writeTag(output, major, frames.toByteArray())
        val buffer = ByteArray(128 * 1024)
        var hasAudio = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            hasAudio = true
            output.write(buffer, 0, read)
        }
        if (!hasAudio) throw IOException("Empty MP3")
    }

    private data class Tag(val major: Int, val bytes: ByteArray)

    private fun readTag(input: BufferedInputStream): Tag? {
        input.mark(10)
        val header = ByteArray(10)
        val filled = read(input, header)
        if (filled < 3 || String(header, 0, 3, Charsets.US_ASCII) != "ID3") {
            input.reset()
            return null
        }
        if (filled != header.size) throw EOFException("Incomplete ID3 header")
        val major = header[3].toInt()
        // Rewriting a tag with a CRC, unsynchronisation or footer requires a different
        // parser. Keep such a source and its existing verified cover intact.
        if (major !in 3..4 || header[5].toInt() != 0) throw IOException("Unsupported ID3 layout")
        val size = frameSize(header, 6, synchsafe = true)
        if (size !in 0..MAX_TAG) throw IOException("Invalid ID3 size")
        return Tag(major, ByteArray(size).also {
            if (read(input, it) != it.size) throw EOFException("Incomplete ID3 data")
        })
    }

    private fun copyFrames(tag: Tag, output: DataOutputStream): Set<String> {
        val present = mutableSetOf<String>()
        var offset = 0
        while (offset < tag.bytes.size && tag.bytes[offset].toInt() != 0) {
            if (tag.bytes.size - offset < 10) throw IOException("Incomplete ID3 frame")
            val id = String(tag.bytes, offset, 4, Charsets.US_ASCII)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) throw IOException("Invalid ID3 frame name")
            val size = frameSize(tag.bytes, offset + 4, synchsafe = tag.major == 4)
            if (size !in 0..tag.bytes.size - offset - 10) throw IOException("Invalid ID3 frame size")
            if (id != "APIC") {
                output.write(tag.bytes, offset, 10 + size)
                present += id
            }
            offset += 10 + size
        }
        return present
    }

    private fun read(input: BufferedInputStream, bytes: ByteArray): Int {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) break
            offset += count
        }
        return offset
    }

    private fun writeTrack(out: DataOutputStream, track: Track, major: Int) {
        textFrame(out, "TIT2", track.title, major)
        textFrame(out, "TPE1", track.artist, major)
        textFrame(out, "TALB", track.album, major)
        textFrame(out, "TCON", track.genre, major)
    }

    private fun textFrame(out: DataOutputStream, id: String, text: String, major: Int) {
        if (text.isBlank()) return
        val data = text.take(1000).toByteArray(Charsets.UTF_16)
        frameHeader(out, id, data.size + 1, major)
        out.writeByte(1)
        out.write(data)
    }

    private fun pictureFrame(out: DataOutputStream, picture: Mp3Picture, major: Int) {
        val mime = picture.mime.toByteArray(Charsets.US_ASCII)
        frameHeader(out, "APIC", mime.size + 4 + picture.bytes.size, major)
        out.writeByte(0)
        out.write(mime)
        out.writeByte(0)
        out.writeByte(3) // Front cover
        out.writeByte(0) // Empty description
        out.write(picture.bytes)
    }

    private fun frameHeader(out: DataOutputStream, id: String, size: Int, major: Int) {
        out.writeBytes(id)
        if (major == 4) out.write(syncsafe(size)) else out.writeInt(size)
        out.writeShort(0)
    }

    private fun writeTag(output: OutputStream, major: Int, bytes: ByteArray) {
        if (bytes.size > MAX_TAG) throw IOException("ID3 tag too large")
        output.write(byteArrayOf(73, 68, 51, major.toByte(), 0, 0))
        output.write(syncsafe(bytes.size))
        output.write(bytes)
    }

    private fun syncsafe(size: Int) = ByteArray(4) { index ->
        ((size ushr ((3 - index) * 7)) and 127).toByte()
    }

    private fun frameSize(bytes: ByteArray, offset: Int, synchsafe: Boolean): Int {
        var value = 0
        for (index in offset until offset + 4) {
            val byte = bytes[index].toInt() and 255
            if (synchsafe && byte >= 128) throw IOException("Invalid ID3 number")
            value = (value shl (if (synchsafe) 7 else 8)) or byte
        }
        return value
    }
}
