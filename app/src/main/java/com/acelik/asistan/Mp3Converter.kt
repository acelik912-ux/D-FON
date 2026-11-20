package com.acelik.asistan

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import de.sciss.jump3r.mp3.BRHist
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.IOException
import java.io.OutputStream

/**
 * Telefondaki ses/video dosyasını MP3'e çevirir. Tamamen telefonda çalışır:
 * Android'in kendi çözücüsüyle (MediaCodec) PCM'e açar, LAME'in Java sürümüyle (jump3r) MP3'e kodlar.
 */
object Mp3Converter {

    private class Enc(channels: Int, sampleRate: Int, kbps: Int) {
        val lame = Lame()
        val gfp: LameGlobalFlags

        init {
            val gaud = GetAudio(); val ga = GainAnalysis(); val bs = BitStream(); val p = Presets()
            val qupvt = QuantizePVT(); val qu = Quantize(); val vbr = VBRTag(); val ver = Version()
            val id3 = ID3Tag(); val rv = Reservoir(); val tak = Takehiro(); val parse = Parse()
            BRHist()
            val mpg = MPGLib(); val intf = Interface(); val common = Common()
            lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
            bs.setModules(ga, mpg, ver, vbr)
            id3.setModules(bs, ver)
            p.setModules(lame)
            qu.setModules(bs, rv, qupvt, tak)
            qupvt.setModules(tak, rv, lame.enc.psy)
            rv.setModules(bs)
            tak.setModules(qupvt)
            vbr.setModules(lame, bs, ver)
            gaud.setModules(parse, mpg)
            parse.setModules(ver, id3, p)
            mpg.setModules(intf, common)
            intf.setModules(vbr, common)

            gfp = lame.lame_init()
            gfp.num_channels = channels
            gfp.in_samplerate = sampleRate
            gfp.brate = kbps
            gfp.quality = 5
            id3.id3tag_init(gfp)
            gfp.write_id3tag_automatic = false
            val rc = lame.lame_init_params(gfp)
            if (rc < 0) throw IOException("MP3 kodlayıcı başlatılamadı ($rc)")
        }

        val out = ByteArray(256 * 1024)
        fun encode(l: IntArray, r: IntArray, n: Int, os: OutputStream) {
            val k = lame.lame_encode_buffer_int(gfp, l, r, n, out, 0, out.size)
            if (k < 0) throw IOException("MP3 kodlama hatası ($k)")
            if (k > 0) os.write(out, 0, k)
        }
        fun finish(os: OutputStream) {
            val k = lame.lame_encode_flush(gfp, out, 0, out.size)
            if (k > 0) os.write(out, 0, k)
            lame.lame_close(gfp)
        }
    }

    /** Dosyada ses izi var mı? */
    fun hasAudio(c: Context, uri: Uri): Boolean = try {
        val ex = MediaExtractor(); ex.setDataSource(c, uri, null)
        val ok = (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.release(); ok
    } catch (e: Exception) { false }

    /** @return süre (sn) */
    fun convert(c: Context, uri: Uri, os: OutputStream, kbps: Int = 192): Long {
        val ex = MediaExtractor()
        ex.setDataSource(c, uri, null)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { ex.release(); throw IOException("Bu dosyada ses yok.") }
        ex.selectTrack(track)
        val inFmt = ex.getTrackFormat(track)
        val mime = inFmt.getString(MediaFormat.KEY_MIME)!!
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(inFmt, null, null, 0)
        dec.start()
        var enc: Enc? = null
        var ch = inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var pcmFloat = false
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        var maxUs = 0L
        var l = IntArray(0); var r = IntArray(0)
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = dec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = dec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            dec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    val buf = dec.getOutputBuffer(oi)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    if (info.size > 0) {
                        if (enc == null) enc = Enc(if (ch >= 2) 2 else 1, rate, kbps)
                        val bps = if (pcmFloat) 4 else 2
                        val frames = info.size / (bps * ch)
                        if (l.size < frames) { l = IntArray(frames); r = IntArray(frames) }
                        val bb = buf.order(java.nio.ByteOrder.nativeOrder())
                        for (i in 0 until frames) {
                            val base = info.offset + i * bps * ch
                            fun s(k: Int): Int = if (pcmFloat) {
                                (bb.getFloat(base + k * 4).coerceIn(-1f, 1f) * 32767f).toInt() shl 16
                            } else bb.getShort(base + k * 2).toInt() shl 16
                            l[i] = s(0)
                            r[i] = if (ch >= 2) s(1) else l[i]
                        }
                        enc.encode(l, r, frames, os)
                        maxUs = maxOf(maxUs, info.presentationTimeUs)
                    }
                    dec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            enc?.finish(os) ?: throw IOException("Ses çözülemedi.")
        } finally {
            try { dec.stop() } catch (ignored: Exception) {}
            dec.release(); ex.release()
        }
        return maxUs / 1_000_000
    }
}
