package com.nuvio.tv.livetv.crash

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Shows a report as a small web page on the home network, so it can be opened (and copied) on a
 * phone on the same Wi-Fi. Nothing leaves the home network, there is no size limit (a QR code
 * holds very little), and it only runs while the report screen is open. The address includes a
 * random code, so only someone given the address can read it.
 */
object ReportServer {
    data class Contact(val email: String, val subject: String)

    class Handle(val url: String, private val socket: ServerSocket,
        private val clients: MutableSet<Socket>, private val workers: java.util.concurrent.ExecutorService) {
        fun stop() {
            runCatching { socket.close() }
            clients.toList().forEach { runCatching { it.close() } }
            workers.shutdownNow()
        }
    }

    /** Starts serving [text] (read again on every page load); null when there's no home network. */
    fun start(title: String, contact: Contact? = null, text: () -> String): Handle? {
        val ip = localAddress() ?: return null
        val server = bind() ?: return null
        val token = randomToken()
        val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        val workers = java.util.concurrent.ThreadPoolExecutor(2, 2, 0, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue(8), java.util.concurrent.ThreadFactory { task ->
                Thread(task, "report-page").apply { isDaemon = true }
            })
        Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: Exception) { break }
                clients.add(client)
                try { workers.execute { try { serve(client, token, title, contact, text) } finally { clients.remove(client) } } }
                catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); runCatching { client.close() } }
            }
        }.apply { isDaemon = true; name = "report-server"; start() }
        return Handle("http://$ip:${server.localPort}/$token", server, clients, workers)
    }

    private fun bind(): ServerSocket? {
        for (port in 8080..8090) {
            val s = runCatching { ServerSocket(port) }.getOrNull()
            if (s != null) return s
        }
        return runCatching { ServerSocket(0) }.getOrNull()
    }

    private fun randomToken(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = java.security.SecureRandom()
        return (1..12).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    /** This device's address on the home network (Wi-Fi or cable), or null. */
    private fun localAddress(): String? = runCatching {
        val found = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { nif.name to it.hostAddress } }
        (found.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") } ?: found.firstOrNull())?.second
    }.getOrNull()

    private fun serve(client: Socket, token: String, title: String, contact: Contact?, text: () -> String) {
        runCatching {
            client.use { c ->
                c.soTimeout = 5_000
                val input = c.getInputStream()
                val header = StringBuilder()
                while (header.length < 8192 && !header.endsWith("\r\n\r\n")) {
                    val byte = input.read()
                    if (byte < 0) return@use
                    header.append(byte.toChar())
                }
                if (!header.endsWith("\r\n\r\n")) return@use
                val request = header.toString().substringBefore("\r\n")
                val path = request.split(' ').getOrNull(1).orEmpty().substringBefore('?')
                val out = c.getOutputStream()
                if (!request.startsWith("GET ") || path.trim('/') != token) {
                    val body = "Not found".toByteArray()
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                } else {
                    val body = page(title, text(), contact).toByteArray(Charsets.UTF_8)
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    out.write(body)
                }
                out.flush()
            }
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    internal fun page(title: String, text: String, contact: Contact? = null): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<style>
body{font-family:-apple-system,Roboto,sans-serif;background:#111;color:#eee;margin:0;padding:16px}
h1{font-size:20px;margin:0 0 6px}
p{color:#aaa;font-size:14px;margin:6px 0 12px}
button{font-size:18px;padding:14px 22px;border:0;border-radius:10px;background:#fff;color:#111;font-weight:600;margin-right:8px}
a{color:#8cf;font-size:15px}
textarea{width:100%;box-sizing:border-box;margin-top:14px;height:60vh;background:#1b1b1f;color:#ddd;border:1px solid #333;border-radius:8px;font-family:monospace;font-size:12px;padding:8px}
#msg{color:#7f7;font-size:15px;margin-top:10px;min-height:20px}
</style></head><body>
<h1>${esc(title)}</h1>
<p>Review the report below. Nothing is sent automatically.</p>
${if (contact != null) """<p>Send to ${esc(contact.email)}. Choose your email app in the share sheet. If file sharing is unavailable, download the report and attach it to the email.</p>
<button id="email" data-to="${esc(contact.email)}" data-subject="${esc(contact.subject)}" onclick="emailReport()">Email to developer</button>
<button onclick="downloadReport()">Download</button>""" else ""}
<button onclick="copyAll()">Copy report</button> <a href="">Reload</a>
<div id="msg"></div>
<textarea id="r" readonly>${esc(text)}</textarea>
<script>
function copyAll(){
  var t=document.getElementById('r');t.focus();t.select();t.setSelectionRange(0,t.value.length);
  var ok=false;try{ok=document.execCommand('copy')}catch(e){}
  document.getElementById('msg').textContent=ok?'Copied. Now paste it into your message.':'Press and hold the text, choose Select all, then Copy.';
}
function reportFile(){return new File([document.getElementById('r').value], 'Nuvio-ReSeq-report.txt', {type:'text/plain;charset=utf-8'});}
function downloadReport(){
  var u=URL.createObjectURL(reportFile()),a=document.createElement('a');a.href=u;a.download='Nuvio-ReSeq-report.txt';a.click();setTimeout(function(){URL.revokeObjectURL(u)},30000);
}
async function emailReport(){
  var b=document.getElementById('email'),f=reportFile();
  if(navigator.canShare && navigator.canShare({files:[f]})){
    try{await navigator.share({files:[f],title:b.dataset.subject,text:'Please send to '+b.dataset.to});return;}
    catch(e){if(e.name==='AbortError')return;}
  }
  document.getElementById('msg').textContent='Download the report, then attach Nuvio-ReSeq-report.txt to this email. Your browser cannot attach files from a local HTTP page.';
  window.location.href='mailto:'+encodeURIComponent(b.dataset.to)+'?subject='+encodeURIComponent(b.dataset.subject)+'&body='+encodeURIComponent('Please attach the downloaded Nuvio-ReSeq-report.txt and describe the problem.');
}
var r=document.getElementById('r');r.scrollTop=r.scrollHeight;
</script></body></html>"""
}
