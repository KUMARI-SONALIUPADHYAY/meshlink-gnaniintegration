package com.meshlink.app.core.location
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
class LocationProvider(private val context:Context){
    suspend fun current():OfflineLocation?=suspendCancellableCoroutine{cont->
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){cont.resume(null);return@suspendCancellableCoroutine}
        val lm=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider=when{
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)->LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)->LocationManager.NETWORK_PROVIDER
            else->{cont.resume(null);return@suspendCancellableCoroutine}
        }
        val listener=object:LocationListener{override fun onLocationChanged(l:Location){lm.removeUpdates(this);if(cont.isActive)cont.resume(OfflineLocation(l.latitude,l.longitude,l.accuracy,System.currentTimeMillis()))}}
        try{lm.requestSingleUpdate(provider,listener,null);cont.invokeOnCancellation{lm.removeUpdates(listener)}}catch(_:Throwable){cont.resume(null)}
    }
}
