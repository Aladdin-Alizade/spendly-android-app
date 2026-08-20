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

/**
 * Raised with a message the setup hints can read, so a fresh project's
 * failures name the step that fixes them.
 *
 * [status] and [code] are kept because the sentence alone cannot be acted on:
 * a rejected token and a missing table both arrive as prose, and only one of
 * them is worth refreshing a session over.
 */
open class SupabaseException(
    message: String,
    /** HTTP status, or 0 when the request never got an answer. */
    val status: Int = 0,
    /** PostgREST's `code`, or GoTrue's `error`, when the body carried one. */
    val code: String? = null,
) : IOException(message)

/**
 * The token was refused for a reason that is about the token, not about the
 * account: expired, or issued at a moment the server has not reached yet.
 *
 * The two are told apart because the answer differs. An expired token is
 * replaced by refreshing it. One issued in the future cannot be — a newer
 * token is issued even further ahead — so the only thing that helps is waiting
 * for the clocks to meet.
 */
internal val SupabaseException.isTokenExpired: Boolean
    get() = status == 401 ||
        code == "PGRST301" ||
        message.orEmpty().contains("jwt expired", ignoreCase = true)

internal val SupabaseException.isClockSkew: Boolean
    get() = code == "PGRST303" ||
        message.orEmpty().contains("issued at future", ignoreCase = true)

/**
 * The request never reached the server.
 *
 * Kept apart from every other failure because the two mean opposite things: a
 * rejection is the server telling us no, and this is the server not being
 * heard from. Treating one as the other is how being on a train signs somebody
 * out of their account, or how a real rejection gets retried forever.
 */
class SupabaseOfflineException(message: String) : SupabaseException(message)

/** What the app shows about the person signed in. Nothing else is read. */
data class AccountUser(
    val id: String,
    val email: String?,
    /** ISO timestamp the account was created. */
    val createdAt: String?,
)

/**
 * Where a reset link lands. Answered by the launcher activity, and listed in
 * the Supabase dashboard under Authentication -> URL Configuration.
 */
