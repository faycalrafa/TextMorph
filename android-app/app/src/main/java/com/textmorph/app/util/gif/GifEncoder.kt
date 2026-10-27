package com.textmorph.app.util.gif

import android.graphics.Bitmap
import com.textmorph.app.util.AppLogger
import java.io.IOException
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.min

/**
 * High-performance, zero-dependency pure Kotlin GIF89a Animated Encoder.
 * Writes standard animated GIF streams with 24fps delay, Netscape looping,
 * fast 256-color palette quantization, and LZW sub-block compression.
 */
class GifEncoder {

    private val TAG = "GifEncoder"
    private var width = 0
    private var height = 0
    private var delay = 4 // hundredths of a second (4 = 40ms = 25fps)
    private var repeat = 0 // 0 = loop forever
    private var out: OutputStream? = null
    private var isStarted = false

    private lateinit var pixels: ByteArray
    private lateinit var indexedPixels: ByteArray
    private lateinit var colorTab: ByteArray

    fun setDelay(ms: Int) {
        delay = (ms / 10).coerceAtLeast(1)
    }

    fun setFrameRate(fps: Float) {
        if (fps > 0f) {
            delay = (100f / fps).toInt().coerceAtLeast(1)
        }
    }

    fun setRepeat(iter: Int) {
        repeat = iter
    }

    fun start(os: OutputStream): Boolean {
        out = os
        return try {
            writeString("GIF89a") // Header
            isStarted = true
            true
        } catch (e: IOException) {
            AppLogger.e(TAG, "Failed writing GIF header: ${e.message}", e)
            false
        }
    }

    fun addFrame(im: Bitmap): Boolean {
        if (!isStarted || out == null) return false
        val w = im.width
        val h = im.height
        val rgb = IntArray(w * h)
        im.getPixels(rgb, 0, w, 0, 0, w, h)

        val rgbBytes = ByteArray(rgb.size * 3)
        var count = 0
        for (pixel in rgb) {
            rgbBytes[count++] = ((pixel shr 16) and 0xFF).toByte()
            rgbBytes[count++] = ((pixel shr 8) and 0xFF).toByte()
            rgbBytes[count++] = (pixel and 0xFF).toByte()
        }
        return addFrameRgb(w, h, rgbBytes)
    }

    fun addFrameRgb(w: Int, h: Int, rgbPixels: ByteArray): Boolean {
        if (!isStarted || out == null) return false
        return try {
            if (width == 0 || height == 0) {
                width = w
                height = h
                writeLSD()
                if (repeat >= 0) {
                    writeNetscapeExt()
                }
            }

            pixels = rgbPixels
            analyzeColors()
            writeGraphicCtrlExt()
            writeImageDesc()
            writePalette()
            writePixels()
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed adding frame to GIF: ${e.message}", e)
            false
        }
    }

    fun finish(): Boolean {
        if (!isStarted) return false
        isStarted = false
        var ok = true
        try {
            out?.write(0x3B) // GIF Trailer
            out?.flush()
        } catch (e: IOException) {
            AppLogger.e(TAG, "Error finishing GIF: ${e.message}", e)
            ok = false
        }
        width = 0
        height = 0
        out = null
        return ok
    }

    private fun analyzeColors() {
        val nPix = pixels.size / 3
        indexedPixels = ByteArray(nPix)

        // Generate a 256-color palette via uniform color quantization
        // 3 bits R (8 levels), 3 bits G (8 levels), 2 bits B (4 levels) = 256 colors
        colorTab = ByteArray(256 * 3)
        var idx = 0
        for (r in 0 until 8) {
            for (g in 0 until 8) {
                for (b in 0 until 4) {
                    colorTab[idx++] = (r * 255 / 7).toByte()
                    colorTab[idx++] = (g * 255 / 7).toByte()
                    colorTab[idx++] = (b * 255 / 3).toByte()
                }
            }
        }

        // Map each pixel to the nearest palette color
        var pixIdx = 0
        for (i in 0 until nPix) {
            val r = pixels[pixIdx++].toInt() and 0xFF
            val g = pixels[pixIdx++].toInt() and 0xFF
            val b = pixels[pixIdx++].toInt() and 0xFF

            val rIdx = (r * 7 + 127) / 255
            val gIdx = (g * 7 + 127) / 255
            val bIdx = (b * 3 + 127) / 255

            val index = (rIdx shl 5) or (gIdx shl 2) or bIdx
            indexedPixels[i] = index.toByte()
        }
    }

    private fun writeGraphicCtrlExt() {
        val os = out ?: return
        os.write(0x21) // Extension introducer
        os.write(0xF9) // GCE label
        os.write(4)    // Block size
        os.write(0)    // Packed fields (no transparency, disposal: do not dispose)
        writeShort(delay) // Delay time in hundredths of a second
        os.write(0)    // Transparent color index
        os.write(0)    // Block terminator
    }

    private fun writeImageDesc() {
        val os = out ?: return
        os.write(0x2C) // Image separator
        writeShort(0)  // Image left position
        writeShort(0)  // Image top position
        writeShort(width)
        writeShort(height)
        os.write(0x87) // Local Color Table flag (0x80) + 256 colors (0x07)
    }

    private fun writeLSD() {
        writeShort(width)
        writeShort(height)
        out?.write(0x70) // Global color table flag = 0, color resolution = 7
        out?.write(0)    // Background color index
        out?.write(0)    // Pixel aspect ratio
    }

