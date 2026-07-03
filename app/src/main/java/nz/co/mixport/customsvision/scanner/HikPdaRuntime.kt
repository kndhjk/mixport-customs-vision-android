package nz.co.mixport.customsvision.scanner

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

object HikPdaRuntime {
    private const val TAG = "HikPdaRuntime"
    private const val SERVICE_BIND_ACTION = "com.hikrobotics.pdaservice.PdaBaseService"

    @Volatile
    private var initialized = false

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var bound = false

    private var receiver: BroadcastReceiver? = null
    private var serviceConnection: ServiceConnection? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        if (initialized || !HikPdaScanBridge.isPdaServiceInstalled(appContext)) {
            return
        }
        synchronized(this) {
            if (initialized || !HikPdaScanBridge.isPdaServiceInstalled(appContext)) {
                return
            }
            registerGlobalReceiver(appContext)
            bindService(appContext)
            HikPdaScanBridge.prepareRuntime(appContext)
            initialized = true
        }
    }

    private fun registerGlobalReceiver(context: Context) {
        if (receiverRegistered) {
            return
        }
        val runtimeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val broadcast = intent ?: return
                HikPdaScanBridge.applyScannerBroadcast(broadcast)
            }
        }
        ContextCompat.registerReceiver(
            context,
            runtimeReceiver,
            IntentFilter(HikPdaScanBridge.STOCK_RESULT_ACTION).apply {
                addAction(HikPdaScanBridge.RESULT_ACTION)
                addAction(HikPdaScanBridge.ACTION_TEST_RESULT)
                addAction(HikPdaScanBridge.ACTION_DEFAULT_SCAN_CONFIG)
                addAction(HikPdaScanBridge.ACTION_DEFAULT_OUTPUT_CONFIG)
                addAction(HikPdaScanBridge.ACTION_DEFAULT_IMAGE_PARAM)
                addAction(HikPdaScanBridge.ACTION_DEFAULT_RUN_STATUS)
                addAction(HikPdaScanBridge.ACTION_STATUS_SERVICE_TO_ASSIST)
                addAction(HikPdaScanBridge.ACTION_CAMERA_INIT_COMPLETE)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiver = runtimeReceiver
        receiverRegistered = true
        Log.i(TAG, "Global PDA receiver registered")
    }

    private fun bindService(context: Context) {
        if (serviceConnection != null) {
            return
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                bound = true
                Log.i(TAG, "PDA service bound: ${name?.className}")
                HikPdaScanBridge.prepareRuntime(context)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bound = false
                Log.w(TAG, "PDA service disconnected: ${name?.className}")
            }
        }
        val intent = Intent(SERVICE_BIND_ACTION).apply {
            setPackage(HikPdaScanBridge.PDA_SERVICE_PACKAGE)
        }
        val didBind = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        serviceConnection = connection
        bound = didBind
        Log.i(TAG, "bindService result=$didBind")
    }
}
