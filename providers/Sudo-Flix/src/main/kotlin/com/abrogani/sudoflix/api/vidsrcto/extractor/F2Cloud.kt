package com.abrogani.sudoflix.api.vidsrcto.extractor

import com.flixclusive.core.util.network.Crypto.base64Encode
import com.abrogani.sudoflix.api.util.fromJson
import com.flixclusive.core.util.network.okhttp.request
import com.flixclusive.model.provider.link.MediaLink
import com.abrogani.sudoflix.api.util.EmbedExtractor
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal class F2Cloud(
    client: OkHttpClient
) : EmbedExtractor(client) {
    override val baseUrl = "https://vid2v11.site"
    override val name = "F2Cloud"

    override suspend fun extract(
        url: String,
        customHeaders: Map<String, String>?,
        onLinkFound: (MediaLink) -> Unit
    ) {
        val encodedId = getKey(url = url)
        val sourcesUrl = getSourcesUrl(id = encodedId, url = url)

        TODO("Fix this broken extractor")
    }

    private fun getKey(url: String): String {
        val id = url.substringBefore("?").substringAfterLast("/")

        val keysUrl = "https://raw.githubusercontent.com/Ciarands/vidsrc-keys/main/keys.json"
        val keys = client.request(keysUrl).execute()
            .fromJson<List<String>>()
        return encodeId(id, keys)
    }

    private fun encodeId(id: String, keyList: List<String>): String {
        val cipher1 = Cipher.getInstance("RC4")
        val cipher2 = Cipher.getInstance("RC4")
        cipher1.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyList[0].toByteArray(), "RC4"),
            cipher1.parameters
        )
        cipher2.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyList[1].toByteArray(), "RC4"),
            cipher2.parameters
        )
        var input = id.toByteArray()
        input = cipher1.doFinal(input)
        input = cipher2.doFinal(input)
        return base64Encode(input).replace("/", "_")
    }

    private fun getSourcesUrl(
        id: String,
        url: String
    ): String {
        val script = client.request(
            url = "$baseUrl/futoken",
            headers = mapOf("Referer" to url).toHeaders()
        ).execute()
            .body?.string()
            ?: throw Exception("[$name]> Failed to get script")

        val k = Regex("""var\s+k\s*=\s*'([^']+)'""").find(script)
            ?.groupValues?.get(1)
            ?: throw Exception("[$name]> Failed to get key from script")

        val a = mutableListOf(k)
        for (i in id.indices) {
            a.add((k[i % k.length].code + id[i].code).toString())
        }

        return "$baseUrl/mediainfo/${a.joinToString(",")}?${url.substringAfter("?")}"
    }
}