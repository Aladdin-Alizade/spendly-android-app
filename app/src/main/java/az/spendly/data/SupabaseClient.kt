/**
 * Supabase over plain HTTP.
 *
 * The web app used supabase-js; here the two endpoints it actually needs —
 * GoTrue for the session and PostgREST for the rows — are called directly,
 * which keeps the dependency list to one HTTP client.
 *
 * The publishable key is designed to ship inside an app. It is not a secret,
 * and it is not what protects the data — row level security is. Every table is
 * scoped to `auth.uid()`, so this key alone reads nothing.
 */
package az.spendly.data

import android.content.Context
import az.spendly.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Raised with a message the setup hints can read, so a fresh project's
 *  failures name the step that fixes them. */
class SupabaseException(message: String) : IOException(message)

/** What the app shows about the person signed in. Nothing else is read. */
data class AccountUser(
    val id: String,
    val email: String?,
    /** ISO timestamp the account was created. */
    val createdAt: String?,
)

object SupabaseConfig {
    val url: String = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    val key: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()

    /** False when local.properties carries no project, so the app can fall
     *  back to keeping everything on the device. */
    val isConfigured: Boolean get() = url.isNotEmpty() && key.isNotEmpty()
}

private val JSON_MEDIA = "application/json".toMediaType()

internal val supabaseJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Authentication.
 *
 * Every row belongs to a user, so there has to be one before any read or
 * write — the RLS policies match `auth.uid()` and nothing else.
 *
 * This used to be an anonymous sign-in, which meant the identity lived on the
 * device: reinstalling the app, or opening it on another phone, minted a new
 * user and the previous rows became invisible under RLS. They were still in
 * the tables, owned by an id nothing could produce again. An email account
 * ties the data to something the user can present from any device.
 *
 * The tokens are kept so a signed-in user stays signed in across restarts.
 */
class SupabaseSession(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("spendly.supabase", Context.MODE_PRIVATE)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var accessToken: String? = preferences.getString(KEY_ACCESS, null)
    private var refreshToken: String? = preferences.getString(KEY_REFRESH, null)
    private var expiresAt: Long = preferences.getLong(KEY_EXPIRES, 0L)

    /** The signed-in user's id, or null when nobody is signed in. */
    var userId: String? = preferences.getString(KEY_USER, null)
        private set

    private var email: String? = preferences.getString(KEY_EMAIL, null)
    private var createdAt: String? = preferences.getString(KEY_CREATED, null)

    /** Who is signed in, for the profile. Null when nobody is. */
    val account: AccountUser?
        get() = userId?.let { AccountUser(it, email, createdAt) }

    /** True when a stored session can still be used or refreshed. */
    val isSignedIn: Boolean get() = userId != null && (accessToken != null || refreshToken != null)

    /**
     * A valid access token. Refreshes an expired one; never signs anybody in,
     * because who is signed in is the app's decision, not the transport's.
     */
    fun token(): String {
        val now = System.currentTimeMillis() / 1000
        val current = accessToken

        // A minute of slack, so a token does not expire mid-request.
        if (current != null && expiresAt - 60 > now) return current

        val refresh = refreshToken
            ?: throw SupabaseException("Hesaba daxil olunmayıb")

        return try {
            store(
                post(
                    "${SupabaseConfig.url}/auth/v1/token?grant_type=refresh_token",
                    buildJsonObject { put("refresh_token", refresh) },
                ),
            )
        } catch (cause: SupabaseException) {
            // A refresh token the server no longer accepts means the session
            // is over; keeping it would retry the same failure forever.
            clear()
            throw SupabaseException("Sessiya bitib. Yenidən daxil olun.")
        }
    }

    /**
     * Create an account. Returns false when the project confirms addresses
     * before the first sign-in — Supabase then creates the user but no
     * session, and the caller has to say so rather than dropping the user on
     * an empty screen.
     */
    fun signUp(email: String, password: String): Boolean {
        val payload = post(
            "${SupabaseConfig.url}/auth/v1/signup",
            buildJsonObject {
                put("email", email)
                put("password", password)
            },
        )
        val hasSession = payload["access_token"]?.jsonPrimitive?.contentOrNull() != null
        if (hasSession) store(payload)
        return hasSession
    }

    fun signIn(email: String, password: String) {
        store(
            post(
                "${SupabaseConfig.url}/auth/v1/token?grant_type=password",
                buildJsonObject {
                    put("email", email)
                    put("password", password)
                },
            ),
        )
    }

    fun signOut() {
        val token = accessToken
        if (token != null) {
            // A failed logout still ends the session on this device; the token
            // expires on its own regardless.
            runCatching {
                execute(
                    Request.Builder()
                        .url("${SupabaseConfig.url}/auth/v1/logout")
                        .addHeader("apikey", SupabaseConfig.key)
                        .addHeader("Authorization", "Bearer $token")
                        .post("{}".toRequestBody(JSON_MEDIA))
                        .build(),
                )
            }
        }
        clear()
    }

    private fun clear() {
        accessToken = null
        refreshToken = null
        expiresAt = 0
        userId = null
        email = null
        createdAt = null
        preferences.edit().clear().apply()
    }

    private fun store(payload: JsonObject): String {
        val access = payload["access_token"]?.jsonPrimitive?.contentOrNull()
            ?: throw SupabaseException("Supabase sessiya qaytarmadı")
        val refresh = payload["refresh_token"]?.jsonPrimitive?.contentOrNull()
        val expiresIn = payload["expires_in"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull()
            ?: 3600L
        val user = payload["user"]?.jsonObject ?: payload
        val id = user["id"]?.jsonPrimitive?.contentOrNull()

        accessToken = access
        refreshToken = refresh
        expiresAt = System.currentTimeMillis() / 1000 + expiresIn
        if (id != null) userId = id
        user["email"]?.jsonPrimitive?.contentOrNull()?.let { email = it }
        user["created_at"]?.jsonPrimitive?.contentOrNull()?.let { createdAt = it }

        preferences.edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .putLong(KEY_EXPIRES, expiresAt)
            .putString(KEY_USER, userId)
            .putString(KEY_EMAIL, email)
            .putString(KEY_CREATED, createdAt)
            .apply()

        return access
    }

    private fun post(url: String, body: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", SupabaseConfig.key)
            .addHeader("Content-Type", "application/json")
            .post(supabaseJson.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()

        val text = execute(request)
        return runCatching { supabaseJson.parseToJsonElement(text).jsonObject }
            .getOrElse { throw SupabaseException("Supabase cavabı oxunmadı") }
    }

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw SupabaseException(describeHttpFailure(response, text))
            return text
        }
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_USER = "user_id"
        const val KEY_EMAIL = "user_email"
        const val KEY_CREATED = "user_created_at"
    }
}

