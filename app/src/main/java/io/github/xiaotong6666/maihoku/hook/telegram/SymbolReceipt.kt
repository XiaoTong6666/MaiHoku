package io.github.xiaotong6666.maihoku.hook.telegram

internal data class FieldReceipt(
    val id: String,
    val declaringClassName: String,
    val fieldName: String,
    val typeName: String,
    val evidence: Set<String>,
)
