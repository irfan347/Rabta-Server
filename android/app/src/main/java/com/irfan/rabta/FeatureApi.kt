package com.irfan.rabta

import android.app.Activity
import android.widget.Toast
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object FeatureApi {
    val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
    fun request(path:String,method:String="GET",body:JSONObject?=null):JSONObject {
        val b=Request.Builder().url(Session.serverUrl+path).header("Authorization","Bearer ${Session.token}")
        if(method!="GET")b.method(method,(body?:JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { response ->
            val raw=response.body?.string().orEmpty();val json=runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            if(response.code>=500)throw java.io.IOException("Server temporarily unavailable — try again")
            check(response.isSuccessful) { json.optString("error","This feature is unavailable on the server (${response.code})") }
            return json
        }
    }
    fun <T> run(a:Activity,work:()->T,done:(T)->Unit) { Thread { val r=runCatching(work);a.runOnUiThread { if(!a.isDestroyed&&!a.isFinishing)r.onSuccess(done).onFailure { Toast.makeText(a,it.message?:"Please try again",Toast.LENGTH_LONG).show() } } }.start() }
}
