package dev.botoved.rover.rover.protocol

import org.msgpack.core.MessagePack

object MessageTypes {
    const val STATUS = 2
    const val PUSH = 3
    const val CONFIG = 4
    const val CMD = 5
    const val PING = 6
    const val REQ = 8
    const val REGISTER = 9
}

object RoverCodec {

    fun encodeRegister(uid: String): Map<Int, Any> {
        return mapOf(0 to MessageTypes.REGISTER, 1 to uid)
    }

    fun decodeTp(fields: Map<*, *>?): Int? {
        return (fields?.get(0) as? Int)
    }

    fun encode(fields: Map<Int, Any>): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(fields.size)
        fields.forEach { (k, v) ->
            packer.packInt(k)
            when (v) {
                is Int -> packer.packInt(v)
                is Boolean -> packer.packBoolean(v)
                is String -> packer.packString(v)
                is Float -> packer.packFloat(v)
                is Double -> packer.packDouble(v)
                is ByteArray -> packer.packBinaryHeader(v.size).also { packer.writePayload(v) }
                else -> packer.packString(v.toString())
            }
        }
        return packer.toByteArray()
    }

    fun decode(bytes: ByteArray): Map<Int, Any>? {
        return try {
            val unpacker = MessagePack.newDefaultUnpacker(bytes)
            val size = unpacker.unpackMapHeader()
            val result = mutableMapOf<Int, Any>()
            repeat(size) {
                val key = unpacker.unpackInt()
                val value: Any = when (unpacker.nextFormat.valueType.name) {
                    "INTEGER" -> unpacker.unpackInt()
                    "BOOLEAN" -> unpacker.unpackBoolean()
                    "FLOAT" -> unpacker.unpackFloat()
                    "STRING" -> unpacker.unpackString()
                    "BINARY" -> {
                        val len = unpacker.unpackBinaryHeader()
                        unpacker.readPayload(len)
                    }
                    else -> unpacker.unpackString()
                }
                result[key] = value
            }
            result
        } catch (e: Exception) {
            null
        }
    }
}
