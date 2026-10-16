package com.irfan.rabta

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
object NearbySetup {
    const val PERMISSIONS=701
    fun permissions():List<String> = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION).apply {
        if(Build.VERSION.SDK_INT>=31)addAll(listOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.BLUETOOTH_CONNECT))
        if(Build.VERSION.SDK_INT>=33)add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    fun problem(c:Context):String? {
        if(permissions().any { c.checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED })return "Nearby permissions needed — tap to allow"
        if(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c)!=ConnectionResult.SUCCESS)return "Google Play services missing or needs an update"
        val bluetooth=(c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?:return "Bluetooth is unavailable on this device"
        if(!runCatching {bluetooth.isEnabled}.getOrDefault(false))return "Turn on Bluetooth"
        val lm=c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location=if(Build.VERSION.SDK_INT>=28)lm.isLocationEnabled else Settings.Secure.getInt(c.contentResolver,Settings.Secure.LOCATION_MODE,0)!=0
        if(!location)return "Turn on phone Location for Nearby discovery"
        return null
    }
    fun request(a:Activity) {val missing=permissions().filter {a.checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED};if(missing.isNotEmpty())a.requestPermissions(missing.toTypedArray(),PERMISSIONS)else MessageService.start(a)}
}
