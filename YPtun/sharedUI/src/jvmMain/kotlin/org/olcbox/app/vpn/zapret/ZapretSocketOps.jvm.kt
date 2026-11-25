package org.olcbox.app.vpn.zapret

import java.net.Socket

internal actual fun setZapretSocketTtl(socket: Socket, ttl: Int): Boolean = false
