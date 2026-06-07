package dev.botoved.rover.service

import android.content.Context
import android.util.Log
import network.reticulum.crypto.defaultCryptoProvider
import network.reticulum.identity.Identity
import java.io.File

object RoverIdentity {

    private const val TAG = "Rover"
    private const val IDENTITY_FILE = "rover_identity"

    fun getOrCreate(context: Context): Identity {
        val file = File(context.filesDir, IDENTITY_FILE)
        val crypto = defaultCryptoProvider()
        return if (file.exists()) {
            Log.i(TAG, "Loading existing identity from ${file.path}")
            Identity.fromFile(file.path, crypto)
                ?: run {
                    Log.w(TAG, "Failed to load identity, creating new one")
                    createAndSave(file, crypto)
                }
        } else {
            Log.i(TAG, "Creating new identity")
            createAndSave(file, crypto)
        }
    }

    private fun createAndSave(file: File, crypto: network.reticulum.crypto.CryptoProvider): Identity {
        val identity = Identity.create(crypto)
        identity.toFile(file.path)
        Log.i(TAG, "Identity created: ${identity.hexHash}")
        return identity
    }
}
