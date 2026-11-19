package io.github.mayueanyou.lifetracker.backup

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The few Drive calls a backup needs, over plain HTTPS. With the drive.file
 * permission, Drive shows this app only the files it made itself -- nothing
 * else in anyone's Drive is visible to it.
 */
class Drive(private val token: String) {
    class Item(val id: String, val name: String, val folder: Boolean)

    class HttpError(val code: Int, message: String) : IOException(message)

    fun email(): String? =
        runCatching { JSONObject(call("GET", "$API/about?fields=user(emailAddress)")).getJSONObject("user").getString("emailAddress") }
            .getOrNull()

    fun findFolder(name: String, parent: String?): String? {
        val q = "name = '${name.replace("'", "\\'")}' and mimeType = '$FOLDER' and trashed = false and " +
            "'${parent ?: "root"}' in parents"
        val files = JSONObject(call("GET", "$API/files?q=${enc(q)}&fields=files(id)&spaces=drive")).getJSONArray("files")
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    fun createFolder(name: String, parent: String?): String {
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER)
        if (parent != null) meta.put("parents", org.json.JSONArray().put(parent))
        return JSONObject(call("POST", "$API/files?fields=id", meta.toString(), "application/json")).getString("id")
    }

    fun folder(name: String, parent: String?): String = findFolder(name, parent) ?: createFolder(name, parent)

    /** Renames one of this app's own files or folders. */
    fun rename(id: String, name: String) {
        call("PATCH", "$API/files/$id?fields=id", JSONObject().put("name", name).toString(), "application/json")
    }

    /** Everything directly in [parent], folders included. */
    fun list(parent: String): List<Item> {
        val out = ArrayList<Item>()
        var page: String? = null
        do {
            val q = "'$parent' in parents and trashed = false"
            val url = "$API/files?q=${enc(q)}&fields=nextPageToken,files(id,name,mimeType)&pageSize=1000" +
                (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val json = JSONObject(call("GET", url))
            val files = json.getJSONArray("files")
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                out += Item(f.getString("id"), f.getString("name"), f.getString("mimeType") == FOLDER)
            }
            page = json.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    /** Writes [json] as [name] in [parent]: replacing file [id] if it is still there, otherwise a new file. */
    fun put(name: String, parent: String, json: String, id: String?): String {
        if (id != null) {
            try {
                return JSONObject(call("PATCH", "$UPLOAD/files/$id?uploadType=media&fields=id", json, "application/json")).getString("id")
            } catch (e: HttpError) {
                if (e.code != 404) throw e        // deleted from Drive: write it afresh
            }
        }
        val boundary = "lr${System.nanoTime()}"
        val meta = JSONObject().put("name", name).put("mimeType", "application/json")
            .put("parents", org.json.JSONArray().put(parent))
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: application/json\r\n\r\n$json\r\n--$boundary--"
        return JSONObject(call("POST", "$UPLOAD/files?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary"))
            .getString("id")
    }

    fun download(id: String): String = call("GET", "$API/files/$id?alt=media")

    private fun call(method: String, url: String, body: String? = null, type: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection has no PATCH; Drive accepts the override header.
            c.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type)
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw HttpError(code, "Drive $method ${url.substringBefore('?')}: $code ${err.take(200)}")
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER = "application/vnd.google-apps.folder"
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** Withdraws the app's access altogether -- what "Disconnect" means. */
        fun revoke(token: String) {
            val c = URL("https://oauth2.googleapis.com/revoke?token=${enc(token)}").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { }
                c.responseCode
            } finally {
                c.disconnect()
            }
        }
    }
}
