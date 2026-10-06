package com.logisticsmanagement.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.logisticsmanagement.app.data.LogisticsRepository
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.ImportedOrder
import com.logisticsmanagement.app.model.ImportedShipment
import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.SourceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Serializable
internal data class XiaomiConfiguration(val phones: List<String> = emptyList(), val oaid: String = "", val vaid: String = "")
internal data class XiaomiSession(val token: String, val cUserId: String, val userId: String)

// 字段、路径与加密算法依据 ExpressAssistant/api/XiaomiApi.kt；MIT 许可。
internal class XiaomiAdapter(private val registry: PlatformRegistry) : PlatformAdapter {
    override val platform = Platform.XIAOMI
    override val loginUrl = "https://account.xiaomi.com/pass/serviceLogin?sid=assistant"
    override val orderListUrl = loginUrl
    override val allowedOrigins = setOf("https://account.xiaomi.com", "https://api.assistant.miui.com", "https://api.account.xiaomi.com", "https://verca.xpa.assistant.miui.com")
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private val aesKey = "d101b17c77ff93cs"

    internal suspend fun configuration(account: Account): XiaomiConfiguration = withContext(Dispatchers.IO) {
        registry.accountSecret(account, "xiaomi.config")?.let { platformJson.decodeFromString<XiaomiConfiguration>(it) }
            ?: XiaomiConfiguration()
    }

    internal suspend fun saveConfiguration(account: Account, configuration: XiaomiConfiguration) = withContext(Dispatchers.IO) {
        require(configuration.phones.all { it.matches(Regex("1[0-9]{10}")) }) { "请输入完整的中国大陆手机号" }
        registry.saveAccountSecret(account, "xiaomi.config", platformJson.encodeToString(configuration))
    }

    internal suspend fun readDeviceIdentifiers(account: Account): XiaomiConfiguration = withContext(Dispatchers.IO) {
        val identifiers = try {
            val providerClass = Class.forName("com.android.id.impl.IdProviderImpl")
            val provider = providerClass.getDeclaredConstructor().newInstance()
            fun identifier(method: String): String =
                (providerClass.getMethod(method, Context::class.java).invoke(provider, registry.context) as? String)
                    ?.takeIf(String::isNotBlank)
                    ?: throw AppFailure("设备没有提供 $method 标识，请检查系统隐私设置", FailureKind.CONFIGURATION)
            identifier("getOAID") to identifier("getVAID")
        } catch (failure: ReflectiveOperationException) {
            throw AppFailure("当前系统没有允许应用读取 OAID 和 VAID，请使用设备提供的配置值", FailureKind.CONFIGURATION)
        } catch (failure: SecurityException) {
            throw AppFailure("系统拒绝了 OAID 和 VAID 的读取，请检查系统隐私设置", FailureKind.CONFIGURATION)
        }
        configuration(account).copy(oaid = identifiers.first, vaid = identifiers.second).also { saveConfiguration(account, it) }
    }

    internal suspend fun session(account: Account): XiaomiSession = withContext(Dispatchers.Main.immediate) {
        val profile = registry.profiles.profile(account)
        val accountCookies = cookieValues(profile, "https://account.xiaomi.com/")
        val serviceCookies = cookieValues(profile, "https://api.assistant.miui.com/")
        val token = serviceCookies["assistant_serviceToken"] ?: serviceCookies["serviceToken"]
        val cUserId = accountCookies["cUserId"] ?: serviceCookies["cUserId"]
        val userId = accountCookies["userId"]
        if (token.isNullOrBlank() || cUserId.isNullOrBlank() || userId.isNullOrBlank()) {
            throw AppFailure("小米账号尚未取得智能助理服务会话，请在登录网页完成授权", FailureKind.NEEDS_LOGIN)
        }
        XiaomiSession(token, cUserId, userId)
    }

    override suspend fun sync(account: Account, onBatch: suspend (List<ImportedOrder>) -> Unit) {
        val configuration = configuration(account)
        if (configuration.phones.isEmpty()) throw AppFailure("请在小米账号配置中绑定用于物流查询的手机号", FailureKind.CONFIGURATION)
        val data = post(account, "/cpa/express/v2/getList", buildJsonObject {
            put("info", buildJsonObject {
                put("limit", 29); put("phones", JsonArray(configuration.phones.map(::JsonPrimitive)))
                put("deletedMailNos", JsonArray(emptyList())); put("modifiedMailNos", JsonArray(emptyList()))
            })
        })
        val parcels = data.requiredArray("expressList").map { it.requireObject("小米包裹") }
        val orders = parcels.map { parcel ->
            val number = parcel.requiredText("mailNo")
            registry.saveAccountSecret(account, parcelKey(number), parcel.toString())
            val incoming = shipment(number, parcel)
            ImportedOrder(number, parcel.text("name").ifBlank { "快递" }, incoming.status, null, "", shipments = listOf(incoming))
        }
        if (orders.isNotEmpty()) onBatch(orders)
        if (parcels.size >= 29) throw AppFailure("小米返回的包裹达到当前接口数量上限，完整范围需要通过真实响应确认", FailureKind.UNSUPPORTED_RESPONSE)
    }

