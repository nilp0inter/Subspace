package dev.nilp0inter.subspace.telecom

import android.content.Intent
import android.os.Build
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import dev.nilp0inter.subspace.service.PttForegroundService

class SubspaceConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: android.telecom.PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val serviceIntent = Intent(this, PttForegroundService::class.java).apply {
            action = PttForegroundService.ACTION_START_MONITORING
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)

        val connection = SubspaceConnection()
        if (!TelecomCarPttCoordinator.attachConnection(connection)) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.BUSY))
        }
        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: android.telecom.PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection? = super.onCreateIncomingConnection(connectionManagerPhoneAccount, request)

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: android.telecom.PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        TelecomCarPttCoordinator.forceAbort()
    }
}