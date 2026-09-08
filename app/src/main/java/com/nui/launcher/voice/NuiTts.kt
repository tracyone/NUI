package com.nui.launcher.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsCallback
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.nui.launcher.NuiToast
import java.io.File

/**
 * NUI 通用离线语音服务。
 *
 * 基于内嵌 sherpa-onnx + Piper 中文模型（14MB int8 量化），免 root、免外部引擎、
 * 完全离线，兼容 Android 9。任何场景（天气、导航、按键反馈等）都可直接调用：
 *
 * ```
 * NuiTts(context).speak("你好")
 * ```
 *
 * 内置两套音色（女声小雅 / 男声超文），随 APK 打包、零下载，可在设置中切换；
 * 切换的瞬间会用新音色播报自我介绍。所有实例共享同一个引擎（单例核心），
 * 车机内存只占一份模型，模型懒加载、后台线程合成与播放。
 */
class NuiTts(context: Context) {
    private val core = NuiTtsCore.apply { ensure(context) }

    /** 合成并播放一段文本（后台线程，自动顶掉当前播报） */
    fun speak(text: String) = core.speak(text)

    /** 后台预热引擎：提前加载语音模型，避免首次播报/点击时长时间等待 */
    fun warmUp() = core.warmUp()

    /** 切换音色：立即停止当前播放，加载新音色并播报自我介绍 */
    fun switchVoice(gender: String) = core.switchVoice(gender)

    /** 停止当前播报 */
    fun stop() = core.stop()

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

/** 单例核心：全局共享同一个 sherpa-onnx 引擎，各场景只占一份模型 */
private object NuiTtsCore {
    private const val TAG = "NuiTts"
    private const val TOKENS_FILE = "tokens.txt"
    private const val LEXICON_FILE = "lexicon.txt"

    /** 内置音色：男声（超文）/ 女声（小雅），均为 14MB int8 离线模型，随 APK 打包，零下载 */
    private data class VoiceSpec(val dir: String, val modelFile: String, val selfName: String)
    private val MALE_SPEC = VoiceSpec("zh_CN-chaowen", "zh_CN-chaowen-medium.onnx", "超文")
    private val FEMALE_SPEC = VoiceSpec("zh_CN-xiao_ya", "zh_CN-xiao_ya-medium.onnx", "小雅")

    private fun specFor(gender: String): VoiceSpec =
        if (gender == NuiTts.VOICE_MALE) MALE_SPEC else FEMALE_SPEC

    private lateinit var appContext: Context

    // 以下状态仅在后台线程（worker）上读写，无需加锁
    private var tts: OfflineTts? = null
    private var loadedSpec: VoiceSpec? = null
    private var failNotified = false

    /** 已加载过的引擎按音色缓存，切换后不释放：第二次切换同一音色立即出声，无需重新加载模型 */
    private val engineCache = HashMap<String, OfflineTts>()

    /** 打断标志：置 true 后正在合成的播报在下一段回调处提前停止（新播报/切换优先）。 */
    @Volatile
    private var generationCancelled = false

    @Volatile
    private var currentTrack: AudioTrack? = null

    private val worker: HandlerThread by lazy {
        HandlerThread("nui-tts").apply { start() }
    }
    private val handler: Handler by lazy { Handler(worker.looper) }