    private fun writePalette() {
        out?.write(colorTab)
    }

    private fun writeNetscapeExt() {
        val os = out ?: return
        os.write(0x21) // Extension introducer
        os.write(0xFF) // Application extension
        os.write(11)   // Block size
        writeString("NETSCAPE2.0")
        os.write(3)    // Sub-block size
        os.write(1)    // Loop sub-block ID
        writeShort(repeat) // Loop count (0 = infinite)
        os.write(0)    // Block terminator
    }

    private fun writePixels() {
        val encoder = LzwEncoder(width, height, indexedPixels, 8)
        encoder.encode(out ?: return)
    }

    private fun writeShort(value: Int) {
        val os = out ?: return
        os.write(value and 0xFF)
        os.write((value shr 8) and 0xFF)
    }

    private fun writeString(s: String) {
        val os = out ?: return
        for (i in s.indices) {
            os.write(s[i].code)
        }
    }
}

/**
 * GIF LZW Stream Compressor. Encodes index byte array into variable-width LZW sub-blocks.
 */
internal class LzwEncoder(
    private val imgW: Int,
    private val imgH: Int,
    private val pixAry: ByteArray,
    private val initCodeSize: Int
) {
    private val BITS = 12
    private val HSIZE = 5003
    private val EOF = -1

    private var cur_accum = 0
    private var cur_bits = 0
    private val masks = intArrayOf(
        0x0000, 0x0001, 0x0003, 0x0007, 0x000F, 0x001F, 0x003F, 0x007F,
        0x00FF, 0x01FF, 0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
    )

    private val htab = IntArray(HSIZE)
    private val codetab = IntArray(HSIZE)
    private val accum = ByteArray(256)
    private var a_count = 0

    private var n_bits = 0
    private var maxcode = 0
    private var free_ent = 0
    private var clear_flg = false
    private var g_init_bits = 0
    private var ClearCode = 0
    private var EOFCode = 0
    private var curPixel = 0

    fun encode(os: OutputStream) {
        os.write(initCodeSize)
        curPixel = 0
        compress(initCodeSize + 1, os)
        os.write(0) // Block terminator
    }

    private fun compress(init_bits: Int, os: OutputStream) {
        g_init_bits = init_bits
        clear_flg = false
        n_bits = g_init_bits
        maxcode = (1 shl n_bits) - 1

        ClearCode = 1 shl (init_bits - 1)
        EOFCode = ClearCode + 1
        free_ent = ClearCode + 2

        a_count = 0
        var ent = nextPixel()

        var hshift = 0
        var fcode = HSIZE
        while (fcode < 65536) {
            hshift++
            fcode *= 2
        }
        hshift = 8 - hshift

        for (i in 0 until HSIZE) htab[i] = -1

        output(ClearCode, os)

        var c = nextPixel()
        while (c != EOF) {
            fcode = (c shl BITS) + ent
            var i = (c shl hshift) xor ent

            if (htab[i] == fcode) {
                ent = codetab[i]
                c = nextPixel()
                continue
            } else if (htab[i] >= 0) {
                var disp = HSIZE - i
                if (i == 0) disp = 1
                var found = false
                while (true) {
                    i -= disp
                    if (i < 0) i += HSIZE
                    if (htab[i] == fcode) {
                        ent = codetab[i]
                        found = true
                        break
                    }
                    if (htab[i] < 0) break
                }
                if (found) {
                    c = nextPixel()
                    continue
                }
            }

            output(ent, os)
            ent = c
            if (free_ent < (1 shl BITS)) {
                codetab[i] = free_ent++
                htab[i] = fcode
            } else {
                cl_block(os)
            }
            c = nextPixel()
        }

        output(ent, os)
        output(EOFCode, os)
    }

    private fun output(code: Int, os: OutputStream) {
        cur_accum = cur_accum and masks[cur_bits]
        cur_accum = if (cur_bits > 0) cur_accum or (code shl cur_bits) else code
        cur_bits += n_bits

        while (cur_bits >= 8) {
            accum[a_count++] = (cur_accum and 0xFF).toByte()
            if (a_count >= 254) flush_char(os)
            cur_accum = cur_accum shr 8
            cur_bits -= 8
        }

        if (free_ent > maxcode || clear_flg) {
            if (clear_flg) {
                n_bits = g_init_bits
                maxcode = (1 shl n_bits) - 1
                clear_flg = false
            } else {
                n_bits++
                maxcode = if (n_bits == BITS) 1 shl BITS else (1 shl n_bits) - 1
            }
        }

        if (code == EOFCode) {
            while (cur_bits > 0) {
                accum[a_count++] = (cur_accum and 0xFF).toByte()
                if (a_count >= 254) flush_char(os)
                cur_accum = cur_accum shr 8
                cur_bits -= 8
            }
            flush_char(os)
        }
    }

    private fun cl_block(os: OutputStream) {
        for (i in 0 until HSIZE) htab[i] = -1
        free_ent = ClearCode + 2
        clear_flg = true
        output(ClearCode, os)
    }

    private fun flush_char(os: OutputStream) {
        if (a_count > 0) {
            os.write(a_count)
            os.write(accum, 0, a_count)
            a_count = 0
        }
    }

    private fun nextPixel(): Int {
        if (curPixel >= pixAry.size) return EOF
        return pixAry[curPixel++].toInt() and 0xFF
    }
}
