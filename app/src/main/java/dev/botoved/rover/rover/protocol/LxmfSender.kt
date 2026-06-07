package dev.botoved.rover.rover.protocol

import android.util.Log
import network.reticulum.destination.Destination
import network.reticulum.identity.Identity
import network.reticulum.lxmf.LXMRouter

class LxmfSender(
    private val router: LXMRouter,
    private val sourceDestination: Destination,
    private val identity: Identity
) {
    private val TAG = "Rover"

    suspend fun sendRegister(serverDestHash: String) {
        Log.i(TAG, "Sending REGISTER to $serverDestHash")
        // TODO Task 4: создать LXMessage → router.handleOutbound
        // API изучено:
        //   LXMessage.create(destination, source, title, content, fields, deliveryMethod)
        //   router.handleOutbound(message) — suspend fun
        //
        // Проблема: нужно создать Destination для сервера.
        // Есть только hex-хэш (32 hex = 16 байт).
        // Варианты:
        //   1. Destination.create(Identity, Direction, Type, appName, aspects)
        //      — нужен Identity сервера (не знаем pub key)
        //   2. Identity.recall(byte[]) — ищет по identity hash,
        //      но destHash != identityHash
        //   3. LXMessage приватный конструктор принимает
        //      destinationHash: ByteArray отдельно от Destination —
        //      но он private, доступа нет
        //   4. Возможно через Transport.lookupDestination(hash)
        //      или AnnounceTable — требует announce
        //
        // Ждём решения в Task 4
    }
}