    fun ensure(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    fun speak(text: String) {
        // 新播报优先：打断正在合成的旧播报，避免长播报占线导致后续播报排队等十几秒。
        // 注意：requestInterrupt 置位后，本任务不再检查 flag（flag 由 speakNow 重置），
        // 排队期间被更新的播报打断由"track.stop + 回调见 flag 提前结束"保证。
        requestInterrupt()
        handler.post {
            if (tts == null && !initEngine()) return@post
            speakNow(text)
        }
    }

    /** 后台预热引擎：立即在 worker 线程初始化，后续 speak 无需再等待 */
    fun warmUp() {
        handler.post {
            if (tts == null) initEngine()
        }
    }

    /** 打断当前播报（UI 线程可调用）：置标志 + 停 AudioTrack。
     *  正在 write 的线程会因 track.stop() 立即返回，合成回调见标志后提前停止，
     *  worker 队列即可继续执行后续任务。 */
    fun requestInterrupt() {
        generationCancelled = true
        runCatching { currentTrack?.stop() }
        runCatching { currentTrack?.flush() }
    }

    fun stop() {
        requestInterrupt()
        handler.removeCallbacksAndMessages(null)
        currentTrack = null
    }

    /** 切换音色：目标音色已加载过则立即切换并播报；否则先出声反馈，
     *  加载完成后缓存（不释放旧引擎），下次切换同音色秒切。 */
    fun switchVoice(gender: String) {
        // 切换优先：先打断正在播的旧播报，再排队切换
        requestInterrupt()
        handler.post {
            val target = specFor(gender)
            // 已是目标音色：直接播报，无需重载
            if (loadedSpec?.dir == target.dir && tts != null) {
                speakNow("主人好，我是${target.selfName}")
                return@post
            }
            // 目标音色已缓存：立即切换出声
            engineCache[target.dir]?.let { cached ->
                tts = cached
                loadedSpec = target
                speakNow("主人好，我是${target.selfName}")
                return@post
            }
            // 切换瞬间立即出声反馈（当前引擎），避免长时间静默
            if (tts != null) {
                runCatching { speakNow("正在切换语音") }
            }
            stopPlayback()
            if (!loadEngine(target)) return@post
            Log.i(TAG, "音色已切换：${target.dir}")
            speakNow("主人好，我是${target.selfName}")
        }
    }

    // ---------- 后台线程执行 ----------

    /** 加载（或复用缓存）指定音色引擎 */
    private fun loadEngine(spec: VoiceSpec): Boolean {
        engineCache[spec.dir]?.let {
            tts = it
            loadedSpec = spec
            return true
        }
        if (!initEngineFor(spec)) return false
        engineCache[spec.dir] = tts!!
        loadedSpec = spec
        return true
    }

    /** 首次使用时初始化引擎；失败时提示一次 */
    private fun initEngine(): Boolean {
        val spec = specFor(NuiTts.voiceGender(appContext))
        return loadEngine(spec)
    }

    private fun initEngineFor(spec: VoiceSpec): Boolean {
        return try {
            val dir = extractModel(spec.dir)
            val vits = OfflineTtsVitsModelConfig.builder()
                .setModel(File(dir, spec.modelFile).absolutePath)
                .setTokens(File(dir, TOKENS_FILE).absolutePath)
                .setLexicon(File(dir, LEXICON_FILE).absolutePath)
                .setLengthScale(0.85f)
                .setNoiseScale(0.667f)
                .setNoiseScaleW(0.8f)
                .build()
            val model = OfflineTtsModelConfig.builder()
                .setVits(vits)
                .setNumThreads(4)
                .setDebug(false)
                .setProvider("cpu")
                .build()
            val config = OfflineTtsConfig.builder()
                .setModel(model)
                .setRuleFsts(ruleFsts(dir))
                .setMaxNumSentences(1)
                .build()
            tts = OfflineTts(config)
            Log.i(TAG, "sherpa-onnx 引擎就绪：${spec.dir} (${tts!!.sampleRate} Hz)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "内置语音初始化失败: ${t.message}")
            notifyFail()
            false
        }
    }

    /** 将 assets 中的模型解压到 filesDir（仅首次） */
    private fun extractModel(dirName: String): File {
        val dir = File(appContext.filesDir, dirName)
        if (!dir.exists()) dir.mkdirs()
        val names = appContext.assets.list(dirName) ?: emptyArray()
        for (name in names) {
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) continue
            try {
                appContext.assets.open("$dirName/$name").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "模型文件 $name 解压失败: ${t.message}")
            }
        }
        return dir
    }

    /** 把 date/number/phone.fst 拼成 ruleFsts（存在才拼） */
    private fun ruleFsts(dir: File): String {
        val present = listOf("date.fst", "number.fst", "phone.fst")
            .mapNotNull { name -> val f = File(dir, name); if (f.isFile) f.absolutePath else null }
        return present.joinToString(",")
    }

    private fun speakNow(text: String) {
        stopPlayback()
        val engine = tts ?: return
        val sr = engine.sampleRate
        if (sr <= 0) return
        val minBuf = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = try {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC, sr,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                minBuf, AudioTrack.MODE_STREAM
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack 创建失败: ${t.message}")
            return
        }
        currentTrack = track
        generationCancelled = false
        try {
            track.play()
            // 首段音频开始写入 = 出声时刻（测试打点：T1/T2 的端点）
            Log.i(TAG, "audio_start")
            // 流式合成：回调按段返回音频，边生成边写入 AudioTrack 播放，
            // 首段完成后立即出声，无需等整段文本全部合成完
            engine.generateWithCallback(text, OfflineTtsCallback { samples ->
                if (generationCancelled) {
                    0 // 被打断：停止合成
                } else if (samples.isNotEmpty()) {
                    val n = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    if (n < 0) {
                        generationCancelled = true
                        0
                    } else {
                        1
                    }
                } else {
                    1
                }
            })
            // 全部音频写完并播放完（测试打点：一段播报的结束时刻）
            Log.i(TAG, "audio_end")
        } catch (t: Throwable) {
            Log.w(TAG, "播放失败: ${t.message}")
        }
        runCatching { track.stop() }
        runCatching { track.release() }
        if (currentTrack === track) currentTrack = null
    }

    private fun stopPlayback() {
        runCatching { currentTrack?.pause() }
        runCatching { currentTrack?.flush() }
        currentTrack = null
    }

    private fun notifyFail() {
        if (failNotified) return
        failNotified = true
        Handler(Looper.getMainLooper()).post {
            NuiToast.show(appContext, "语音播报不可用（内置语音初始化失败）", Toast.LENGTH_SHORT)
        }
    }
}
