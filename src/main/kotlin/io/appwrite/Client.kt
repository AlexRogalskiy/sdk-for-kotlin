package io.appwrite

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.appwrite.exceptions.AppwriteException
import io.appwrite.extensions.fromJson
import io.appwrite.json.PreciseNumberAdapter
import io.appwrite.models.UploadProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

class Client @JvmOverloads constructor(
    var endPoint: String = "https://appwrite.io/v1",
    private var selfSigned: Boolean = false
) : CoroutineScope {

    companion object {
        const val CHUNK_SIZE = 5*1024*1024; // 5MB
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private val job = Job()

    private val gson = GsonBuilder().registerTypeAdapter(
        object : TypeToken<Map<String, Any>>(){}.type,
        PreciseNumberAdapter()
    ).create()

    lateinit var http: OkHttpClient

    private val headers: MutableMap<String, String>

    val config: MutableMap<String, String>


    init {
        headers = mutableMapOf(
            "content-type" to "application/json",
            "x-sdk-version" to "appwrite:kotlin:${BuildConfig.SDK_VERSION}",            
            "x-appwrite-response-format" to "0.13.0"
        )
        config = mutableMapOf()

        setSelfSigned(selfSigned)
    }

    /**
     * Set Project
     *
     * Your project ID
     *
     * @param {string} project
     *
     * @return this
     */
    fun setProject(value: String): Client {
        config["project"] = value
        addHeader("x-appwrite-project", value)
        return this
    }

    /**
     * Set Key
     *
     * Your secret API key
     *
     * @param {string} key
     *
     * @return this
     */
    fun setKey(value: String): Client {
        config["key"] = value
        addHeader("x-appwrite-key", value)
        return this
    }

    /**
     * Set JWT
     *
     * Your secret JSON Web Token
     *
     * @param {string} jwt
     *
     * @return this
     */
    fun setJWT(value: String): Client {
        config["jWT"] = value
        addHeader("x-appwrite-jwt", value)
        return this
    }

    /**
     * Set Locale
     *
     * @param {string} locale
     *
     * @return this
     */
    fun setLocale(value: String): Client {
        config["locale"] = value
        addHeader("x-appwrite-locale", value)
        return this
    }

    /**
     * Set self Signed
     *
     * @param status
     *
     * @return this
     */
    fun setSelfSigned(status: Boolean): Client {
        selfSigned = status

        val builder = OkHttpClient()
            .newBuilder()

        if (!selfSigned) {
            http = builder.build()
            return this
        }

        try {
            // Create a trust manager that does not validate certificate chains
            val trustAllCerts = arrayOf<TrustManager>(
                @Suppress("CustomX509TrustManager")
                object : X509TrustManager {
                    @Suppress("TrustAllX509TrustManager")
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    }
                    @Suppress("TrustAllX509TrustManager")
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    }
                    override fun getAcceptedIssuers(): Array<X509Certificate> {
                        return arrayOf()
                    }
                }
            )
            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory: SSLSocketFactory = sslContext.socketFactory
            builder.sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier(HostnameVerifier { _, _ -> true })

            http = builder.build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }

        return this
    }

    /**
    * Set endpoint.
    *
    * @param endpoint
    *
    * @return this
    */
    fun setEndpoint(endPoint: String): Client {
        this.endPoint = endPoint
        return this
    }

    /**
     * Add Header
     *
     * @param key
     * @param value
     *
     * @return this
     */
    fun addHeader(key: String, value: String): Client {
        headers[key] = value
        return this
    }

    /**
     * Send the HTTP request
     *
     * @param method
     * @param path
     * @param headers
     * @param params
     *
     * @return [T]    
     */
    @Throws(AppwriteException::class)
    suspend fun <T> call(
        method: String,
        path: String,
        headers:  Map<String, String> = mapOf(),
        params: Map<String, Any?> = mapOf(),
        responseType: Class<T>,
        convert: ((Map<String, Any,>) -> T)? = null
    ): T {
        val filteredParams = params.filterValues { it != null }

        val requestHeaders = this.headers.toHeaders().newBuilder()
            .addAll(headers.toHeaders())
            .build()

        val httpBuilder = (endPoint + path).toHttpUrl().newBuilder()

        if ("GET" == method) {
            filteredParams.forEach {
                when (it.value) {
                    null -> {
                        return@forEach
                    }
                    is List<*> -> {
                        val list = it.value as List<*>
                        for (index in list.indices) {
                            httpBuilder.addQueryParameter(
                                "${it.key}[]",
                                list[index].toString()
                            )
                        }
                    }
                    else -> {
                        httpBuilder.addQueryParameter(it.key, it.value.toString())
                    }
                }
            }
            val request = Request.Builder()
                .url(httpBuilder.build())
                .headers(requestHeaders)
                .get()
                .build()

            return awaitResponse(request, responseType, convert)
        }

        val body = if (MultipartBody.FORM.toString() == headers["content-type"]) {
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)

            filteredParams.forEach {
                when {
                    it.key == "file" -> {
                        builder.addPart(it.value as MultipartBody.Part)
                    }
                    it.value is List<*> -> {
                        val list = it.value as List<*>
                        for (index in list.indices) {
                            builder.addFormDataPart(
                                "${it.key}[]",
                                list[index].toString()
                            )
                        }
                    }
                    else -> {
                        builder.addFormDataPart(it.key, it.value.toString())
                    }
                }
            }
            builder.build()
        } else {
            gson.toJson(filteredParams)
                .toRequestBody("application/json".toMediaType())
        }

        val request = Request.Builder()
            .url(httpBuilder.build())
            .headers(requestHeaders)
            .method(method, body)
            .build()

        return awaitResponse(request, responseType, convert)
    }

    /**
     * Upload a file in chunks
     *
     * @param path
     * @param headers
     * @param params
     *
     * @return [T]
     */
    @Throws(AppwriteException::class)
    suspend fun <T> chunkedUpload(
        path: String,
        headers:  MutableMap<String, String>,
        params: MutableMap<String, Any?>,
        responseType: Class<T>,
        convert: ((Map<String, Any,>) -> T),
        paramName: String,
        onProgress: ((UploadProgress) -> Unit)? = null,
    ): T {
        val file = params[paramName] as File
        val size = file.length()

        if (size < CHUNK_SIZE) {
            params[paramName] = MultipartBody.Part.createFormData(
                paramName,
                file.name,
                file.asRequestBody()
            )
            return call(
                "POST",
                path,
                headers,
                params,
                responseType,
                convert
            )
        }

        val input = file.inputStream().buffered()
        val buffer = ByteArray(CHUNK_SIZE)
        var offset = 0L
        var result: Map<*, *>? = null

        generateSequence {
            val readBytes = input.read(buffer)
            if (readBytes >= 0) {
                buffer.copyOf(readBytes)
            } else {
                input.close()
                null
            }
        }.forEach {
            params[paramName] = MultipartBody.Part.createFormData(
                paramName,
                file.name,
                it.toRequestBody()
            )

            headers["Content-Range"] =
                "bytes $offset-${((offset + CHUNK_SIZE) - 1).coerceAtMost(size)}/$size"

            result = call(
                "POST",
                path,
                headers,
                params,
                Map::class.java
            )

            offset += CHUNK_SIZE
            headers["x-appwrite-id"] = result!!["\$id"].toString()
            onProgress?.invoke(UploadProgress(
                id = result!!["\$id"].toString(),
                progress = offset.coerceAtMost(size).toDouble()/size * 100,
                sizeUploaded = offset.coerceAtMost(size),
                chunksTotal = result!!["chunkTotal"].toString().toInt(),
                chunksUploaded = result!!["chunkUploaded"].toString().toInt(),
            ))
        }

        return convert(result as Map<String, Any>)
    }

    /**
     * Await Response
     *
     * @param request
     * @param responseType
     * @param convert
     *
     * @return [T]
     */
    @Throws(AppwriteException::class)
    private suspend fun <T> awaitResponse(
        request: Request,
        responseType: Class<T>,
        convert: ((Map<String, Any,>) -> T)? = null
    ) = suspendCancellableCoroutine<T> {
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (it.isCancelled) {
                    return
                }
                it.cancel(e)
            }

            @Suppress("UNCHECKED_CAST")
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val body = response.body!!
                        .charStream()
                        .buffered()
                        .use(BufferedReader::readText)
                        
                    val error = if (response.headers["content-type"]?.contains("application/json") == true) {
                        val map = gson.fromJson<Map<String, Any>>(
                            body,
                            object : TypeToken<Map<String, Any>>(){}.type
                        )
                        AppwriteException(
                            map["message"] as? String ?: "", 
                            (map["code"] as Number).toInt(),
                            map["type"] as? String ?: "", 
                            body
                        )
                    } else {
                        AppwriteException(body, response.code)
                    }
                    it.cancel(error)
                    return
                }
                when {
                    responseType == Boolean::class.java -> {
                        it.resume(true as T)
                        return
                    }
                    responseType == ByteArray::class.java -> {
                        it.resume(response.body!!
                            .byteStream()
                            .buffered()
                            .use(BufferedInputStream::readBytes) as T
                        )
                        return
                    }
                    response.body == null -> {
                        it.resume(true as T)
                        return
                    }
                }
                val body = response.body!!
                    .charStream()
                    .buffered()
                    .use(BufferedReader::readText)
                if (body.isEmpty()) {
                    it.resume(true as T)
                    return
                }
                val map = gson.fromJson<Map<String, Any>>(
                    body,
                    object : TypeToken<Map<String, Any>>(){}.type
                )
                it.resume(
                    convert?.invoke(map) ?: map as T
                )
            }
        })
    }
}