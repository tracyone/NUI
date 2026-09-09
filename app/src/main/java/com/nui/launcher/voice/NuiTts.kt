package com.nui.launcher.voice

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import com.nui.launcher.NuiToast
import java.util.Locale

/**
 * NUI 通用语音服务（系统语音唯一通道）。
 *
 * 使用 Android 原生 TextToSpeech，引擎由系统/车机预装（如 Pico / 厂商引擎），
 * 免模型加载、响应快，适合低性能车机。内置两套音色偏好（女声小雅 / 男声超文，
 * 以系统引擎声音为准），可在设置中切换；切换瞬间播报自我介绍。
 *
 * 引擎不可用 / 无中文语音包时给出一次性提示，不做任何引擎切换（无备用引擎）。
 * 任何场景（天气、导航、按键反馈等）都可直接调用：
 * ```
 * NuiTts(context).speak("你好")
 * ```
 */
class NuiTts(context: Context) {
    private val core = NuiTtsCore.apply { ensure(context) }

    /** 合成并播放一段文本（后台线程，自动顶掉当前播报） */
    fun speak(text: String) = core.speak(text)

    /** 后台预热引擎：提前绑定系统 TTS 引擎，避免首次播报/点击时长时间等待 */
    fun warmUp() = core.warmUp()

    /** 切换音色：立即停止当前播放，用新音色播报自我介绍 */
    fun switchVoice(gender: String) = core.switchVoice(gender)

    /** 停止当前播报 */
    fun stop() = core.stop()

    /** 系统 TTS 引擎是否支持指定音色（male/female）。
     *  引擎未就绪返回 null（未知，调用方应等待后重查）；引擎不可用返回 false；就绪后按实际 voices 判定。 */
    fun supportsVoice(gender: String): Boolean? = core.supportsVoice(gender)

    /** 系统 TTS 引擎可用状态：null=初始化中（未知），true=可用，false=不可用（无引擎/绑定失败/超时） */
    fun isSystemAvailable(): Boolean? = core.isSystemAvailable()

    companion object {
        private const val PREFS = "nui_voice"
        private const val KEY_VOICE = "voice_gender"
        const val VOICE_FEMALE = "female"
        const val VOICE_MALE = "male"

        /** 当前选中的音色 */
        fun voiceGender(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_VOICE, VOICE_FEMALE) ?: VOICE_FEMALE

        /** 保存音色选择 */
        fun setVoiceGender(context: Context, gender: String) {
            val v = if (gender == VOICE_MALE) VOICE_MALE else VOICE_FEMALE
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_VOICE, v).apply()
        }
    }
}

/** 单例核心：全局共享同一份系统 TTS 引擎，各场景只占一份 */
private object NuiTtsCore {
    private const val TAG = "NuiTts"
    private const val BIND_TIMEOUT_MS = 5000L

    /** 音色偏好 → 自我介绍用名 */
    private fun selfNameFor(gender: String): String =
        if (gender == NuiTts.VOICE_MALE) "超文" else "小雅"

    private lateinit var appContext: Context

    // ---------- 系统引擎（Android TextToSpeech）状态 ----------
    private var sysTts: TextToSpeech? = null

    @Volatile
    private var sysReady = false

    /** 引擎就绪前排队的播报文本（单条，新文本覆盖旧的） */
    @Volatile
    private var sysPending: String? = null

    @Volatile
    private var sysInitFailed = false

    @Volatile
    private var noZhNotified = false

    @Volatile
    private var failNotified = false

    /** 引擎就绪后的声音列表缓存（"语言|名称"字符串，避开 TextToSpeech.Voice 泛型解析问题）：
     *  供 supportsVoice 能力检测（就绪前为 null=未知） */
    @Volatile
    private var sysVoices: List<String>? = null

    private val worker: HandlerThread by lazy {
        HandlerThread("nui-tts").apply { start() }
    }
    private val handler: Handler by lazy { Handler(worker.looper) }

