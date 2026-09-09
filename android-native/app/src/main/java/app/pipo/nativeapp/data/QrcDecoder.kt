package app.pipo.nativeapp.data

/**
 * QQ QRC uses a historical DES variant whose key and lookup schedule differs from
 * the platform DESede provider. This implementation follows the wire format directly
 * and keeps the decoded bytes bounded by [OnlineLyricSupport.inflate].
 */
internal object QrcDecoder {
    private val qrcKey = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)

    fun decode(encryptedHex: String): String {
        val encrypted = decodeHex(encryptedHex)
        require(encrypted.isNotEmpty() && encrypted.size % BLOCK_SIZE == 0) { "Invalid QRC payload" }

        val firstKey = qrcKey.copyOfRange(0, 8)
        val secondKey = qrcKey.copyOfRange(8, 16)
        val thirdKey = qrcKey.copyOfRange(16, 24)
        val stages = arrayOf(
            roundKeys(thirdKey, decrypt = true),
            roundKeys(secondKey, decrypt = false),
            roundKeys(firstKey, decrypt = true),
        )
        val compressed = ByteArray(encrypted.size)
        for (offset in encrypted.indices step BLOCK_SIZE) {
            var block = encrypted.copyOfRange(offset, offset + BLOCK_SIZE)
            stages.forEach { keys -> block = cryptBlock(block, keys) }
            block.copyInto(compressed, destinationOffset = offset)
        }
        return OnlineLyricSupport.inflate(compressed)
    }

    private fun decodeHex(value: String): ByteArray {
        val trimmed = value.trim()
        require(trimmed.length % 2 == 0) { "Invalid QRC hex length" }
        return ByteArray(trimmed.length / 2) { index ->
            val high = Character.digit(trimmed[index * 2], 16)
            val low = Character.digit(trimmed[index * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "Invalid QRC hex" }
            ((high shl 4) or low).toByte()
        }
    }

    private fun cryptBlock(block: ByteArray, keys: LongArray): ByteArray {
        var left = permuteInput(block, INITIAL_PERMUTATION, 0)
        var right = permuteInput(block, INITIAL_PERMUTATION, 32)
        keys.forEach { key ->
            val nextRight = left xor feistel(right, key)
            left = right
            right = nextRight
        }
        return permuteOutput(right, left)
    }

    private fun roundKeys(key: ByteArray, decrypt: Boolean): LongArray {
        var c = permuteKey(key, PC1_C)
        var d = permuteKey(key, PC1_D)
        val keys = LongArray(16)
        ROUND_SHIFTS.forEachIndexed { round, shift ->
            c = rotate28(c, shift)
            d = rotate28(d, shift)
            var roundKey = 0L
            KEY_COMPRESSION.forEach { position ->
                val bit = if (position < 28) bit28(c, position) else bit28OrZero(d, position - 27)
                roundKey = (roundKey shl 1) or bit.toLong()
            }
            keys[round] = roundKey
        }
        return if (decrypt) keys.reversedArray() else keys
    }

    private fun feistel(right: Int, roundKey: Long): Int {
        var expanded = 0L
        EXPANSION.forEach { position ->
            expanded = (expanded shl 1) or bit32(right, position).toLong()
        }
        val mixed = expanded xor roundKey
        var substituted = 0
        S_BOXES.forEachIndexed { boxIndex, box ->
            val sixBits = ((mixed ushr (42 - boxIndex * 6)) and 0x3f).toInt()
            val row = ((sixBits and 0x20) ushr 4) or (sixBits and 1)
            val column = (sixBits ushr 1) and 0x0f
            substituted = (substituted shl 4) or box[row * 16 + column]
        }
        var result = 0
        PERMUTATION.forEach { position ->
            result = (result shl 1) or bit32(substituted, position)
        }
        return result
    }

    private fun permuteInput(block: ByteArray, table: IntArray, start: Int): Int {
        var result = 0
        for (index in start until start + 32) {
            result = (result shl 1) or sourceBit(block, table[index])
        }
        return result
    }

    private fun permuteOutput(right: Int, left: Int): ByteArray {
        val output = ByteArray(BLOCK_SIZE)
        FINAL_PERMUTATION.forEachIndexed { outputBit, sourcePosition ->
            val source = if (sourcePosition < 32) right else left
            val bit = bit32(source, sourcePosition % 32)
            if (bit == 0) return@forEachIndexed
            val wordOffset = (outputBit / 32) * 4
            val byteIndex = wordOffset + 3 - (outputBit % 32) / 8
            output[byteIndex] = (output[byteIndex].toInt() or (1 shl (7 - outputBit % 8))).toByte()
        }
        return output
    }

    private fun permuteKey(key: ByteArray, table: IntArray): Int {
        var result = 0
        table.forEach { position -> result = (result shl 1) or sourceBit(key, position) }
        return result
    }

    private fun sourceBit(bytes: ByteArray, bit: Int): Int {
        val byteIndex = (bit / 32) * 4 + 3 - (bit % 32) / 8
        return (bytes[byteIndex].toInt() ushr (7 - bit % 8)) and 1
    }

    private fun bit32(value: Int, position: Int): Int = (value ushr (31 - position)) and 1

    private fun bit28(value: Int, position: Int): Int = (value ushr (27 - position)) and 1

    private fun bit28OrZero(value: Int, position: Int): Int =
        if (position in 0..27) bit28(value, position) else 0

    private fun rotate28(value: Int, shift: Int): Int =
        ((value shl shift) or (value ushr (28 - shift))) and 0x0fffffff

    private const val BLOCK_SIZE = 8
    private val ROUND_SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val INITIAL_PERMUTATION = intArrayOf(
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
        61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
        56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2,
        60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6,
    )
    private val FINAL_PERMUTATION = intArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24,
    )
    private val EXPANSION = intArrayOf(
        31, 0, 1, 2, 3, 4, 3, 4, 5, 6, 7, 8, 7, 8, 9, 10, 11, 12,
        11, 12, 13, 14, 15, 16, 15, 16, 17, 18, 19, 20, 19, 20, 21, 22,
        23, 24, 23, 24, 25, 26, 27, 28, 27, 28, 29, 30, 31, 0,
    )
    private val PERMUTATION = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24,
    )
    private val PC1_C = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
        58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
    )
    private val PC1_D = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5,
        60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
    )
    private val KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
        25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
        50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )
    private val S_BOXES = arrayOf(
        intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8, 4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13),
        intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5, 0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9),
        intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1, 13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12),
        intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9, 10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14),
        intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6, 4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3),
        intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8, 9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13),
        intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6, 1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12),
        intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2, 7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11),
    )
}
