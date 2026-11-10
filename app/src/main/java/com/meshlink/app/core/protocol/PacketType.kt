package com.meshlink.app.core.protocol
enum class PacketType(val code: Byte) {
    NODE_BEACON(1), HELLO(2), CAPABILITIES(3), ROUTE_SUMMARY(4), TEXT(5), VOICE_META(6),
    VOICE_CHUNK(7), SOS(8), ALERT(9), LOCATION(10), ACK(11), RECEIPT(12), CANCEL(13), SOS_REVIEW(14)
}
