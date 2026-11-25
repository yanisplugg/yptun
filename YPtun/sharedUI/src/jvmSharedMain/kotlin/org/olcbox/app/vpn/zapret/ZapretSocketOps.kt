package org.olcbox.app.vpn.zapret

import java.net.Socket

internal expect fun setZapretSocketTtl(socket: Socket, ttl: Int): Boolean
