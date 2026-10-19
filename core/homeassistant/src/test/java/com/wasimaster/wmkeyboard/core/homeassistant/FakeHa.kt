package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.serialization.json.jsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList

/** A tiny Home Assistant: the server side of the socket conversation, scripted for the tests. */
internal class FakeHa(private val token: String = "GOOD") : WebSocketListener() {
    val received = CopyOnWriteArrayList<String>()
    @Volatile var socket: WebSocket? = null

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
        webSocket.send("""{"type":"auth_required","ha_version":"2026.10.1"}""")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        received += text
        val o = HaJson.parseToJsonElement(text).jsonObject
        val id = o.int("id")
        when (o.str("type")) {
            "auth" -> if (o.str("access_token") == token) {
                webSocket.send("""{"type":"auth_ok","ha_version":"2026.10.1"}""")
            } else {
                webSocket.send("""{"type":"auth_invalid","message":"Invalid access token"}""")
                webSocket.close(1000, "")
            }
            "subscribe_entities" -> {
                webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                webSocket.send(
                    """{"id":$id,"type":"event","event":{"a":{
                        "light.kitchen":{"s":"off","a":{"friendly_name":"Kitchen","brightness":null},"lc":1.0},
                        "sensor.temp":{"s":"21.5","a":{"unit_of_measurement":"°C"},"lc":1.0}}}}""",
                )
            }
            "call_service" -> {
                webSocket.send("""{"id":$id,"type":"result","success":true,"result":{"context":{"id":"ctx"}}}""")
                // The house reacts: the light comes on at half brightness.
                webSocket.send("""{"id":${subscription()},"type":"event","event":{"c":{"light.kitchen":{"+":{"s":"on","lc":2.0,"a":{"brightness":128}}}}}}""")
            }
            "config/area_registry/list" -> webSocket.send("""{"id":$id,"type":"result","success":true,"result":[{"area_id":"k","name":"Kitchen"}]}""")
            "config/floor_registry/list", "config/device_registry/list" ->
                webSocket.send("""{"id":$id,"type":"result","success":true,"result":[]}""")
            "config/entity_registry/list" ->
                webSocket.send("""{"id":$id,"type":"result","success":true,"result":[{"entity_id":"light.kitchen","area_id":"k"}]}""")
            "ping" -> webSocket.send("""{"id":$id,"type":"pong"}""")
            "bad" -> webSocket.send("""{"id":$id,"type":"result","success":false,"error":{"code":"unknown_command","message":"nope"}}""")
            else -> webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
        }
    }

    private fun subscription(): Int =
        received.map { HaJson.parseToJsonElement(it).jsonObject }.first { it.str("type") == "subscribe_entities" }.int("id")!!
}
