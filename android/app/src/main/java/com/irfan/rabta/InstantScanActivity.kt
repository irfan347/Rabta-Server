package com.irfan.rabta

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/** QR-only camera. Decoding a code never approves a contact or consumes an invitation. */
class InstantScanActivity:ThemedActivity() {
    private lateinit var camera:BarcodeView
    private lateinit var status:TextView
    private var visible=false
    private var checking=false
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val body=FeatureUi.page(this,"Scan to connect")
        body.addView(RabtaUi.text(this,"Scan an invitation, pairing reply or confirmation. You will review the identity before approving.",15f,color=AppTheme.sub(this)))
        val frame=FrameLayout(this).apply { background=RabtaUi.shape(this@InstantScanActivity,AppTheme.raised(this@InstantScanActivity));clipToOutline=true }
        camera=BarcodeView(this).apply { decoderFactory=DefaultDecoderFactory(InstantQr.formats,InstantQr.decodeHints,null,0) }
        frame.addView(camera,FrameLayout.LayoutParams(-1,-1))
        frame.addView(object:View(this) {
            private val p=Paint(Paint.ANTI_ALIAS_FLAG)
            init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
            override fun onDraw(c:Canvas) {
                val edge=minOf(width,height)*0.74f;val left=(width-edge)/2;val top=(height-edge)/2;val right=left+edge;val bottom=top+edge;val arm=edge*0.12f
                p.color=AppTheme.accent(context);p.strokeWidth=RabtaUi.dp(context,3).toFloat();p.style=Paint.Style.STROKE
                for((x,y) in listOf(left to top,right to top,left to bottom,right to bottom)) {
                    c.drawLine(x,y,x+if(x==left)arm else -arm,y,p);c.drawLine(x,y,x,y+if(y==top)arm else -arm,p)
                }
            }
        },FrameLayout.LayoutParams(-1,-1))
        body.addView(frame,LinearLayout.LayoutParams(-1,RabtaUi.dp(this,320)).apply { topMargin=RabtaUi.dp(this@InstantScanActivity,20);bottomMargin=RabtaUi.dp(this@InstantScanActivity,16) })
        status=RabtaUi.text(this,"Camera permission is needed to scan a QR code.",14f,color=AppTheme.sub(this));body.addView(status)
        camera.addStateListener(object:CameraPreview.StateListener {
            override fun previewSized() {}
            override fun previewStarted() { status.text="Camera ready · hold the QR code inside the frame" }
            override fun previewStopped() {}
            override fun cameraClosed() {}
            override fun cameraError(error:Exception) { status.text="Camera unavailable. Close other camera apps, check permission, then retry." }
        })
        body.addView(RabtaUi.button(this,"Allow camera / Retry scanner",true) { startCamera(true) })
        body.addView(RabtaUi.button(this,"App camera settings") { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) })
        body.addView(RabtaUi.button(this,"Cancel scan") { setResult(Activity.RESULT_CANCELED);finish() })
        body.addView(FeatureUi.text(this,"Only public pairing information is scanned. No login password, private key or account token is exchanged."))
    }
    override fun onResume() { super.onResume();visible=true;startCamera(false) }
    override fun onPause() { visible=false;camera.pause();super.onPause() }
    private fun startCamera(request:Boolean) {
        if(!visible||checking)return
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            status.text="Allow camera permission to scan. You can also return and show your own invitation."
            if(request)requestPermissions(arrayOf(Manifest.permission.CAMERA),902)
            return
        }
        status.text="Starting camera…"
        camera.decodeSingle(object:BarcodeCallback {
            override fun barcodeResult(result:BarcodeResult) {
                if(checking||!visible)return
                checking=true;camera.pause();status.text="Checking invitation signature and expiry…"
                Thread {
                    val raw=result.text.orEmpty()
                    val verified=runCatching { InstantInvitation.verify(raw,System.currentTimeMillis()) }
                    runOnUiThread {
                        checking=false
                        if(!visible||isDestroyed)return@runOnUiThread
                        verified.onSuccess { setResult(Activity.RESULT_OK,Intent().putExtra("pairing_code",raw));finish() }
                            .onFailure { status.text=it.message?:"Invalid pairing code. Ask for a fresh Instant Connect invitation, then retry." }
                    }
                }.start()
            }
            override fun possibleResultPoints(points:List<ResultPoint>) {}
        })
        camera.resume()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==902)startCamera(false)
    }
}
