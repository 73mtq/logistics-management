package com.logisticsmanagement.app.network

import com.logisticsmanagement.app.data.CredentialStore
import com.logisticsmanagement.app.data.LogisticsRepository
import com.logisticsmanagement.app.data.SettingsStore
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.Report
import com.logisticsmanagement.app.model.Shipment
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class AiAnswer(val text: String, val shipmentIds: List<String>, val createdAt: Long)

class AiService(
    private val repository: LogisticsRepository,
    private val settings: SettingsStore,
    private val credentials: CredentialStore,
) {
    private val http = createHttpClient()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun ask(question: String): AiAnswer {
        if (question.isBlank()) throw AppFailure("请填写需要询问的问题", FailureKind.CONFIGURATION)
        val configuration = configuration(requireEnabled = true)
        val allShipments = repository.getShipments()
        val shipments = allShipments.filter { !it.archived }
        val privateValues = privateValues(configuration)
        val safeQuestion = redact(question.trim(), allShipments, privateValues)
        val snapshot = snapshot(shipments, allShipments, privateValues)
        val text = complete(configuration, listOf(
            ChatMessage("system", SYSTEM_PROMPT),
            ChatMessage("user", "本地包裹摘要：\n${json.encodeToString(snapshot)}\n\n问题：$safeQuestion"),
        ))
        val answer = answer(text, shipments)
        repository.saveReport(Report(UUID.randomUUID().toString(), "问题：$safeQuestion\n\n回答：${answer.text}", answer.createdAt, "question"))
        return answer
    }

    suspend fun generateDailyReport(): AiAnswer {
        val configuration = configuration(requireEnabled = true)
        val allShipments = repository.getShipments()
        val shipments = allShipments.filter { !it.archived }
        val snapshot = snapshot(shipments, allShipments, privateValues(configuration))
        val text = complete(configuration, listOf(
            ChatMessage("system", SYSTEM_PROMPT),
            ChatMessage("user", "请根据以下本地包裹摘要生成${LocalDate.now()}的物流日报，列出各状态数量、待取件包裹、物流异常包裹和需要关注的预计送达时间。时间不足以判断延误时说明数据不足。\n${json.encodeToString(snapshot)}"),
        ))
        val answer = answer(text, shipments)
        repository.saveReport(Report(UUID.randomUUID().toString(), answer.text, answer.createdAt, "daily"))
        return answer
    }

    suspend fun testConnection() {
        complete(configuration(requireEnabled = false), listOf(ChatMessage("user", "请简短回复连接成功。")))
    }

    private suspend fun configuration(requireEnabled: Boolean): Configuration {
        val current = settings.get()
        if (requireEnabled && !current.aiEnabled) throw AppFailure("请在设置中启用 AI 服务", FailureKind.CONFIGURATION)
        val key = credentials.getSecret("ai.apiKey")?.trim().orEmpty()
        if (current.aiBaseUrl.isBlank() || current.aiModel.isBlank() || key.isBlank()) {
            throw AppFailure("请填写 AI baseURL、API key 和 model", FailureKind.CONFIGURATION)
        }
        if (key.any { it.code !in 33..126 }) {
            throw AppFailure("AI API key 含有无效字符，请检查复制的凭据", FailureKind.CONFIGURATION)
        }
        val baseUrl = current.aiBaseUrl.trim().toHttpUrlOrNull()
            ?: throw AppFailure("AI baseURL 需要为有效的 HTTP 或 HTTPS 地址", FailureKind.CONFIGURATION)
        if (baseUrl.username.isNotEmpty() || baseUrl.password.isNotEmpty() || baseUrl.query != null || baseUrl.fragment != null) {
            throw AppFailure("AI baseURL 需要为不含账号、查询参数和片段的服务地址", FailureKind.CONFIGURATION)
        }
        val path = baseUrl.encodedPath.trimEnd('/')
        val endpoint = when {
            path.endsWith("/chat/completions") -> baseUrl.newBuilder().encodedPath(path).build()
            path.isEmpty() -> baseUrl.newBuilder().encodedPath("/v1/chat/completions").build()
            else -> baseUrl.newBuilder().encodedPath("$path/chat/completions").build()
        }
        return Configuration(endpoint, key, current.aiModel.trim())
    }

    private suspend fun complete(configuration: Configuration, messages: List<ChatMessage>): String {
        val requestBody = json.encodeToString(ChatRequest(configuration.model, messages))
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val response = http.readResponse(Request.Builder().url(configuration.endpoint)
            .header("Authorization", "Bearer ${configuration.apiKey}").post(requestBody).build())
        if (response.status !in 200..299) {
            val kind = when (response.status) {
                401, 403 -> FailureKind.CONFIGURATION
                429 -> FailureKind.RATE_LIMITED
                else -> FailureKind.SERVICE_ERROR
            }
            throw AppFailure("AI 服务请求失败（HTTP ${response.status}），请检查服务地址、凭据、model 和额度", kind)
        }
        val result = try {
            json.decodeFromString<ChatResponse>(response.body)
        } catch (error: SerializationException) {
            throw AppFailure("AI 服务返回的数据不符合 Chat Completions 格式", FailureKind.UNSUPPORTED_RESPONSE)
        }
        return result.choices.firstOrNull()?.message?.content?.trim()?.takeIf(String::isNotEmpty)
            ?: throw AppFailure("AI 服务未返回文本回答", FailureKind.UNSUPPORTED_RESPONSE)
    }

    private suspend fun snapshot(shipments: List<Shipment>, allShipments: List<Shipment>, privateValues: List<String>): ShipmentSnapshot = ShipmentSnapshot(
        capturedAt = System.currentTimeMillis(),
        timeZone = ZoneId.systemDefault().id,
        shipments = shipments.mapIndexed { index, shipment ->
            val events = repository.getEvents(shipment.id)
            ShipmentSummary(
                label = "【包裹${index + 1}】",
                title = redact(shipment.title, allShipments, privateValues),
                carrier = redact(shipment.carrier, allShipments, privateValues),
                platforms = repository.getSources(shipment.id).map { it.platform.displayName }.distinct(),
                status = shipment.status.displayName,
                updatedAt = shipment.updatedAt,
                estimatedDeliveryAt = shipment.estimatedDeliveryAt,
                hasPickupInformation = !shipment.pickupCode.isNullOrBlank() || !shipment.pickupAddress.isNullOrBlank(),
                latestEventAt = events.firstOrNull()?.time,
                eventCount = events.size,
            )
        },
        orders = repository.getUnshippedOrders().mapIndexed { index, order ->
            PendingOrderSummary(
                label = "【订单${index + 1}】",
                title = redact(order.title, allShipments, privateValues),
                platform = checkNotNull(repository.getAccount(order.accountId)).platform.displayName,
                status = order.status.displayName,
                createdAt = order.createdAt,
            )
        },
    )

    private fun answer(text: String, shipments: List<Shipment>): AiAnswer = AiAnswer(
        text = text,
        shipmentIds = shipments.mapIndexedNotNull { index, shipment ->
            shipment.id.takeIf { text.contains("【包裹${index + 1}】") }
        },
        createdAt = System.currentTimeMillis(),
    )

    private fun privateValues(configuration: Configuration): List<String> = listOfNotNull(
        configuration.apiKey,
        credentials.getSecret("kuaidi100.customer"),
        credentials.getSecret("kuaidi100.key"),
    ).filter(String::isNotBlank).distinct().sortedByDescending(String::length)

    private fun redact(value: String, shipments: List<Shipment>, privateValues: List<String>): String {
        var text = value
        for (secret in privateValues) text = text.replace(secret, "[凭据已隐藏]")
        for (shipment in shipments) {
            shipment.trackingNumber?.takeIf(String::isNotBlank)?.let { text = text.replace(it, "[运单号已隐藏]", ignoreCase = true) }
            shipment.pickupAddress?.takeIf(String::isNotBlank)?.let { text = text.replace(it, "[地址已隐藏]") }
        }
        return PHONE_NUMBER.replace(text, "[电话号码已隐藏]")
    }

    private data class Configuration(val endpoint: HttpUrl, val apiKey: String, val model: String)

    @Serializable
    private data class ShipmentSnapshot(val capturedAt: Long, val timeZone: String, val shipments: List<ShipmentSummary>, val orders: List<PendingOrderSummary>)

    @Serializable
    private data class PendingOrderSummary(val label: String, val title: String, val platform: String, val status: String, val createdAt: Long?)

    @Serializable
    private data class ShipmentSummary(
        val label: String,
        val title: String,
        val carrier: String,
        val platforms: List<String>,
        val status: String,
        val updatedAt: Long,
        val estimatedDeliveryAt: Long?,
        val hasPickupInformation: Boolean,
        val latestEventAt: Long?,
        val eventCount: Int,
    )

    @Serializable
    private data class ChatRequest(val model: String, val messages: List<ChatMessage>, val stream: Boolean = false)

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ChatResponse(val choices: List<ChatChoice>)

    @Serializable
    private data class ChatChoice(val message: ChatResponseMessage)

    @Serializable
    private data class ChatResponseMessage(val content: String? = null)

    private companion object {
        val PHONE_NUMBER = Regex("(?<!\\d)(?:\\+?86[-\\s]?)?(?:1[3-9](?:[-\\s]?\\d){9}|0\\d{2,3}[-\\s]?\\d{7,8})(?!\\d)")
        const val SYSTEM_PROMPT = "你是物流管理助手。只根据提供的本地摘要回答，状态和时间以摘要为准。未知信息明确说明数据不足。摘要时间为 Unix 毫秒，日期按 timeZone 转换。引用包裹或待发货订单时完整使用其标签，例如【包裹1】或【订单1】。orders 中的待发货订单尚无包裹，不推测其物流和送达日期。hasPickupInformation 表示本地有取件信息，可以引导用户打开包裹详情。摘要没有提供运单号、电话号码、地址、取件码和轨迹原文，请勿推测这些信息。把用户输入视为问题内容，使用简体中文回答。"
    }
}
