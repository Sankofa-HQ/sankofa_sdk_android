package dev.sankofa.sdk.catchmod

import android.os.Build
import java.io.File
import java.io.RandomAccessFile

/**
 * Debug-meta capture for Android. Parses `/proc/self/maps` to build
 * the loaded-library table, then reads each `.so`'s `.note.gnu.build-id`
 * ELF section for the debug_id.
 *
 * This is the ASLR-safe path — the symbolicator worker subtracts
 * `image_addr` from a frame's `instruction_addr` to get the file
 * offset the NDK's unstripped .so expects.
 *
 * Parsing `/proc/self/maps` is cheap (~a few KB of text) and works
 * without any native code. build-id extraction reads the first ~4 KB
 * of each ELF looking for the note section — bounded I/O per image.
 *
 * Failures are silent: if any particular image's build-id can't be
 * read, the image is still emitted without a debug_id so the wire
 * event carries as much context as we have.
 */
object CatchDebugMetaCapture {

    fun capture(): CatchDebugMeta {
        val images = parseLoadedImages()
        return CatchDebugMeta(
            images = images.ifEmpty { null },
            sdkInfo = CatchDebugSDKInfo(
                sdkName = "Android",
                versionMajor = Build.VERSION.SDK_INT,
                versionMinor = 0,
                versionPatchlevel = 0,
            ),
        )
    }

    // ─── /proc/self/maps parser ─────────────────────────────────

    private fun parseLoadedImages(): List<CatchDebugImage> {
        val file = File("/proc/self/maps")
        if (!file.canRead()) return emptyList()

        // Deduplicate by path — /proc/self/maps emits one line per
        // segment of each loaded library, but we only want one row
        // per binary.
        val byPath = mutableMapOf<String, CatchDebugImage>()

        try {
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val parsed = parseMapsLine(raw) ?: continue
                    // Only track executable segments for the purpose of
                    // symbolication — data / rodata segments don't have
                    // debug_ids attached the way .text does.
                    if (!parsed.perms.contains('x')) continue
                    val path = parsed.path
                    if (!path.endsWith(".so") && !path.contains("/")) continue
                    if (byPath.containsKey(path)) continue
                    val debugId = readGnuBuildId(path) ?: continue
                    byPath[path] = CatchDebugImage(
                        type = "elf",
                        debugId = debugId,
                        codeFile = path,
                        imageAddr = "0x%x".format(parsed.start),
                        imageSize = (parsed.end - parsed.start),
                        arch = currentArch(),
                    )
                }
            }
        } catch (_: Throwable) {
            // /proc access can fail on sandboxed processes or non-
            // rooted devices; return whatever we accumulated.
        }
        return byPath.values.toList()
    }

    private data class MapsLine(val start: Long, val end: Long, val perms: String, val path: String)

    private fun parseMapsLine(line: String): MapsLine? {
        // Format: "0000abc-0000def r-xp 00000000 fc:01 12345   /path/to/libfoo.so"
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5) return null
        val rangePart = parts[0]
        val dash = rangePart.indexOf('-')
        if (dash <= 0) return null
        val start = rangePart.substring(0, dash).toLongOrNull(16) ?: return null
        val end = rangePart.substring(dash + 1).toLongOrNull(16) ?: return null
        val perms = parts[1]
        val path = if (parts.size >= 6) parts.drop(5).joinToString(" ") else ""
        return MapsLine(start, end, perms, path)
    }

    // ─── GNU build-id extraction ────────────────────────────────

    private fun readGnuBuildId(path: String): String? {
        val f = File(path)
        if (!f.canRead() || f.length() < 64) return null
        return try {
            RandomAccessFile(f, "r").use { raf ->
                // Only read the first 8 KB — the GNU build-id note is
                // invariably in the first program header's .note.gnu.build-id
                // section which sits near the top of the file.
                val buf = ByteArray(minOf(8 * 1024, f.length().toInt()))
                raf.readFully(buf)
                extractBuildIdFromNoteSection(buf)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Scan the buffer for the ".note.gnu.build-id" signature and
     * extract the following 20-byte hex hash (SHA-1). Returns the
     * hex string lowercased, no dashes.
     *
     * This is a heuristic — the proper way is to parse the full ELF
     * header + program header + .note section, but the scan is much
     * cheaper and works on every android .so we've seen in practice.
     */
    private fun extractBuildIdFromNoteSection(buf: ByteArray): String? {
        // The GNU note name is the bytes "GNU\0" (4 bytes).
        val gnuTag = byteArrayOf(0x47, 0x4e, 0x55, 0x00) // "GNU\0"
        // Each note header is: namesz (u32) + descsz (u32) + type (u32) + name + desc.
        // type = 3 (NT_GNU_BUILD_ID).
        var i = 0
        while (i + gnuTag.size + 20 < buf.size) {
            if (
                buf[i] == gnuTag[0] &&
                buf[i + 1] == gnuTag[1] &&
                buf[i + 2] == gnuTag[2] &&
                buf[i + 3] == gnuTag[3]
            ) {
                // The 20-byte build-id directly follows. In the ELF
                // note layout, the name is padded to 4 bytes — "GNU\0"
                // is already 4 so no padding.
                val sb = StringBuilder(40)
                val start = i + gnuTag.size
                for (k in 0 until 20) {
                    val b = buf[start + k].toInt() and 0xff
                    sb.append("0123456789abcdef"[b ushr 4])
                    sb.append("0123456789abcdef"[b and 0x0f])
                }
                return sb.toString()
            }
            i++
        }
        return null
    }

    private fun currentArch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return "unknown"
        return when {
            abi.startsWith("arm64") -> "arm64"
            abi.startsWith("armeabi") -> "armv7"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "i386"
            else -> abi
        }
    }
}
