package io.github.xiaotong6666.maihoku.mimessage.host.google

import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import io.github.xiaotong6666.maihoku.hook.HookRuntime
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.ByteArrayOutputStream
import java.io.Serializable
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import io.github.xiaotong6666.maihoku.MaiHokuLog as Log

/** Reuses Google Messages' manifest SMS_DELIVER processor, resolving its R8 method with DexKit. */
internal class GoogleMessagesSmsDeliverAdapter private constructor(
    private val receiverConstructor: Constructor<*>,
    private val injectReceiver: Method,
    private val processSmsDelivery: Method,
) {
    fun receive(
        context: Context,
        messageId: String,
        address: String,
        body: String,
        receivedAt: Long,
        subscriptionId: Int,
        serviceCenterMarker: String,
    ) {
        val pdus = createDeliverPdus(
            address = address,
            body = body,
            receivedAt = receivedAt,
            serviceCenterMarker = serviceCenterMarker,
        )
        val intent = Intent(android.provider.Telephony.Sms.Intents.SMS_DELIVER_ACTION)
            .putExtra("pdus", pdus as Serializable)
            .putExtra("format", FORMAT_3GPP)
            .putExtra(EXTRA_MESSAGE_ID, messageId.toLong())
            .putExtra(EXTRA_SUBSCRIPTION, subscriptionId)
            .putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subscriptionId)

        val receiver = receiverConstructor.newInstance()
        // SmsDeliverReceiver's nearest superclass exposes its Dagger injection entry point.
        // Invoke it before the DexKit-resolved processing method. Do not call onReceive() on this
        // manually-created instance: Google's outer receiver wrapper owns a framework PendingResult
        // for goAsync(), while the host inbox bridge already keeps its own broadcast alive here.
        injectReceiver.invoke(receiver, context)
        val processing = processSmsDelivery.invoke(receiver, context, intent) as? Future<*>
            ?: throw IllegalStateException("Google Messages SMS_DELIVER processor did not return a Future")

        // Google's native receiver returns a ListenableFuture for SmsReceivePipeline. Waiting
        // for the Telephony row alone is too early: the row can exist before Bugle has committed
        // the conversation/message pipeline and scheduled its normal notification.
        awaitGoogleSmsReceivePipeline(processing)
    }

    private fun createDeliverPdus(
        address: String,
        body: String,
        receivedAt: Long,
        serviceCenterMarker: String,
    ): Array<ByteArray> {
        val originDigits = address.filter(Char::isDigit)
        require(originDigits.isNotEmpty() && originDigits.length <= 20) {
            "Google Messages native SMS path requires a numeric sender address"
        }
        val serviceCenterDigits = serviceCenterMarker.filter(Char::isDigit)
        require(serviceCenterDigits.isNotEmpty() && serviceCenterDigits.length <= 20) {
            "Invalid synthetic SMSC marker"
        }

        val codePoints = body.codePoints().toArray()
        val chunks = if (body.length <= SINGLE_PART_UCS2_LIMIT) {
            listOf(body)
        } else {
            val result = ArrayList<String>()
            val current = StringBuilder()
            var units = 0
            for (codePoint in codePoints) {
                val chars = Character.charCount(codePoint)
                if (units + chars > MULTIPART_UCS2_LIMIT) {
                    result += current.toString()
                    current.setLength(0)
                    units = 0
                }
                current.appendCodePoint(codePoint)
                units += chars
            }
            if (current.isNotEmpty()) result += current.toString()
            result
        }
        require(chunks.size <= MAX_SMS_PARTS) { "Google Messages native SMS path exceeds multipart limit" }

        val timestamp = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = receivedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        }
        val reference = (messageReference(serviceCenterMarker) and 0xff).toByte()
        return chunks.mapIndexed { index, chunk ->
            val userData = chunk.toByteArray(Charsets.UTF_16BE)
            val userDataHeader = if (chunks.size > 1) {
                byteArrayOf(
                    0x05,
                    0x00,
                    0x03,
                    reference,
                    chunks.size.toByte(),
                    (index + 1).toByte(),
                )
            } else {
                ByteArray(0)
            }

            ByteArrayOutputStream().apply {
                val smscAddress = semiOctets(serviceCenterDigits)
                write(1 + smscAddress.size)
                write(INTERNATIONAL_NUMBER_TYPE)
                write(smscAddress)

                // SMS-DELIVER TPDU. UDHI is set only when concatenation metadata is present.
                write(if (chunks.size > 1) USER_DATA_HEADER_INDICATOR else 0x00)
                write(originDigits.length)
                write(if (address.trim().startsWith('+')) INTERNATIONAL_NUMBER_TYPE else NATIONAL_NUMBER_TYPE)
                write(semiOctets(originDigits))
                write(PROTOCOL_IDENTIFIER)
                write(DATA_CODING_UCS2)
                write(swappedBcd(timestamp.get(Calendar.YEAR) % 100))
                write(swappedBcd(timestamp.get(Calendar.MONTH) + 1))
                write(swappedBcd(timestamp.get(Calendar.DAY_OF_MONTH)))
                write(swappedBcd(timestamp.get(Calendar.HOUR_OF_DAY)))
                write(swappedBcd(timestamp.get(Calendar.MINUTE)))
                write(swappedBcd(timestamp.get(Calendar.SECOND)))
                write(0x00) // UTC timezone offset
                write(userDataHeader.size + userData.size)
                write(userDataHeader)
                write(userData)
            }.toByteArray()
        }.toTypedArray()
    }

    private fun semiOctets(digits: String): ByteArray {
        val result = ByteArray((digits.length + 1) / 2)
        var source = 0
        for (target in result.indices) {
            val low = digits[source++].digitToInt()
            val high = if (source < digits.length) digits[source++].digitToInt() else 0x0f
            result[target] = (low or (high shl 4)).toByte()
        }
        return result
    }

    private fun swappedBcd(value: Int): Int = ((value % 10) shl 4) or (value / 10)

    private fun messageReference(marker: String): Int = marker.filter(Char::isDigit).takeLast(2).toIntOrNull() ?: 1

    companion object {
        private const val TAG = "GoogleMsg"
        private const val FORMAT_3GPP = "3gpp"
        private const val EXTRA_MESSAGE_ID = "messageId"
        private const val EXTRA_SUBSCRIPTION = "subscription"
        private const val RECEIVE_FINGERPRINT = "Received SMS. Processing message."
        private const val RECEIVE_NULL_PDU_FINGERPRINT = "Received SMS with null messages. Disgarding SMS."
        private const val INTERNATIONAL_NUMBER_TYPE = 0x91
        private const val NATIONAL_NUMBER_TYPE = 0x81
        private const val PROTOCOL_IDENTIFIER = 0x00
        private const val DATA_CODING_UCS2 = 0x08
        private const val USER_DATA_HEADER_INDICATOR = 0x40
        private const val SINGLE_PART_UCS2_LIMIT = 70
        private const val MULTIPART_UCS2_LIMIT = 67
        private const val MAX_SMS_PARTS = 255

        fun resolve(runtime: HookRuntime): GoogleMessagesSmsDeliverAdapter? = runCatching {
            runtime.dexKit.useBridge { bridge ->
                val candidates = bridge.findMethod(
                    FindMethod.create().matcher(
                        MethodMatcher.create().apply {
                            usingStrings(RECEIVE_FINGERPRINT, RECEIVE_NULL_PDU_FINGERPRINT)
                        },
                    ),
                ).mapNotNull { data ->
                    runCatching { data.getMethodInstance(runtime.classLoader) }.getOrNull()
                }.filter { method ->
                    method.parameterTypes.contentEquals(arrayOf(Context::class.java, Intent::class.java)) &&
                        Future::class.java.isAssignableFrom(method.returnType) &&
                        !Modifier.isStatic(method.modifiers) &&
                        BroadcastReceiverType.isReceiver(method.declaringClass)
                }.distinct()

                if (candidates.size != 1) {
                    Log.e(TAG, "DexKit expected one Google SMS delivery processor, found ${candidates.size}")
                    return@useBridge null
                }

                val processMethod = candidates.single().apply { isAccessible = true }
                val receiverClass = processMethod.declaringClass
                val constructor = receiverClass.getDeclaredConstructor().apply { isAccessible = true }
                val injectionMethod = generateSequence(receiverClass.superclass as Class<*>?) { it.superclass }
                    .map { clazz ->
                        clazz.declaredMethods.filter { method ->
                            method.returnType == Void.TYPE &&
                                method.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
                                !Modifier.isStatic(method.modifiers)
                        }
                    }
                    .firstOrNull { it.isNotEmpty() }
                    ?.singleOrNull()
                    ?.apply { isAccessible = true }
                    ?: run {
                        Log.e(TAG, "Google SMS receiver dependency injection entry point is missing or ambiguous")
                        return@useBridge null
                    }

                Log.i(
                    TAG,
                    "Resolved Google SMS_DELIVER processor ${receiverClass.name}.${processMethod.name} " +
                        "and injection ${injectionMethod.declaringClass.name}.${injectionMethod.name} " +
                        "via DexKit/runtime hierarchy",
                )
                GoogleMessagesSmsDeliverAdapter(constructor, injectionMethod, processMethod)
            }
        }.onFailure {
            Log.e(TAG, "DexKit lookup for Google SMS_DELIVER processor failed", it)
        }.getOrNull()
    }

    private object BroadcastReceiverType {
        fun isReceiver(clazz: Class<*>): Boolean = android.content.BroadcastReceiver::class.java.isAssignableFrom(clazz)
    }
}

/** Waits for Google's native SmsReceivePipeline and preserves its underlying failure. */
internal fun awaitGoogleSmsReceivePipeline(
    future: Future<*>,
    timeoutSeconds: Long = 60L,
) {
    try {
        future.get(timeoutSeconds, TimeUnit.SECONDS)
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    } catch (error: TimeoutException) {
        throw IllegalStateException(
            "Google Messages SmsReceivePipeline did not complete within ${timeoutSeconds}s",
            error,
        )
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException("Interrupted while waiting for Google Messages SmsReceivePipeline", error)
    }
}
