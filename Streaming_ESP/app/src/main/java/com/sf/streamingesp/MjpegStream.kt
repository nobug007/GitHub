package com.sf.streamingesp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ESP32-CAM 의 `multipart/x-mixed-replace` MJPEG 스트림을 읽어 프레임 단위 Bitmap 으로 넘긴다.
 *
 * 안드로이드에는 MJPEG 디코더가 없어서 파트를 직접 잘라내야 한다. 펌웨어가 파트마다
 * Content-Length 를 붙여주므로 그 길이만큼 정확히 읽는다. 길이 헤더가 없는 경우를 대비해
 * JPEG 마커(FFD8…FFD9) 스캔으로 되돌아가는 경로도 둔다.
 *
 * 스레드 하나가 연결·파싱·디코드를 전담하고, 끊기면 스스로 재접속한다.
 */
class MjpegStream(
    private val url: String,
    private val onFrame: (Bitmap) -> Unit,
    private val onState: (String?) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "mjpeg").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun runLoop() {
        while (running) {
            var conn: HttpURLConnection? = null
            try {
                onState("카메라 연결 중…")
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = StreamConfig.CONNECT_TIMEOUT_MS
                    readTimeout = StreamConfig.READ_TIMEOUT_MS
                    requestMethod = "GET"
                    // MJPEG 은 끝나지 않는 응답이다. 압축을 걸면 파트 경계가 버퍼에 묶여 지연이 생긴다.
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Connection", "close")
                    doInput = true
                }
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP ${conn.responseCode}")
                }
                BufferedInputStream(conn.inputStream, 32 * 1024).use { input ->
                    onState(null)
                    readParts(input)
                }
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "스트림 끊김: ${e.message}")
                    onState("카메라에 연결할 수 없습니다\nWi-Fi \"${StreamConfig.AP_SSID}\" 에 접속되어 있는지 확인하세요")
                }
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
            if (!running) break
            try { Thread.sleep(StreamConfig.RECONNECT_DELAY_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun readParts(input: InputStream) {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var contentLength = -1
        var inPartHeader = false          // "--boundary" 를 지나 헤더 블록 안에 있는가
        var buffer = ByteArray(64 * 1024)
        var frames = 0L

        while (running) {
            val line = readLine(input) ?: return

            when {
                // 본문은 "\r\n--boundary\r\n" 로 시작한다. 즉 첫 줄이 빈 줄이다.
                // 경계선을 만나야 비로소 파트 헤더가 시작되므로, 그 전의 빈 줄은 무시해야 한다.
                // (이 구분이 없으면 맨 앞의 빈 줄을 본문 시작으로 착각해 Content-Length 를
                //  영영 읽지 못하고 매 프레임을 바이트 단위로 훑게 된다.)
                line.startsWith("--") -> {
                    inPartHeader = true
                    contentLength = -1
                }
                line.startsWith("Content-Length:", ignoreCase = true) -> {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
                }
                // 빈 줄 = 파트 헤더의 끝. 바로 다음부터 JPEG 본문이다.
                line.isEmpty() && inPartHeader -> {
                    inPartHeader = false
                    if (contentLength > 0) {
                        if (buffer.size < contentLength) buffer = ByteArray(contentLength)
                        readFully(input, buffer, contentLength)
                        BitmapFactory.decodeByteArray(buffer, 0, contentLength, opts)
                            ?.let { onFrame(it) }
                    } else {
                        // Content-Length 를 주지 않는 펌웨어를 위한 대비책.
                        val jpeg = scanJpeg(input) ?: return
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)?.let { onFrame(it) }
                    }
                    contentLength = -1
                    if (++frames % 30L == 0L) Log.i(TAG, "수신 ${frames}프레임")
                }
            }
        }
    }

    /** CRLF 로 끝나는 헤더 한 줄. 스트림이 끝나면 null. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(64)
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 1024) return sb.toString()   // 헤더가 아니면 더 읽지 않는다
        }
    }

    private fun readFully(input: InputStream, dst: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(dst, off, len - off)
            if (n < 0) throw IllegalStateException("스트림 조기 종료")
            off += n
        }
    }

    /** Content-Length 가 없을 때 SOI(FFD8)~EOI(FFD9) 구간을 직접 찾아 잘라낸다. */
    private fun scanJpeg(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream(48 * 1024)
        var prev = -1
        var started = false
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (!started) {
                if (prev == 0xFF && c == 0xD8) {
                    started = true
                    out.write(0xFF); out.write(0xD8)
                }
            } else {
                out.write(c)
                if (prev == 0xFF && c == 0xD9) return out.toByteArray()
            }
            prev = c
        }
    }

    private companion object { const val TAG = "MjpegStream" }
}