    override suspend fun tracking(account: Account, source: SourceRef): ImportedShipment {
        val raw = registry.accountSecret(account, parcelKey(source.remoteShipmentId))
            ?: throw AppFailure("小米包裹查询参数未保存，请更新账号包裹列表", FailureKind.CONFIGURATION)
        val parcel = platformJson.parseToJsonElement(raw).requireObject("小米包裹查询参数")
        val configuration = configuration(account)
        val latest = parcel.arrayOrNull("details")?.lastOrNull()?.requireObject("小米物流节点")
        val data = post(account, "/cpa/express/v2/query", buildJsonObject {
            put("info", buildJsonObject {
                put("phones", JsonArray(configuration.phones.map(::JsonPrimitive)))
                put("cpCode", parcel.text("cpCode")); put("mailNo", parcel.requiredText("mailNo")); put("name", parcel.text("name"))
                put("provider", parcel.text("provider")); put("stateNum", parcel.text("stateNum").toIntOrNull() ?: 0)
                val time = latest?.text("time").orEmpty()
                put("logisticsUpdateTime", if (time.isBlank()) "" else parsePlatformTime(time).toString())
                if (parcel.text("phone").isNotBlank()) put("phone", parcel.text("phone"))
                if (parcel.text("queryChannel").isNotBlank()) put("queryChannel", parcel.text("queryChannel"))
                put("channel", "1")
            })
        })
        val merged = JsonObject(parcel + data)
        registry.saveAccountSecret(account, parcelKey(source.remoteShipmentId), merged.toString())
        return shipment(source.remoteShipmentId, merged)
    }

    private fun shipment(remoteId: String, parcel: JsonObject): ImportedShipment {
        val events = parcel.arrayOrNull("details").orEmpty().map { node ->
            val point = node.requireObject("小米物流节点")
            trackingEvent(point.requiredText("time"), point.requiredText("desc"))
        }
        val latest = events.maxByOrNull { it.time }
        val stateText = parcel.text("state")
        val status = parcelStatus(stateText + " " + latest?.text.orEmpty()).let {
            if (it == ParcelStatus.UNKNOWN && parcel.text("stateNum") == "107") ParcelStatus.SIGNED else it
        }
        return ImportedShipment(remoteId, parcel.requiredText("mailNo"), parcel.text("name"), parcel.text("cpCode"), status,
            pickupCode = latest?.text?.let(::pickupCode), events = events)
    }

    internal suspend fun sendPhoneCode(account: Account, phone: String) {
        requirePhone(phone)
        post(account, "/cpa/express/phone/sendVerificationCode", buildJsonObject { put("info", buildJsonObject { put("phone", phone) }) }, false)
    }

    internal suspend fun bindPhone(account: Account, phone: String, code: String) {
        requirePhone(phone)
        if (code.isBlank()) throw AppFailure("请填写短信验证码", FailureKind.CONFIGURATION)
        post(account, "/cpa/express/phone/checkVerificationCode", buildJsonObject {
            put("info", buildJsonObject { put("phone", phone); put("verificationCode", code) })
        }, false)
        val current = configuration(account)
        val phones = (current.phones + phone).distinct()
        post(account, "/cpa/express/phone/bind", buildJsonObject {
            put("cardId", 3); put("serviceKey", "express")
            put("info", buildJsonObject { put("type", 1); put("phone", phone); put("phoneList", JsonArray(phones.map(::JsonPrimitive))) })
        }, false)
        saveConfiguration(account, current.copy(phones = phones))
    }

    private fun requirePhone(phone: String) {
        if (!phone.matches(Regex("1[0-9]{10}"))) throw AppFailure("请填写完整的中国大陆手机号", FailureKind.CONFIGURATION)
    }

    private fun parcelKey(number: String): String = "xiaomi.parcel.${LogisticsRepository.stableId(number)}"

