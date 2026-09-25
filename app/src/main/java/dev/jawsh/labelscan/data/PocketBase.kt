package dev.jawsh.labelscan.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One product record on the server. [id] is PocketBase's own record id. */
data class RemoteProduct(val id: String, val product: Product)

/** One photo record on the server, with the stored filename needed to build its download URL. */
data class RemotePhoto(val id: String, val upc: String, val kind: PhotoKind, val filename: String, val createdMs: Long)

/**
 * Minimal PocketBase REST client for the LabelScan backend. Talks to the `products` and
 * `photos` collections and authenticates as the app's `users` account. All calls are
 * blocking and throw [IOException] on failure — run them off the main thread (the worker does).
 */
class PocketBase(private val baseUrl: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    /** Authenticates and returns a token, or throws. */
    fun authWithPassword(identity: String, password: String): String {
        val body = JSONObject().put("identity", identity).put("password", password)
            .toString().toRequestBody(json)
        val req = Request.Builder()
            .url("$baseUrl/api/collections/users/auth-with-password")
            .post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("auth failed (${resp.code}): ${text.take(200)}")
            return JSONObject(text).optString("token").ifEmpty { throw IOException("no token in auth response") }
        }
    }

    private fun get(token: String, url: String): JSONObject {
        val req = Request.Builder().url(url).header("Authorization", token).get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) throw UnauthorizedException()
            if (!resp.isSuccessful) throw IOException("GET $url -> ${resp.code}: ${text.take(200)}")
            return JSONObject(text)
        }
    }

    private fun send(token: String, method: String, url: String, body: RequestBody): JSONObject {
        val req = Request.Builder().url(url).header("Authorization", token).method(method, body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) throw UnauthorizedException()
            if (!resp.isSuccessful) throw IOException("$method $url -> ${resp.code}: ${text.take(200)}")
            return JSONObject(text.ifEmpty { "{}" })
        }
    }

    // ---- products ----

    fun findProductId(token: String, upc: String): String? {
        val url = "$baseUrl/api/collections/products/records".toHttpUrl().newBuilder()
            .addQueryParameter("perPage", "1")
            .addQueryParameter("filter", "(upc='${upc.replace("'", "")}')")
            .build().toString()
        val items = get(token, url).optJSONArray("items") ?: return null
        return if (items.length() > 0) items.getJSONObject(0).optString("id") else null
    }

    /** Creates or updates the product keyed by UPC. */
    fun upsertProduct(token: String, p: Product) {
        val body = productJson(p).toString().toRequestBody(json)
        val id = findProductId(token, p.upc)
        if (id == null) {
            send(token, "POST", "$baseUrl/api/collections/products/records", body)
        } else {
            send(token, "PATCH", "$baseUrl/api/collections/products/records/$id", body)
        }
    }

    fun listProducts(token: String, page: Int, perPage: Int = 200): Pair<List<RemoteProduct>, Int> {
        val url = "$baseUrl/api/collections/products/records".toHttpUrl().newBuilder()
            .addQueryParameter("perPage", perPage.toString())
            .addQueryParameter("page", page.toString())
            .build().toString()
        val obj = get(token, url)
        val totalPages = obj.optInt("totalPages", 1)
        val items = obj.optJSONArray("items") ?: return emptyList<RemoteProduct>() to totalPages
        val out = ArrayList<RemoteProduct>(items.length())
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            out += RemoteProduct(o.optString("id"), o.toProduct())
        }
        return out to totalPages
    }

    // ---- photos ----

    /** Uploads a photo's bytes and returns the new record id. */
    fun createPhoto(token: String, upc: String, kind: PhotoKind, createdMs: Long, filename: String, bytes: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("upc", upc)
            .addFormDataPart("kind", kind.name)
            .addFormDataPart("created_ms", createdMs.toString())
            .addFormDataPart("image", filename, bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        return send(token, "POST", "$baseUrl/api/collections/photos/records", body).optString("id")
    }

    fun listPhotos(token: String, page: Int, perPage: Int = 200): Pair<List<RemotePhoto>, Int> {
        val url = "$baseUrl/api/collections/photos/records".toHttpUrl().newBuilder()
            .addQueryParameter("perPage", perPage.toString())
            .addQueryParameter("page", page.toString())
            .build().toString()
        val obj = get(token, url)
        val totalPages = obj.optInt("totalPages", 1)
        val items = obj.optJSONArray("items") ?: return emptyList<RemotePhoto>() to totalPages
        val out = ArrayList<RemotePhoto>(items.length())
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            out += RemotePhoto(
                id = o.optString("id"),
                upc = o.optString("upc"),
                kind = PhotoKind.from(o.optString("kind")),
                filename = o.optString("image"),
                createdMs = o.optLong("created_ms"),
            )
        }
        return out to totalPages
    }

    /** Fetches one photo record (for its stored filename), or null if missing. */
    fun getPhoto(token: String, id: String): RemotePhoto? {
        val o = runCatching { get(token, "$baseUrl/api/collections/photos/records/$id") }.getOrNull() ?: return null
        val filename = o.optString("image")
        if (filename.isEmpty()) return null
        return RemotePhoto(id, o.optString("upc"), PhotoKind.from(o.optString("kind")), filename, o.optLong("created_ms"))
    }

    /** Downloads a photo's image bytes. */
    fun downloadPhoto(token: String, rp: RemotePhoto): ByteArray {
        val url = "$baseUrl/api/files/photos/${rp.id}/${rp.filename}"
        val req = Request.Builder().url(url).header("Authorization", token).get().build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw UnauthorizedException()
            if (!resp.isSuccessful) throw IOException("download $url -> ${resp.code}")
            return resp.body?.bytes() ?: throw IOException("empty image body")
        }
    }

    fun deleteRecord(token: String, collection: String, id: String) {
        val req = Request.Builder()
            .url("$baseUrl/api/collections/$collection/records/$id")
            .header("Authorization", token).delete().build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw UnauthorizedException()
            // 404 is fine — already gone.
            if (!resp.isSuccessful && resp.code != 404) throw IOException("delete $collection/$id -> ${resp.code}")
        }
    }

    fun deleteProductByUpc(token: String, upc: String) {
        findProductId(token, upc)?.let { deleteRecord(token, "products", it) }
    }

    private fun productJson(p: Product) = JSONObject().apply {
        put("upc", p.upc)
        put("name", p.name)
        put("category", p.category)
        put("item_no", p.itemNo)
        put("size", p.size)
        put("dept", p.dept)
        put("plu", p.plu)
        put("last_slot", p.lastSlot)
        put("price", p.price)
        put("unit_price", p.unitPrice)
        put("notes", p.notes)
        put("times_seen", p.timesSeen)
        put("first_seen", p.firstSeen)
        put("last_seen", p.lastSeen)
    }

    private fun JSONObject.toProduct() = Product(
        upc = optString("upc"),
        name = optString("name"),
        category = optString("category"),
        itemNo = optString("item_no"),
        size = optString("size"),
        dept = optString("dept"),
        plu = optString("plu"),
        lastSlot = optString("last_slot"),
        price = optString("price"),
        unitPrice = optString("unit_price"),
        notes = optString("notes"),
        timesSeen = optInt("times_seen"),
        firstSeen = optLong("first_seen"),
        lastSeen = optLong("last_seen"),
    )

    class UnauthorizedException : IOException("unauthorized")
}
