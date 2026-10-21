package com.irfan.rabta

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
object AppLock {
    private var unlockedAt=0L
    private var leftAt=0L
    fun left(){leftAt=SystemClock.elapsedRealtime()}
    fun needed(c:Context)=Privacy.prefs(c).getBoolean("lock",false)&&(unlockedAt==0L||(leftAt>0&&SystemClock.elapsedRealtime()-leftAt>30000))
    fun authenticate(a:ThemedActivity,failed:()->Unit={},done:()->Unit) {
        val allowed=BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if(BiometricManager.from(a).canAuthenticate(allowed)!=BiometricManager.BIOMETRIC_SUCCESS){android.widget.Toast.makeText(a,"Set up a screen lock or fingerprint in phone settings first",android.widget.Toast.LENGTH_LONG).show();failed();if(Privacy.prefs(a).getBoolean("lock",false))a.finish();return}
        val prompt=BiometricPrompt(a,ContextCompat.getMainExecutor(a),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){unlockedAt=SystemClock.elapsedRealtime();leftAt=0;done()}
            override fun onAuthenticationError(code:Int,message:CharSequence){failed();if(Privacy.prefs(a).getBoolean("lock",false))a.finish()}
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Rabta").setAllowedAuthenticators(allowed).build())
    }
}