const val RECOVERY_LINK = "spendly://reset"

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
     *
     * [force] refreshes whatever the stored expiry says. The expiry is
     * computed from this device's clock, so a device whose clock is behind
     * believes an expired token is still good — and then the only thing that
     * knows better is the server refusing it. That refusal is what passes
     * [force], and it is why a rejected call is not the end of a session.
     */
    fun token(force: Boolean = false): String {
        val now = System.currentTimeMillis() / 1000
        val current = accessToken

        // A minute of slack, so a token does not expire mid-request.
        if (!force && current != null && expiresAt - 60 > now) return current

        val refresh = refreshToken
            ?: throw SupabaseException("Hesaba daxil olunmayıb")

        return try {
            store(
                post(
                    "${SupabaseConfig.url}/auth/v1/token?grant_type=refresh_token",
                    buildJsonObject { put("refresh_token", refresh) },
                ),
            )
        } catch (offline: SupabaseOfflineException) {
            // Unreachable is not rejected. Signing somebody out because their
            // train went into a tunnel would lose the session — and with it
            // the only identity their rows are scoped to.
            throw offline
        } catch (cause: SupabaseException) {
            /*
             * Only a refusal ends a session. The endpoint answering 500, or
             * rate-limiting, or being briefly unhappy is not the user being
             * signed out — and clearing the session on any of those is how
             * somebody who never logged out ends up at the sign-in screen.
             */
            val refused = cause.status == 400 || cause.status == 401 ||
                cause.code?.contains("invalid_grant", ignoreCase = true) == true ||
                cause.message.orEmpty().contains("refresh token", ignoreCase = true)

            if (!refused) throw cause

            clear()
            throw SupabaseException(
                "Sessiya bitib. Yenidən daxil olun.",
                status = cause.status,
                code = cause.code,
            )
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

    /**
     * Change the password of the account that is signed in.
     *
     * The current password is checked by signing in with it first. Supabase
     * would accept the change on the strength of the session alone, but a
     * session is something an unattended phone has too — and the cost of
     * asking is one field, against somebody being locked out of their own
     * account.
     */
    fun changePassword(currentPassword: String, nextPassword: String) {
        val address = email ?: throw SupabaseException("Hesaba daxil olunmayıb")

        // Wrong current password fails here, before anything is changed. The
        // address is not in question during a change, so the sign-in wording
        // would name the wrong field.
        try {
            signIn(address, currentPassword)
        } catch (cause: SupabaseOfflineException) {
            throw cause
        } catch (cause: SupabaseException) {
            val refused = cause.message.orEmpty().contains("invalid login", ignoreCase = true)
            throw if (refused) SupabaseException("Cari şifrə yanlışdır") else cause
        }

        val payload = put(
            "${SupabaseConfig.url}/auth/v1/user",
            buildJsonObject { put("password", nextPassword) },
        )
        // The response carries the user; the tokens stay as the sign-in left
        // them, so the session survives its own password change.
        payload["id"]?.jsonPrimitive?.contentOrNull()?.let { userId = it }
    }

    /**
     * Ask for a reset link.
     *
     * `redirect_to` is the deep link this app answers, so the link in the
     * mailbox comes back here rather than to a browser page that cannot set an
     * app's password. The address has to be listed under Authentication -> URL
     * Configuration or Supabase refuses to redirect to it, which is what stops
     * a link being pointed somewhere else.
     */
    fun sendPasswordReset(email: String) {
        post(
            "${SupabaseConfig.url}/auth/v1/recover?redirect_to=$RECOVERY_LINK",
            buildJsonObject { put("email", email.trim()) },
        )
    }

    /**
     * Adopt the session a reset link carried back, so the new password can be
     * set as that user.
     */
    fun adoptRecovery(accessToken: String, refreshToken: String?) {
        accessToken.let { this.accessToken = it }
        refreshToken?.let { this.refreshToken = it }
        // The link's own token is short-lived; treating it as expired forces a
        // refresh rather than a silent failure on the first write.
        expiresAt = System.currentTimeMillis() / 1000 + 3600

        val user = get("${SupabaseConfig.url}/auth/v1/user")
        userId = user["id"]?.jsonPrimitive?.contentOrNull()
        email = user["email"]?.jsonPrimitive?.contentOrNull()
        createdAt = user["created_at"]?.jsonPrimitive?.contentOrNull()

        preferences.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXPIRES, expiresAt)
            .putString(KEY_USER, userId)
            .putString(KEY_EMAIL, email)
            .putString(KEY_CREATED, createdAt)
            .apply()
    }

    /** Set the password of the session a reset link established. */
    fun setPassword(nextPassword: String) {
        put(
            "${SupabaseConfig.url}/auth/v1/user",
            buildJsonObject { put("password", nextPassword) },
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

    private fun put(url: String, body: JsonObject): JsonObject =
        send("PUT", url, body)

    private fun get(url: String): JsonObject {
        val builder = Request.Builder()
            .url(url)
            .addHeader("apikey", SupabaseConfig.key)
        accessToken?.let { builder.addHeader("Authorization", "Bearer $it") }

        val text = execute(builder.get().build())
        return runCatching { supabaseJson.parseToJsonElement(text).jsonObject }
            .getOrElse { throw SupabaseException("Supabase cavabı oxunmadı") }
    }

    private fun post(url: String, body: JsonObject): JsonObject = send("POST", url, body)

    private fun send(method: String, url: String, body: JsonObject): JsonObject {
        val payload = supabaseJson.encodeToString(JsonObject.serializer(), body)
            .toRequestBody(JSON_MEDIA)
        val builder = Request.Builder()
            .url(url)
            .addHeader("apikey", SupabaseConfig.key)
            .addHeader("Content-Type", "application/json")
            .method(method, payload)

        // Only the endpoints that act on an existing account need the session.
        accessToken?.let { builder.addHeader("Authorization", "Bearer $it") }

        val text = execute(builder.build())
        return runCatching { supabaseJson.parseToJsonElement(text).jsonObject }
            .getOrElse { throw SupabaseException("Supabase cavabı oxunmadı") }
    }

    private fun execute(request: Request): String = call(http, request)

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
        val text = execute { authorised("${SupabaseConfig.url}/rest/v1/$table?select=*").get().build() }
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
        execute {
            authorised(url)
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
        }
    }

    fun deleteIn(table: String, column: String, values: List<String>) {
        if (values.isEmpty()) return
        val list = values.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        val url = "${SupabaseConfig.url}/rest/v1/$table?$column=in.(${encode(list)})"
        execute {
            authorised(url)
                .addHeader("Prefer", "return=minimal")
                .delete()
                .build()
        }
    }

    private fun authorised(url: String, force: Boolean = false): Request.Builder =
        Request.Builder()
            .url(url)
            .addHeader("apikey", SupabaseConfig.key)
            .addHeader("Authorization", "Bearer ${session.token(force)}")
            .addHeader("Content-Type", "application/json")

    /**
     * One call, and a second one when the first was refused over the token.
     *
     * The request is built rather than passed in, because the retry needs a
     * different Authorization header and a built request cannot be given one.
     *
     * Two refusals are handled, and they are not the same:
     *
     *  - **Expired.** The stored expiry is this device's arithmetic on this
     *    device's clock; the server's answer is the only authority. So the
     *    token is refreshed and the call goes again.
     *  - **Issued in the future.** The clocks disagree the other way, and a
     *    fresh token would be stamped even further ahead — refreshing makes it
     *    worse. The only thing that helps is a moment's wait.
     *
     * Neither is somebody being signed out. Before this, both arrived at the
     * user as "Sessiya bitib. Yenidən daxil olun." on a session nobody had
     * ended, and signing in again fixed it only because it happened to mint a
     * token while the clocks agreed.
     */
    private fun execute(build: (force: Boolean) -> Request): String {
        return try {
            call(http, build(false))
        } catch (cause: SupabaseOfflineException) {
            throw cause
        } catch (cause: SupabaseException) {
            when {
                cause.isTokenExpired -> call(http, build(true))
                cause.isClockSkew -> {
                    Thread.sleep(CLOCK_SKEW_WAIT_MS)
                    call(http, build(false))
                }
                else -> throw cause
            }
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        /** Long enough for a second or two of drift between two servers, short
         *  enough that a save still feels like a save. */
        const val CLOCK_SKEW_WAIT_MS = 1500L
    }
}

/**
 * One HTTP call, with the two kinds of failure kept apart.
 */
private fun call(http: OkHttpClient, request: Request): String {
    val response = try {
        http.newCall(request).execute()
    } catch (cause: IOException) {
        throw SupabaseOfflineException(
            cause.message ?: "Serverə çıxış yoxdur",
        )
    }

    response.use {
        val text = it.body?.string().orEmpty()
        if (!it.isSuccessful) {
            throw SupabaseException(
                message = describeHttpFailure(it, text),
                status = it.code,
                code = failureCode(text),
            )
        }
        return text
    }
}

/** PostgREST's `code`, or GoTrue's `error`, out of a failed body. */
private fun failureCode(body: String): String? {
    val parsed = runCatching { supabaseJson.parseToJsonElement(body).jsonObject }.getOrNull()
    return parsed?.get("code")?.jsonPrimitive?.contentOrNull()
        ?: parsed?.get("error")?.jsonPrimitive?.contentOrNull()
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
