package com.example.iot

import android.net.Network
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

internal class NetworkBoundSslSocketFactory(
    private val wifiNetwork: Network,
    private val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
) : SSLSocketFactory() {
    private val wifiSocketFactory: SocketFactory = wifiNetwork.socketFactory

    override fun getDefaultCipherSuites(): Array<String> = tlsFactory.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = tlsFactory.supportedCipherSuites

    override fun createSocket(host: String, port: Int): Socket = wrap(
        wifiSocketFactory.createSocket(host, port), host, port, true
    )

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = wrap(
        wifiSocketFactory.createSocket(host, port, localHost, localPort), host, port, true
    )

    override fun createSocket(host: InetAddress, port: Int): Socket = wrap(
        wifiSocketFactory.createSocket(host, port), host.hostName, port, true
    )

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = wrap(
        wifiSocketFactory.createSocket(address, port, localAddress, localPort), address.hostName, port, true
    )

    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        tlsFactory.createSocket(socket, host, port, autoClose).also {
            (it as? javax.net.ssl.SSLSocket)?.startHandshake()
        }

    private fun wrap(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        createSocket(socket, host, port, autoClose)
}
