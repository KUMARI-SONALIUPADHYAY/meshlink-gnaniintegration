package com.meshlink.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.meshlink.app.R
import com.meshlink.app.core.mesh.MeshEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class MeshForegroundService:Service(){
    @Inject lateinit var engine:MeshEngine
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    override fun onCreate(){super.onCreate();createChannel()}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){stateJob?.cancel();stateJob=null;engine.stop();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY}
        if(Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION_ID,notification("Mesh active • discovering nearby nodes…"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION_ID,notification("Mesh active • discovering nearby nodes…"))
        // START_STICKY may redeliver onStartCommand. Keep one state collector for
        // the service lifetime rather than accumulating collectors on every start.
        engine.start()
        if (stateJob?.isActive != true) {
            stateJob = scope.launch {
                engine.state.collect { state ->
                    if (state.active) updateNotification("Mesh active • ${state.peers.size} nearby nodes")
                    else updateNotification("MeshLink mesh is paused")
                }
            }
        }
        return START_STICKY
    }
    override fun onDestroy(){stateJob?.cancel();stateJob=null;scope.cancel();engine.stop();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    private fun createChannel(){
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL,"MeshLink Mesh",NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text:String):Notification=NotificationCompat.Builder(this,CHANNEL)
        .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("MeshLink").setContentText(text).setOngoing(true).build()
    private fun updateNotification(text:String)=getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,notification(text))
    companion object{
        const val ACTION_START="com.meshlink.app.MESH_START";const val ACTION_STOP="com.meshlink.app.MESH_STOP"
        const val CHANNEL="meshlink_mesh";const val NOTIFICATION_ID=100
        fun start(context:android.content.Context){ContextCompat.startForegroundService(context,Intent(context,MeshForegroundService::class.java).setAction(ACTION_START))}
        fun stop(context:android.content.Context){context.startService(Intent(context,MeshForegroundService::class.java).setAction(ACTION_STOP))}
    }
}