/**
 * PostgREST calls, with the session attached.
 *
 * Failures carry the API's own message through, because that is what names the
 * missing table or the disabled provider — the UI turns it into the setup step
 * that fixes it.
 */
class SupabaseRest(private val session: SupabaseSession) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun select(table: String): JsonArray {
        val request = authorised("${SupabaseConfig.url}/rest/v1/$table?select=*").get().build()
        val text = execute(request)
        return runCatching { supabaseJson.parseToJsonElement(text) as JsonArray }
            .getOrElse { throw SupabaseException("$table: cavab oxunmadı") }
    }

    /** Insert-or-update, keyed by the primary key unless [onConflict] names
     *  another unique column set. */
    fun upsert(table: String, rows: List<JsonElement>, onConflict: String? = null) {
        if (rows.isEmpty()) return
        val url = buildString {
            append("${SupabaseConfig.url}/rest/v1/$table")
            if (onConflict != null) append("?on_conflict=$onConflict")
        }
        val body = supabaseJson.encodeToString(JsonArray.serializer(), JsonArray(rows))
        val request = authorised(url)
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        execute(request)
    }

    fun deleteIn(table: String, column: String, values: List<String>) {
        if (values.isEmpty()) return
        val list = values.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        val url = "${SupabaseConfig.url}/rest/v1/$table?$column=in.(${encode(list)})"
        val request = authorised(url)
            .addHeader("Prefer", "return=minimal")
            .delete()
            .build()
        execute(request)
    }

    private fun authorised(url: String): Request.Builder = Request.Builder()
        .url(url)
        .addHeader("apikey", SupabaseConfig.key)
        .addHeader("Authorization", "Bearer ${session.token()}")
        .addHeader("Content-Type", "application/json")

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw SupabaseException(describeHttpFailure(response, text))
            return text
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/**
 * Everything useful out of a failed response.
 *
 * PostgREST puts the actionable part in `hint` and identifies the failure with
 * `code`; GoTrue uses `error_description` or `msg`. Reading only one of them
 * throws away the part that says what to do.
 */
private fun describeHttpFailure(response: Response, body: String): String {
    val parsed = runCatching { supabaseJson.parseToJsonElement(body).jsonObject }.getOrNull()

    val parts = listOfNotNull(
        parsed?.get("message")?.jsonPrimitive?.contentOrNull(),
        parsed?.get("error_description")?.jsonPrimitive?.contentOrNull(),
        parsed?.get("msg")?.jsonPrimitive?.contentOrNull(),
        parsed?.get("details")?.jsonPrimitive?.contentOrNull(),
        parsed?.get("hint")?.jsonPrimitive?.contentOrNull(),
    ).distinct()

    val code = parsed?.get("code")?.jsonPrimitive?.contentOrNull()
        ?: parsed?.get("error")?.jsonPrimitive?.contentOrNull()

    return when {
        parts.isEmpty() && code == null -> body.ifBlank { "HTTP ${response.code}" }
        parts.isEmpty() -> code.orEmpty()
        code == null -> parts.joinToString(" — ")
        else -> "$code: ${parts.joinToString(" — ")}"
    }
}

private fun JsonPrimitive.contentOrNull(): String? =
    content.takeIf { it.isNotBlank() && it != "null" }
