package com.logisticsmanagement.app.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.logisticsmanagement.app.data.CredentialStore
import com.logisticsmanagement.app.data.LogisticsRepository
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.CaptureMode
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.Order
import com.logisticsmanagement.app.model.SourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

class PlatformRegistry(
    context: Context,
    internal val repository: LogisticsRepository,
    internal val credentials: CredentialStore,
) {
    internal val context = context.applicationContext
    internal val profiles = AccountProfiles(this.context)
    private val credentialLocks = ConcurrentHashMap<String, Mutex>()
    private val adapters: Map<Platform, PlatformAdapter> by lazy {
        listOf(TaobaoAdapter(this), JdAdapter(this), PddAdapter(this), DouyinAdapter(this)).associateBy { it.platform }
    }

    fun prepareProfiles() = profiles.prepare()
    suspend fun cleanupRemovedProfiles() {
        for (pending in repository.pendingProfileCleanup()) {
            profiles.deleteProfile(pending.profileName)
            credentials.deleteAccount(pending.accountId)
            repository.finishProfileCleanup(pending.accountId)
        }
        credentials.deleteServiceSecrets()
    }
    fun adapter(platform: Platform): PlatformAdapter = adapters[platform]
        ?: throw AppFailure("此来源没有平台账号", FailureKind.CONFIGURATION)

    suspend fun deleteAccountProfile(account: Account) {
        credentialLocks.getOrPut(account.id) { Mutex() }.withLock {
            profiles.delete(account)
            withContext(Dispatchers.IO) { credentials.deleteAccount(account.id) }
        }
    }

    internal suspend fun accountSecret(account: Account, name: String): String? = accountCredentials(account) {
        credentials.getSecret("account.${account.id}.$name")
    }

    internal suspend fun saveAccountSecret(account: Account, name: String, value: String) = accountCredentials(account) {
        credentials.putSecret("account.${account.id}.$name", value)
    }

    private suspend fun <T> accountCredentials(account: Account, action: () -> T): T = withContext(Dispatchers.IO) {
        credentialLocks.getOrPut(account.id) { Mutex() }.withLock {
            if (repository.getAccount(account.id)?.deleted != false) throw AppFailure("账号已移除", FailureKind.NEEDS_LOGIN)
            action()
        }
    }

    fun openLogin(context: Context, account: Account) {
        context.startPlatformIntent(Intent(context, PlatformBrowserActivity::class.java)
            .putExtra("accountId", account.id).putExtra("mode", "login"))
    }

    fun openSource(context: Context, account: Account, source: SourceRef) {
        context.startActivity(sourceIntent(context, account, source))
    }

    internal fun sourceIntent(context: Context, account: Account, source: SourceRef): Intent {
        require(source.accountId == account.id && source.platform == account.platform)
        val intent = if (usesNativeDouyin(account)) {
            Intent(context, DouyinCaptureActivity::class.java)
                .putExtra("accountId", account.id).putExtra("remoteOrderId", source.remoteOrderId)
        } else Intent(context, PlatformBrowserActivity::class.java)
            .putExtra("accountId", account.id).putExtra("sourceId", source.id)
            .putExtra("shipmentId", source.shipmentId).putExtra("mode", "source")
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    fun openOrder(context: Context, account: Account, order: Order) {
        require(order.accountId == account.id)
        if (usesNativeDouyin(account)) {
            context.startPlatformIntent(Intent(context, DouyinCaptureActivity::class.java)
                .putExtra("accountId", account.id).putExtra("remoteOrderId", order.remoteId))
            return
        }
        context.startPlatformIntent(Intent(context, PlatformBrowserActivity::class.java)
            .putExtra("accountId", account.id).putExtra("orderId", order.id).putExtra("mode", "order"))
    }

    private fun usesNativeDouyin(account: Account): Boolean =
        account.platform == Platform.DOUYIN && account.captureMode == CaptureMode.APP_PAGE

    private fun Context.startPlatformIntent(intent: Intent) {
        if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    internal suspend fun rememberCapture(account: Account, capture: CapturedResponse) {
        if (capture.kind !in setOf("response", "ssr", "dom", "capacity", "accessibility", "redirect", "trackingUnavailable")) return
        accountCredentials(account) {
            val key = "account.${account.id}.captures"
            val previous = credentials.getSecret(key)?.let { platformJson.decodeFromString<List<CapturedResponse>>(it) }.orEmpty()
            val batch = (previous.filterNot { it.kind == capture.kind && it.url == capture.url && it.body == capture.body } + capture).takeLast(8)
            credentials.putSecret(key, platformJson.encodeToString(batch))
            if (account.platform == Platform.DOUYIN && capture.kind == "response") {
                credentials.putSecret("account.${account.id}.douyin.page", capture.url)
            }
        }
    }

    internal suspend fun rememberDouyinPage(account: Account, url: String) {
        if (account.platform == Platform.DOUYIN && allowsOrigin(Uri.parse(url), adapter(account.platform).allowedOrigins)) {
            saveAccountSecret(account, "douyin.orderPage", url)
        }
    }

    internal suspend fun diagnostic(account: Account): String = withContext(Dispatchers.IO) {
        val captures = accountSecret(account, "captures")
            ?.let { platformJson.decodeFromString<List<CapturedResponse>>(it) }.orEmpty()
        buildJsonObject {
            put("platform", account.platform.name)
            put("responses", JsonArray(captures.map { capture ->
                buildJsonObject {
                    put("kind", capture.kind)
                    val uri = Uri.parse(capture.url)
                    val path = uri.pathSegments.joinToString("/", prefix = "/") { segment ->
                        if (Regex("[0-9]{5,}|[A-Fa-f0-9]{16,}").containsMatchIn(segment)) "identifier" else segment
                    }
                    put("endpoint", uri.buildUpon().clearQuery().fragment(null).path(path).build().toString())
                    put("httpStatus", capture.status)
                    put("characters", capture.characters ?: capture.body.length)
                    put("schema", if (capture.kind == "dom") JsonPrimitive("DOM text") else responseSchema(capture.body))
                }
            }))
        }.toString()
    }

    private fun responseSchema(body: String): JsonElement {
        val root = try { platformJson.parseToJsonElement(body) }
        catch (failure: SerializationException) { return buildJsonObject { put("type", "text") } }
        return schema(root, 0)
    }

    private fun schema(value: JsonElement, depth: Int): JsonElement {
        if (depth > 8) return JsonPrimitive("结构层数超过诊断范围")
        return when (value) {
            is JsonObject -> buildJsonObject {
                put("type", "object")
                put("fields", JsonArray(value.entries.take(100).mapIndexed { index, entry ->
                    buildJsonObject {
                        put("name", entry.key.takeIf { it.matches(Regex("[A-Za-z_$][A-Za-z_$]*")) } ?: "field_$index")
                        put("schema", schema(entry.value, depth + 1))
                    }
                }))
            }
            is JsonArray -> buildJsonObject {
                put("type", "array")
                put("length", value.size)
                put("items", JsonArray(value.take(3).map { schema(it, depth + 1) }.distinct()))
            }
            JsonNull -> JsonPrimitive("null")
            is JsonPrimitive -> JsonPrimitive(when {
                value.isString -> "string"
                value.content == "true" || value.content == "false" -> "boolean"
                else -> "number"
            })
        }
    }
}