    private suspend fun post(account: Account, path: String, body: JsonObject, expectData: Boolean = true): JsonObject {
        val session = session(account)
        val configuration = configuration(account)
        val assistant = withContext(Dispatchers.IO) {
            val packages = registry.context.packageManager.getInstalledPackages(0)
            packages.firstOrNull { it.packageName == "com.miui.personalassistant" }?.let(PackageInfoCompat::getLongVersionCode)
                ?: throw AppFailure("小米物流接口需要设备安装小米智能助理", FailureKind.CONFIGURATION)
        }
        val encryptedPath = encrypt(path)
        val sign = MessageDigest.getInstance("SHA-1").digest("yellowpage_encparam${encryptedPath}77eb2e8a5755abd016c0d69ba74b219c".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.uppercase(Locale.US)
        val url = "https://verca.xpa.assistant.miui.com$path".toHttpUrl().newBuilder()
            .addQueryParameter("version", assistant.toString()).addQueryParameter("appkey", "yellowpage")
            .addQueryParameter("yellowpage_encparam", encryptedPath).addQueryParameter("sign", sign).addQueryParameter("_encparam", encryptedPath).build()
        val wrapped = withContext(Dispatchers.IO) { wrap(body, session, configuration, assistant) }
        val request = Request.Builder().url(url).header("Cookie", "serviceToken=${session.token}; cUserId=${session.cUserId}")
            .post(encrypt(wrapped.toString()).toRequestBody("application/json".toMediaType())).build()
        val response = platformRequest(client, request)
        val root = platformResponse(response.body, "小米")
        if (root.text("code") != "0") throw AppFailure("小米智能助理返回业务错误 ${root.text("code")}", FailureKind.SERVICE_ERROR)
        if (!expectData) return root
        val encrypted = root.requiredText("data")
        val data = platformResponse(decrypt(encrypted), "小米业务数据")
        registry.rememberCapture(account, CapturedResponse("response", url.toString(), data.toString(), 200))
        return data
    }

    private fun wrap(body: JsonObject, session: XiaomiSession, configuration: XiaomiConfiguration, assistantVersion: Long): JsonObject {
        val context = registry.context
        val packages = context.packageManager.getInstalledPackages(0).associateBy { it.packageName }
        fun version(name: String): Long? = packages[name]?.let(PackageInfoCompat::getLongVersionCode)
        fun systemProperty(name: String): String = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
        val vendor = Class.forName("miui.os.Build")
        val miuiType = when {
            vendor.getField("IS_ALPHA_BUILD").getBoolean(null) -> "alpha"
            vendor.getField("IS_DEVELOPMENT_VERSION").getBoolean(null) -> "dev"
            vendor.getField("IS_STABLE_VERSION").getBoolean(null) -> "stable"
            else -> "custom"
        }
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = network.getNetworkCapabilities(network.activeNetwork)
        val connection = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "MOBILE"
            else -> "NONE"
        }
        return buildJsonObject {
            put("userSignal", buildJsonObject { put("oaid", configuration.oaid); put("vaid", configuration.vaid); put("userId", session.userId) })
            put("environmentSignal", buildJsonObject {
                put("terminal", "phone"); put("assistantVersion", assistantVersion)
                version("com.miui.home")?.let { put("launchVersion", it) }
                put("androidVersion", Build.VERSION.RELEASE); put("androidSdkVersion", Build.VERSION.SDK_INT)
                put("miuiType", miuiType); put("phoneModel", Build.MODEL); put("phoneDevice", Build.DEVICE); put("connectionType", connection)
                put("miuiVersionName", systemProperty("ro.miui.ui.version.name")); put("miuiVersion", systemProperty("ro.miui.ui.version.code"))
                put("osVersionName", systemProperty("ro.mi.os.version.name")); put("osVersion", systemProperty("ro.mi.os.version.code"))
                packages["com.xiaomi.misettings"]?.let { put("miSettingsVersionName", it.versionName.orEmpty()); put("miSettingsVersionCode", PackageInfoCompat.getLongVersionCode(it)) }
                put("timeZone", TimeZone.getDefault().getDisplayName(false, 0)); put("language", Locale.getDefault().language); put("country", Locale.getDefault().country)
                put("screen", 1); put("coordinate", ""); put("reqId", UUID.randomUUID().toString().replace("-", ""))
                val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                put("ip", address?.hostAddress.orEmpty()); put("cpuArchitecture", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                version("com.xiaomi.market")?.let { put("marketVersion", it) }
                put("os", Build.VERSION.INCREMENTAL); put("clientFlag", 0); put("hwVersion", systemProperty("ro.boot.hwversion"))
            })
            put("timeSignal", buildJsonObject { put("clientTs", System.currentTimeMillis().toString()); put("timeZone", TimeZone.getDefault().getDisplayName(false, 0)) })
            put("eventSignal", body)
        }
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey.toByteArray(Charsets.UTF_8), "AES"))
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }
    private fun decrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey.toByteArray(Charsets.UTF_8), "AES"))
        return cipher.doFinal(Base64.getDecoder().decode(text)).toString(Charsets.UTF_8)
    }
}
