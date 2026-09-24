package com.gekkotron.oikos.transport

import com.hivemq.client.mqtt.exceptions.ConnectionFailedException
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a direct connection attempt failed; drives the onboarding messages (spec §5, §6). */
sealed interface DirectFailure {
    data object Unreachable : DirectFailure
    data object Rejected : DirectFailure
    data class Tls(val message: String) : DirectFailure
    data class Other(val message: String) : DirectFailure
}

fun classifyFailure(error: Throwable): DirectFailure {
    val chain = generateSequence(error) { it.cause }.take(10).toList()
    chain.firstNotNullOfOrNull { it as? Mqtt3ConnAckException }?.let { e ->
        return when (e.mqttMessage.returnCode) {
            Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD, Mqtt3ConnAckReturnCode.NOT_AUTHORIZED -> DirectFailure.Rejected
            else -> DirectFailure.Other("broker refused the connection: ${e.mqttMessage.returnCode}")
        }
    }
    chain.firstOrNull { it is SSLException }?.let { return DirectFailure.Tls(it.message ?: "TLS error") }
    val unreachable = chain.any {
        it is ConnectionFailedException || it is ConnectException || it is UnknownHostException ||
            it is NoRouteToHostException || it is SocketTimeoutException
    }
    return if (unreachable) DirectFailure.Unreachable else DirectFailure.Other(error.message ?: error.javaClass.simpleName)
}
