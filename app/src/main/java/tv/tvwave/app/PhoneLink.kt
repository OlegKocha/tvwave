package tv.tvwave.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Base64
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/** One short-lived LAN-only inbox. It never fetches or persists submitted URLs. */
class PhoneLink(private val address:InetAddress,private val receive:(String)->Unit):Closeable {
    private val server=ServerSocket(0,4,address).apply{soTimeout=1000}
    private val token=Base64.encodeToString(ByteArray(24).also{SecureRandom().nextBytes(it)},Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    val url="http://${address.hostAddress}:${server.localPort}/${token}"
    private val authority="${address.hostAddress}:${server.localPort}"
    private val deadline=SystemClock.elapsedRealtime()+300000
    @Volatile private var closed=false
    @Volatile private var client:Socket?=null
    val remainingSeconds get()=((deadline-SystemClock.elapsedRealtime())/1000).coerceAtLeast(0)
    fun start(){thread(name="phone-link",isDaemon=true){
        try{while(!closed&&remainingSeconds>0){
            try{server.accept().use{socket->client=socket;socket.soTimeout=3000;handle(socket);client=null}}
            catch(_:Exception){if(closed)break}
        }}finally{close()}
    }}
    override fun close(){closed=true;runCatching{client?.close()};runCatching{server.close()}}
    private fun handle(socket:Socket){
        if(!socket.inetAddress.isSiteLocalAddress&&!socket.inetAddress.isLoopbackAddress)return
        val input=socket.getInputStream();var total=0
        fun line():String {val b=java.io.ByteArrayOutputStream();while(true){val c=input.read();if(c<0)throw java.io.EOFException();if(++total>8192)throw java.io.IOException();if(c==10)break;if(c!=13)b.write(c)};return b.toString("US-ASCII")}
        val request=line().split(' ');if(request.size!=3)return
        val headers=mutableMapOf<String,String>();while(true){val l=line();if(l.isEmpty())break;val i=l.indexOf(':');if(i<=0)return;val k=l.substring(0,i).lowercase();if(headers.containsKey(k))return;headers[k]=l.substring(i+1).trim()}
        // A regular form POST under no-referrer sends Origin:null and fails our own check.
        // same-origin preserves the sender for this form and hides referrers from other sites.
        fun send(code:Int,body:String){val bytes=body.toByteArray();val out=socket.getOutputStream();out.write(("HTTP/1.1 ${code} Response\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: same-origin\r\nX-Frame-Options: DENY\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'\r\n\r\n").toByteArray());out.write(bytes);out.flush()}
        val length=headers["content-length"]?.toIntOrNull()
        val posted=if(request[0]=="POST"&&length!=null&&length in 1..24576&&!headers.containsKey("transfer-encoding")){
            ByteArray(length).also{bytes->var offset=0;while(offset<length){val n=input.read(bytes,offset,length-offset);if(n<0)return;offset+=n}}
        }else null
        if(closed||remainingSeconds==0L){send(410,page(tr("session_ended_open_a_new_qr_code_on_your_tv")));return}
        if(request[1]!="/${token}"||headers["host"]!=authority){send(404,page(tr("this_link_is_invalid")));return}
        if(headers["origin"]!=null&&headers["origin"]!="http://${authority}"){send(403,page(tr("open_the_qr_link_again")));return}
        if(request[0]=="GET"){send(200,page(tr("your_phone_and_tv_must_be_on_the_same_home_network"),true));return}
        if(request[0]!="POST"||headers.containsKey("transfer-encoding")||length==null||length !in 1..24576||headers["content-type"]?.substringBefore(';')!="application/x-www-form-urlencoded"){send(400,page(tr("could_not_receive_the_url"),true));return}
        val value=runCatching{(posted?:throw IllegalArgumentException()).toString(Charsets.UTF_8).split('&').single{it.startsWith("url=")}.substringAfter('=').let{URLDecoder.decode(it,"UTF-8")}.let{SourceIO.url(it)}}.getOrNull()
        if(value==null){send(400,page(tr("enter_a_full_http_or_https_url_without_spaces_then_try_again"),true));return}
        send(200,page(tr("url_sent_check_and_save_it_on_your_tv_you_can_close_this_tab")))
        close();receive(value)
    }
    private fun html(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
    private fun page(message:String,form:Boolean=false)="""<!doctype html><html lang="${html(L10n.language)}"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>TV Wave</title><style>${T.phoneStyle}</style><h1>${html(tr("phone_title"))}</h1><p>${html(message)}</p>${if(form)"""<form method="post"><label for="url">${html(tr("phone_url_label"))}</label><input id="url" name="url" type="url" maxlength="8192" required placeholder="https://…" autocomplete="off" autofocus><button>${html(tr("phone_send"))}</button></form><p>${html(tr("phone_privacy"))}</p>"""else""}</html>"""
    companion object {
        fun lanAddress(context:Context):InetAddress {
            val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network=manager.activeNetwork?:throw UserError(tr("connect_the_tv_to_your_home_wi_fi_or_ethernet_network"))
            val caps=manager.getNetworkCapabilities(network)
            if(caps==null||(!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)))throw UserError(tr("qr_transfer_needs_a_home_wi_fi_or_ethernet_network"))
            return manager.getLinkProperties(network)?.linkAddresses?.map{it.address}?.firstOrNull{it is Inet4Address&&it.isSiteLocalAddress&&!it.isLoopbackAddress}
                ?:throw UserError(tr("no_local_ipv4_address_found_check_your_home_network_connection"))
        }
    }
}
