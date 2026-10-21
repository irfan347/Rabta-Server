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
    fun request(path:String,method:String="GET",body:JSONObject?=null,serverUrl:String=Session.serverUrl,token:String=Session.token):JSONObject {
        val b=Request.Builder().url(serverUrl+path).header("Authorization","Bearer $token")
        if(method!="GET")b.method(method,(body?:JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { response ->
            val raw=response.body?.string().orEmpty();val json=runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            if(response.code>=500)throw java.io.IOException("Server temporarily unavailable — try again")
            check(response.isSuccessful) { json.optString("error","This isn't available right now. Try again later.") }
            return json
        }
    }
    fun <T> run(a:Activity,work:()->T,done:(T)->Unit) { Thread { val r=runCatching(work);a.runOnUiThread { if(!a.isDestroyed&&!a.isFinishing)r.onSuccess(done).onFailure { Toast.makeText(a,it.message?:"Please try again",Toast.LENGTH_LONG).show() } } }.start() }
}
