package io.agora.agora_rtc_engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.NonNull
import io.agora.iris.base.IrisEventHandler
import io.agora.iris.rtc.IrisRtcEngine
import io.agora.rtc.RtcEngine
import io.agora.rtc.base.RtcEngineRegistry
import io.flutter.BuildConfig
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.*
import io.flutter.plugin.platform.PlatformViewRegistry
import kotlin.math.abs

internal class EventHandler(private val eventSink: EventChannel.EventSink?) : IrisEventHandler {
    private val handler = Handler(Looper.getMainLooper())

    override fun OnEvent(event: String?, data: String?) {
        handler.post {
            eventSink?.success(mapOf("methodName" to event, "data" to data))
        }
    }

    override fun OnEvent(event: String?, data: String?, buffer: ByteArray?) {
        handler.post {
            eventSink?.success(mapOf("methodName" to event, "data" to data, "buffer" to buffer))
        }
    }
}

open class CallApiMethodCallHandler(
    protected val irisRtcEngine: IrisRtcEngine
) : MethodChannel.MethodCallHandler {

    protected open fun callApi(apiType: Int, params: String?, sb: StringBuffer): Int {
        val ret = irisRtcEngine.callApi(apiType, params, sb)
        if (apiType == 0) {
            RtcEngineRegistry.instance.onRtcEngineCreated(irisRtcEngine.rtcEngine as RtcEngine?)
        }
        if (apiType == 1) {
            RtcEngineRegistry.instance.onRtcEngineDestroyed()
        }
        return ret
    }

    protected open fun callApiWithBuffer(
        apiType: Int,
        params: String?,
        buffer: ByteArray?,
        sb: StringBuffer
    ): Int {
        return irisRtcEngine.callApi(apiType, params, buffer, sb)
    }

    protected open fun callApiError(ret: Int): String {
        val description = StringBuffer()
        irisRtcEngine.callApi(
            132,
            "{\"code\":${abs(ret)}}",
            description
        )
        return description.toString()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val apiType = call.argument<Int>("apiType")
        val params = call.argument<String>("params")
        val sb = StringBuffer()

        if (BuildConfig.DEBUG) {
            when (call.method) {
                "getIrisRtcEngineIntPtr" -> {
                    result.success(irisRtcEngine.nativeHandle)
                    return
                }
                "forceDestroyIrisRtcEngine" -> {
                    irisRtcEngine.destroy()
                    result.success(true)
                    return
                }
            }
        }

        try {
            val ret = when (call.method) {
                "callApi" -> callApi(apiType!!, params, sb)
                "callApiWithBuffer" -> {
                    val buffer = call.argument<ByteArray>("buffer")
                    callApiWithBuffer(apiType!!, params, buffer, sb)
                }
                else -> -1
            }

            when {
                ret == 0 -> {
                    if (sb.isEmpty()) {
                        result.success(null)
                    } else {
                        result.success(sb.toString())
                    }
                }
                ret > 0 -> result.success(ret)
                else -> {
                    val errorMsg = callApiError(ret)
                    result.error(ret.toString(), errorMsg, null)
                }
            }
        } catch (e: Exception) {
            result.error("", e.message ?: "", null)
        }
    }
}

class AgoraRtcEnginePlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private lateinit var applicationContext: Context
    private lateinit var irisRtcEngine: IrisRtcEngine
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rtcChannelPlugin: AgoraRtcChannelPlugin
    private lateinit var callApiMethodCallHandler: CallApiMethodCallHandler
    private lateinit var binding: FlutterPlugin.FlutterPluginBinding

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        binding = flutterPluginBinding
        applicationContext = binding.applicationContext
        initPlugin()
    }

    private fun initPlugin() {
        irisRtcEngine = IrisRtcEngine(applicationContext)
        methodChannel = MethodChannel(binding.binaryMessenger, "agora_rtc_engine")
        methodChannel.setMethodCallHandler(this)
        eventChannel = EventChannel(binding.binaryMessenger, "agora_rtc_engine/events")
        eventChannel.setStreamHandler(this)

        callApiMethodCallHandler = CallApiMethodCallHandler(irisRtcEngine)

        binding.platformViewRegistry.registerViewFactory(
            "AgoraSurfaceView",
            AgoraSurfaceViewFactory(binding.binaryMessenger, irisRtcEngine)
        )
        binding.platformViewRegistry.registerViewFactory(
            "AgoraTextureView",
            AgoraTextureViewFactory(binding.binaryMessenger, irisRtcEngine)
        )

        rtcChannelPlugin = AgoraRtcChannelPlugin(irisRtcEngine)
        rtcChannelPlugin.initPlugin(binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        rtcChannelPlugin.onDetachedFromEngine(binding)
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        irisRtcEngine.destroy()
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        irisRtcEngine.setEventHandler(EventHandler(eventSink))
    }

    override fun onCancel(arguments: Any?) {
        irisRtcEngine.setEventHandler(null)
        eventSink = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "createTextureRender",
            "destroyTextureRender" -> {
                result.notImplemented()
            }
            "getAssetAbsolutePath" -> {
                getAssetAbsolutePath(call, result)
            }
            else -> {
                callApiMethodCallHandler.onMethodCall(call, result)
            }
        }
    }

    private fun getAssetAbsolutePath(call: MethodCall, result: MethodChannel.Result) {
        call.arguments<String>()?.let { assetName ->
            val assetKey = binding.flutterAssets.getAssetFilePathByName(assetName)
            try {
                applicationContext.assets.openFd(assetKey).close()
                result.success("/assets/$assetKey")
            } catch (e: Exception) {
                result.error(e.javaClass.simpleName, e.message, null)
            }
        } ?: result.error(
            "IllegalArgumentException",
            "The parameter should not be null",
            null
        )
    }
}