    fun ensure(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    // ---------- 对外接口 ----------

    fun speak(text: String) {
        val t = sysTts
        if (t == null) {
            sysPending = text
            handler.post { initSystemTts() }
            return
        }
        if (!sysReady) {
            sysPending = text
            return
        }
        sysPending = null
        speakWithSystem(t, text)
    }

    /** 后台预热引擎：绑定系统 TTS 引擎（秒级） */
    fun warmUp() {
        if (sysTts == null) handler.post { initSystemTts() }
    }

    fun stop() {
        runCatching { sysTts?.stop() }
    }

    /** 切换音色：立即用新音色播报自我介绍 */
    fun switchVoice(gender: String) {
        speak("主人好，我是${selfNameFor(gender)}")
    }

    /** 系统引擎是否含指定性别音色（zh 优先，name 含 gender 关键词，与 applySystemVoice 同规则）。
     *  引擎不可用返回 false；初始化中（voices 未知）返回 null。 */
    fun supportsVoice(gender: String): Boolean? {
        if (sysInitFailed) return false
        val voices = sysVoices ?: return null
        val zh = voices.filter { it.startsWith("zh|") }
        val pool = if (zh.isNotEmpty()) zh else voices
        return pool.any { it.substringAfter("|").contains(gender, true) }
    }

    /** 引擎可用状态：初始化中 null、就绪 true、失败/超时 false */
    fun isSystemAvailable(): Boolean? = when {
        sysInitFailed -> false
        sysReady -> true
        else -> null
    }

    // ---------- 系统引擎实现（Android TextToSpeech） ----------

    /** 初始化系统 TTS 引擎。必须在 worker 线程调用（回调同线程，就绪后可立即出声）。
     *  引擎绑定可能超时/失败（设备无系统引擎），5 秒未就绪判定不可用并提示一次。 */
    private fun initSystemTts() {
        if (sysTts != null) return
        Log.i(TAG, "初始化系统语音引擎")
        var created: TextToSpeech? = null
        created = try {
            TextToSpeech(appContext) { status ->
                val t = created ?: sysTts ?: return@TextToSpeech
                if (status == TextToSpeech.SUCCESS) {
                    // 标准做法：初始化时就设置中文并校验支持（避免每次 speak 重复 setLanguage）
                    val lang = t.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    val noZh = lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED
                    // 显式语速/音调（默认 1.0，与引擎行为对齐）
                    t.setSpeechRate(1.0f)
                    t.setPitch(1.0f)
                    val langs = runCatching { t.availableLanguages }.getOrNull()
                    sysVoices = runCatching { t.voices.map { "${it.locale?.language ?: ""}|${it.name}" } }
                        .getOrNull().orEmpty()
                    Log.i(TAG, "系统语音就绪：${t.voice?.name} 可用语言=${langs?.map { it.toString() } ?: "null"} 中文=${!noZh} 声音数=${sysVoices?.size ?: 0}")
                    sysReady = true
                    applySystemVoice()
                    if (noZh && !noZhNotified) {
                        noZhNotified = true
                        Handler(Looper.getMainLooper()).post {
                            NuiToast.show(appContext, "系统语音无中文语音包，无法播报", Toast.LENGTH_SHORT)
                        }
                    }
                    sysPending?.let { p ->
                        sysPending = null
                        speakWithSystem(t, p)
                    }
                } else {
                    Log.w(TAG, "系统语音初始化失败: $status")
                    sysInitFailed = true
                    notifyUnavailable()
                }
            }
        } catch (t0: Throwable) {
            Log.w(TAG, "系统语音创建异常: ${t0.message}")
            sysInitFailed = true
            notifyUnavailable()
            null
        }
        sysTts = created
        // 超时保护：引擎绑定一直不回回调（设备无系统 TTS 引擎）时，5 秒后判定不可用
        if (created != null) {
            handler.postDelayed({
                if (!sysReady && !sysInitFailed && sysTts === created) {
                    Log.w(TAG, "系统语音引擎绑定超时")
                    sysInitFailed = true
                    notifyUnavailable()
                }
            }, BIND_TIMEOUT_MS)
        }
    }

    /** 用已就绪的系统引擎播报（语言已在初始化时校验设置，speak 不再重复 setLanguage）。
     *  通过 UtteranceProgressListener.onStart 打点「点击→出声」耗时。 */
    private fun speakWithSystem(t: TextToSpeech, text: String) {
        runCatching {
            val t0 = System.currentTimeMillis()
            val uttId = "nui_$t0"
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    if (id == uttId) Log.i(TAG, "系统语音出声耗时 ${System.currentTimeMillis() - t0}ms")
                }
                override fun onDone(id: String?) {}
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {}
            })
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, uttId)
        }
    }

    /** 按当前音色偏好选择系统引擎的声音（zh 优先，name 含 female/male 匹配，找不到用默认） */
    private fun applySystemVoice() {
        val t = sysTts ?: return
        val want = if (NuiTts.voiceGender(appContext) == NuiTts.VOICE_MALE) "male" else "female"
        val voices = runCatching { t.voices }.getOrNull().orEmpty()
        if (voices.isEmpty()) return
        val zh = voices.filter { it.locale?.language.equals("zh", true) }
        val pool = if (zh.isNotEmpty()) zh else voices
        val target = pool.firstOrNull { it.name.contains(want, true) }
            ?: pool.firstOrNull()
        if (target != null && target != t.voice) {
            runCatching { t.voice = target }
            Log.i(TAG, "系统语音音色：${target.name}")
        }
    }

    /** 引擎不可用提示（一次） */
    private fun notifyUnavailable() {
        if (failNotified) return
        failNotified = true
        Handler(Looper.getMainLooper()).post {
            NuiToast.show(appContext, "系统语音不可用", Toast.LENGTH_SHORT)
        }
    }
}
