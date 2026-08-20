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
import kotlinx.serialization.json.JsonNull
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
class SupabaseSession private constructor(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("spendly.supabase", Context.MODE_PRIVATE)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Written by whichever thread refreshes and read by all the others.
     *
     * A load asks for seven tables at once, so seven threads read these at the
     * same moment and one of them may be replacing them. Without @Volatile the
     * others are entitled to keep seeing the token that was just refused.
     */
    @Volatile private var accessToken: String? = preferences.getString(KEY_ACCESS, null)
    @Volatile private var refreshToken: String? = preferences.getString(KEY_REFRESH, null)
    @Volatile private var expiresAt: Long = preferences.getLong(KEY_EXPIRES, 0L)

    /** The signed-in user's id, or null when nobody is signed in. */
    @Volatile var userId: String? = preferences.getString(KEY_USER, null)
        private set

    @Volatile private var email: String? = preferences.getString(KEY_EMAIL, null)
    @Volatile private var createdAt: String? = preferences.getString(KEY_CREATED, null)

    /**
     * Held only while a refresh is in flight, so seven parallel reads send one
     * refresh between them rather than seven.
     *
     * Sending seven is not merely wasteful: they race each other over a token
     * the server rotates as it answers, and the losers come back as
     * `invalid_grant` — which this class reads as the session being over. That
     * is how a signed-in person ended up at the sign-in screen for no reason
     * they could see.
     */
    private val refreshLock = Any()

    /** Who is signed in, for the profile. Null when nobody is. */
    val account: AccountUser?
        get() = userId?.let { AccountUser(it, email, createdAt) }

    /** True when a stored session can still be used or refreshed. */
    val isSignedIn: Boolean get() = userId != null && (accessToken != null || refreshToken != null)

    /** A minute of slack, so a token does not expire mid-request. */
    private fun usable(token: String?): Boolean =
        token != null && expiresAt - 60 > System.currentTimeMillis() / 1000

    /**
     * A valid access token. Refreshes an expired one; never signs anybody in,
     * because who is signed in is the app's decision, not the transport's.
     *
     * [refused] is the token the server has just rejected. The stored expiry
     * is this device's arithmetic on this device's clock, so a device whose
     * clock is behind believes an expired token is still good — and then the
     * only thing that knows better is the server. Naming the rejected token
     * rather than passing a flag is what lets a caller that waited on the lock
     * see that somebody else has already replaced it, and use that instead of
     * refreshing a second time.
     */
    fun token(refused: String? = null): String {
        val current = accessToken
        if (current != null && current != refused && usable(current)) return current

        synchronized(refreshLock) {
            // Another thread may have refreshed while this one waited.
            val settled = accessToken
            if (settled != null && settled != refused && usable(settled)) return settled

            return refresh()
        }
    }

    private fun refresh(): String {
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
        // `spendly://reset` carries a colon and two slashes, which have to be
        // escaped to survive as one query value rather than being read as the
        // start of something else.
        val redirect = java.net.URLEncoder.encode(RECOVERY_LINK, "UTF-8")
        post(
            "${SupabaseConfig.url}/auth/v1/recover?redirect_to=$redirect",
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

    private fun execute(request: Request): String = call(http, request).body

    companion object {
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_USER = "user_id"
        private const val KEY_EMAIL = "user_email"
        private const val KEY_CREATED = "user_created_at"

        @Volatile private var instance: SupabaseSession? = null

        /**
         * One session for the whole process.
         *
         * There used to be two: one behind the sign-in screen and one behind
         * the store, each holding its own copy of the tokens in memory while
         * both wrote to the same preferences file. Signing out cleared one of
         * them — the other still held what it had read at startup and wrote it
         * back on its next refresh, so the app came back signed in as somebody
         * who had left, on a phone somebody else may now be holding.
         */
        fun get(context: Context): SupabaseSession =
            instance ?: synchronized(this) {
                instance ?: SupabaseSession(context.applicationContext).also { instance = it }
            }
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

    /**
     * Every row of a table, in pages.
     *
     * PostgREST answers with at most `db-max-rows` rows and says nothing about
     * it — Supabase ships that set to 1000. A single unpaged request therefore
     * looked like a complete answer while quietly being the first thousand
     * rows, and the merge then wrote that truncated picture back over the
     * device's own copy: an account with more history than that watched a
     * shifting subset of it appear and disappear.
     *
     * So the total comes from the server's own `Content-Range` rather than
     * from the size of a page, and [order] keeps the pages from overlapping —
     * without it PostgREST is free to answer in any order it likes and an
     * offset means nothing.
     */
    fun select(table: String, order: String): JsonArray {
        val rows = mutableListOf<JsonElement>()
        var total: Int? = null

        while (true) {
            val url = "${SupabaseConfig.url}/rest/v1/$table" +
                "?select=*&order=$order.asc&limit=$PAGE_ROWS&offset=${rows.size}"

            val answer = execute { token ->
                authorised(url, token)
                    // Counting is what makes the end of the table knowable, so
                    // it is asked for once and not on every page.
                    .apply { if (total == null) addHeader("Prefer", "count=exact") }
                    .get()
                    .build()
            }

            val page = runCatching { supabaseJson.parseToJsonElement(answer.body) as JsonArray }
                .getOrElse { throw SupabaseException("$table: cavab oxunmadı") }

            if (page.isEmpty()) break
            rows.addAll(page)
            if (total == null) total = totalOf(answer.contentRange)
            if (total != null && rows.size >= total!!) break
        }

        return JsonArray(rows)
    }

    /** Insert-or-update, keyed by the primary key unless [onConflict] names
     *  another unique column set. Sent in batches, so a first sync of a long
     *  history is a series of requests rather than one the server refuses. */
    fun upsert(table: String, rows: List<JsonElement>, onConflict: String? = null) {
        if (rows.isEmpty()) return
        val url = buildString {
            append("${SupabaseConfig.url}/rest/v1/$table")
            if (onConflict != null) append("?on_conflict=$onConflict")
        }

        for (batch in rows.chunked(WRITE_ROWS)) {
            val body = supabaseJson.encodeToString(JsonArray.serializer(), JsonArray(batch))
            execute { token ->
                authorised(url, token)
                    .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()
            }
        }
    }

    /**
     * Delete the rows whose [column] is one of [values].
     *
     * The list goes in the URL, so it is sent a batch at a time: an id is
     * around forty characters once escaped, and "delete everything" on a real
     * history built a request line long enough for the gateway to refuse it
     * outright. Clearing an account worked for somebody with fifty rows and
     * failed for somebody with five hundred.
     */
    fun deleteIn(table: String, column: String, values: List<String>) {
        if (values.isEmpty()) return

        for (batch in values.chunked(DELETE_KEYS)) {
            val list = batch.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
            val url = "${SupabaseConfig.url}/rest/v1/$table?$column=in.(${encode(list)})"
            execute { token ->
                authorised(url, token)
                    .addHeader("Prefer", "return=minimal")
                    .delete()
                    .build()
            }
        }
    }

    private fun authorised(url: String, token: String): Request.Builder =
        Request.Builder()
            .url(url)
            .addHeader("apikey", SupabaseConfig.key)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")

    /**
     * One call, and a second one when the first was refused over the token.
     *
     * The token is handed to [build] rather than fetched inside it, and that
     * is the point: the retry has to use a *different* one, and when the flag
     * saying so was the builder's own argument every call site quietly dropped
     * it and re-sent the token the server had just rejected. Passing the token
     * itself makes forgetting impossible, and it also tells the session which
     * token was refused — so a caller that queued behind somebody else's
     * refresh can use what that refresh produced instead of asking for another.
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
    private fun execute(build: (token: String) -> Request): HttpAnswer {
        val used = session.token()
        return try {
            call(http, build(used))
        } catch (cause: SupabaseOfflineException) {
            throw cause
        } catch (cause: SupabaseException) {
            when {
                cause.isTokenExpired -> call(http, build(session.token(refused = used)))
                cause.isClockSkew -> {
                    Thread.sleep(CLOCK_SKEW_WAIT_MS)
                    call(http, build(session.token()))
                }
                else -> throw cause
            }
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** `items 0-999/2500` -> 2500. Null when the server did not say. */
    private fun totalOf(contentRange: String?): Int? =
        contentRange?.substringAfterLast('/')?.trim()?.toIntOrNull()

    private companion object {
        /** Long enough for a second or two of drift between two servers, short
         *  enough that a save still feels like a save. */
        const val CLOCK_SKEW_WAIT_MS = 1500L

        /** Rows asked for per read. The server caps this as well, which is why
         *  nothing here treats a short page as the end of the table. */
        const val PAGE_ROWS = 1000

        /** Rows written per request. */
        const val WRITE_ROWS = 500

        /** Ids per delete. These travel in the URL, which is the short one. */
        const val DELETE_KEYS = 100
    }
}

/** A body, and the one header the paging reads. */
private class HttpAnswer(val body: String, val contentRange: String?)

/**
 * One HTTP call, with the two kinds of failure kept apart.
 */
private fun call(http: OkHttpClient, request: Request): HttpAnswer {
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
        return HttpAnswer(text, it.header("Content-Range"))
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

/**
 * The text of a primitive, or null when there is none.
 *
 * JSON null is asked about directly rather than by comparing the rendered
 * text: `content` renders it as the four letters "null", which is also a
 * perfectly ordinary thing for somebody to have typed.
 */
private fun JsonPrimitive.contentOrNull(): String? =
    if (this is JsonNull) null else content.takeIf { it.isNotBlank() }
