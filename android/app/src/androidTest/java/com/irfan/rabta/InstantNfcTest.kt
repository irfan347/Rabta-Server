package com.irfan.rabta

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** Real HCE handler and pairing store; no physical NFC field or camera hardware certified. */
@RunWith(AndroidJUnit4::class)
class InstantNfcTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ctx=inst.targetContext
    private fun id()=java.util.UUID.randomUUID().toString().replace("-","").take(12).uppercase()
    private fun clean(account:String) {
        ctx.getSharedPreferences("instant_connect_$account",0).edit().clear().commit();ctx.getSharedPreferences("mesh_keys_$account",0).edit().clear().commit()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null);deleteEntry("rabta_mesh_v2_$account") }
    }
    @Test fun realHostTransfersOnlyArmedPublicCodeAndStopsOnAccountChange() {
        Session.init(ctx);val old=listOf(Session.id,Session.name,Session.username,Session.token,Session.serverUrl)
        val account=id();val owner=id();val store=InstantPairingStore(ctx,account)
        try {
            val offer=store.create("NFC fixture")
            inst.runOnMainSync { MessageService.stop(ctx);Session.id=account;Session.name="NFC fixture";Session.username="nfc_fixture";Session.token="never-transferred-fixture";Session.serverUrl="https://127.0.0.1:8443" }
            val host=InstantNfcHostService()
            assertTrue(runCatching { InstantNfcProtocol.payload(host.processCommandApdu(InstantNfcProtocol.select,null)) }.isFailure)
            InstantNfcSession.arm(owner,account,offer.raw)
            InstantNfcProtocol.payload(host.processCommandApdu(InstantNfcProtocol.select,null))
            val out=java.io.ByteArrayOutputStream()
            while(out.size()<offer.raw.toByteArray().size) {
                val count=minOf(InstantNfcProtocol.CHUNK,offer.raw.toByteArray().size-out.size())
                out.write(InstantNfcProtocol.payload(host.processCommandApdu(InstantNfcProtocol.read(out.size(),count),null)))
            }
            val raw=String(out.toByteArray(),Charsets.UTF_8)
            assertEquals(offer.raw,raw);assertFalse(raw.contains("never-transferred-fixture"));InstantInvitation.verify(raw,System.currentTimeMillis())
            assertEquals(offer.raw,store.view().offer);assertFalse(store.paired())
            Session.id=id()
            assertTrue(runCatching { InstantNfcProtocol.payload(host.processCommandApdu(InstantNfcProtocol.size,null)) }.isFailure)
            Session.id=account;assertNull("Old NFC share cannot revive",InstantNfcSession.current())
            InstantNfcSession.arm(owner,account,offer.raw);InstantNfcSession.clear(owner)
            assertNull(InstantNfcSession.current())
        }finally {
            InstantNfcSession.clear(owner)
            inst.runOnMainSync { MessageService.stop(ctx);Session.id=old[0];Session.name=old[1];Session.username=old[2];Session.token=old[3];Session.serverUrl=old[4] }
            clean(account)
        }
    }
    @Test fun hceServiceIsSystemProtectedAndOptionalWithUnlockRequired() {
        val info=ctx.packageManager.getServiceInfo(ComponentName(ctx,InstantNfcHostService::class.java),PackageManager.GET_META_DATA)
        assertTrue(info.exported);assertEquals("android.permission.BIND_NFC_SERVICE",info.permission)
        val xml=ctx.resources.getXml(info.metaData.getInt("android.nfc.cardemulation.host_apdu_service"))
        try {
            var unlock=false;var aid=""
            while(xml.eventType!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if(xml.eventType==org.xmlpull.v1.XmlPullParser.START_TAG) {
                    if(xml.name=="host-apdu-service")unlock=xml.getAttributeBooleanValue("http://schemas.android.com/apk/res/android","requireDeviceUnlock",false)
                    if(xml.name=="aid-filter")aid=xml.getAttributeValue("http://schemas.android.com/apk/res/android","name")
                }
                xml.next()
            }
            assertTrue(unlock);assertEquals(InstantNfcProtocol.AID,aid)
        }finally{xml.close()}
        ctx.packageManager.getPackageInfo(ctx.packageName,PackageManager.GET_CONFIGURATIONS).reqFeatures.orEmpty().filter { it.name in listOf("android.hardware.nfc","android.hardware.nfc.hce") }.forEach {
            assertEquals(0,it.flags and android.content.pm.FeatureInfo.FLAG_REQUIRED)
        }
    }
    @Test fun failedDurableConsumptionNeverPinsOrPublishesConfirmation() {
        val aid=id();val bid=id();val a=InstantPairingStore(ctx,aid);val b=InstantPairingStore(ctx,bid)
        try {
            val offer=a.create("A");val reply=b.respond(offer.raw,"B")
            val fail=object:ContextWrapper(ctx) {
                override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                    val real=ctx.getSharedPreferences(name,mode)
                    if(name!="instant_connect_$aid")return real
                    return object:SharedPreferences by real {
                        override fun edit():SharedPreferences.Editor {
                            val editor=real.edit();var consume=false
                            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { proxy,method,args ->
                                if(method.name=="putString"&&args?.get(0)=="used")consume=true
                                if(method.name=="commit"&&consume)false else {
                                    val result=method.invoke(editor,*(args?:emptyArray()))
                                    if(result is SharedPreferences.Editor)proxy else result
                                }
                            } as SharedPreferences.Editor
                        }
                    }
                }
            }
            assertTrue(runCatching { InstantPairingStore(fail,aid).approve(reply.raw) }.isFailure)
            assertFalse(a.paired());assertNull(MeshSecurity(ctx,aid).key(bid));assertNull(a.view().confirmation)
            assertEquals(offer.raw,a.view().offer)
        }finally{clean(aid);clean(bid)}
    }
    @Test fun scannerScreenProvidesPermissionGuidanceAndCancelWithoutPairing() {
        val screen=inst.startActivitySync(Intent(ctx,InstantScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InstantScanActivity
        try {
            inst.waitForIdleSync()
            inst.runOnMainSync {
                fun all(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) }else emptyList()
                val labels=all(screen.window.decorView).filterIsInstance<android.widget.TextView>()
                assertTrue(labels.any { it.text=="Allow camera / Retry scanner" })
                labels.first { it.text=="Cancel scan" }.performClick()
            }
        }finally{inst.runOnMainSync { screen.finish() }}
    }
}
