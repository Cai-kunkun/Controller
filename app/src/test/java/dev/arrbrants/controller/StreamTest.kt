package dev.arrbrants.controller

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket

/** End-to-end check of the SSE parser against a tiny local fake provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamTest {

    private lateinit var server: ServerSocket
    private lateinit var worker: Thread
    @Volatile private var requestBody = ""

    @Before
    fun setUp() {
        server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        worker = Thread {
            try {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val headers = StringBuilder()
                    while (true) {
                        val line = input.readLine() ?: break
                        headers.append(line).append('\n')
                        if (line.isEmpty()) break
                    }
                    val length = Regex("(?i)content-length:\\s*(\\d+)")
                        .find(headers.toString())?.groupValues?.get(1)?.toInt() ?: 0
                    val body = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(body, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    requestBody = String(body, 0, read)

                    val sse = buildString {
                        append("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n")
                        append("data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n")
                        append(
                            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120," +
                                "\"completion_tokens\":2,\"prompt_cache_hit_tokens\":100," +
                                "\"prompt_cache_miss_tokens\":20}}\n\n"
                        )
                        append("data: [DONE]\n\n")
                    }
                    val payload = sse.toByteArray()
                    val out = socket.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream\r\n" +
                            "Content-Length: ${payload.size}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray()
                    )
                    out.write(payload)
                    out.flush()
                }
            } catch (e: Exception) {
                // The test fails on assertions rather than here.
            }
        }.also { it.start() }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    @Test
    fun streamsDeltasUsageAndTiming() {
        val deltas = ArrayList<String>()
        val reply = LlmClient.stream(
            LlmClient.Config("http://127.0.0.1:${server.localPort}", "", "test-model"),
            listOf(LlmClient.Msg("user", "hi"))
        ) { deltas.add(it) }

        assertEquals(listOf("Hel", "lo"), deltas)
        assertEquals("Hello", reply.text)
        assertEquals(120L, reply.usage.promptTokens)
        assertEquals(2L, reply.usage.completionTokens)
        assertEquals(100L, reply.usage.cacheHitTokens)
        assertEquals(20L, reply.usage.cacheMissTokens)
        assertTrue(reply.usage.tokensPerSecond > 0)
        assertTrue(requestBody.contains("\"stream\":true"))
        assertTrue(requestBody.contains("include_usage"))
    }
}
