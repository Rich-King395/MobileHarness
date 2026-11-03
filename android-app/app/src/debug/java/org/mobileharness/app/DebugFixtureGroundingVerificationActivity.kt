package org.mobileharness.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import org.json.JSONObject
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.verification.FixtureGroundingVerification
import java.io.File

/** Explicit DUMP-protected debug entry; exports fixed predicates and counters only. */
class DebugFixtureGroundingVerificationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var verifier: FixtureGroundingVerification
    private lateinit var status: TextView
    private var startedAt = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        verifier = FixtureGroundingVerification(applicationContext)
        status = TextView(this).apply {
            textSize = 18f
            text = "合成页面定位诊断\n最多一次 GUI 请求；当前合成截图发送至已配置服务。原始模型点按与独立中心点控制分别核验，不保存画面、回复或坐标。"
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 100, 40, 50)
            addView(status)
            addView(Button(this@DebugFixtureGroundingVerificationActivity).apply {
                text = "停止诊断"
                setOnClickListener { HarnessAccessibilityService.instance?.controls?.stop(); job?.cancel() }
            })
        })
        runVerification()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (job?.isActive != true) runVerification()
    }
    private fun runVerification() {
        if (job?.isActive == true) return
        val mode = when (intent.getStringExtra("mode")) {
            "LAST_TASK_HOME_PROBE" -> FixtureGroundingVerification.Mode.LAST_TASK_HOME_PROBE
            "UNABLE_PROBE" -> FixtureGroundingVerification.Mode.UNABLE_PROBE
            "FINISH" -> FixtureGroundingVerification.Mode.FINISH
            "LOCAL_RENDER" -> FixtureGroundingVerification.Mode.LOCAL_RENDER
            else -> FixtureGroundingVerification.Mode.TAP
        }
        if (mode == FixtureGroundingVerification.Mode.LOCAL_RENDER) {
            status.text = "本地截图绘制核验\n仅采集一次合成页面画面，在内存检查固定背景；不调用模型、不派发点按、不保存画面、坐标或色值。"
        } else if (mode == FixtureGroundingVerification.Mode.UNABLE_PROBE) {
            status.text = "模型结束原因诊断\n一次合成页面截图发送至当前 GUI 服务；一次请求，不执行动作，不重试。仅保存固定诊断分类，不保存画面、回复或密钥。"
        } else if (mode == FixtureGroundingVerification.Mode.LAST_TASK_HOME_PROBE) {
            status.text = "最近失败任务的首轮诊断\n仅在内存读取本地原任务，一次桌面截图发送至当前 GUI 服务；不执行返回动作、不重试、不新增实验记录。仅保存固定诊断分类。"
        }
        startedAt = SystemClock.elapsedRealtime()
        write(FixtureGroundingVerification.Result(mode = mode.name))
        job = scope.launch(Dispatchers.Default) {
            try { verifier.run(mode) { write(it) } }
            catch (cancelled: CancellationException) {
                write(verifier.latest.copy(status = "CANCELLED", code = "CANCELLED"))
                throw cancelled
            } catch (_: Exception) { write(verifier.latest.copy(status = "FAIL", code = "DIAGNOSTIC_FAILURE")) }
            withContext(Dispatchers.Main.immediate) {
                status.text = "合成页面定位诊断\n${verifier.latest.status} · ${verifier.latest.code}\n单步结果不代表多步任务成功。"
            }
        }
    }
    @Synchronized private fun write(value: FixtureGroundingVerification.Result) {
        val payload = JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("prompt_bundle_version", "1.2.0").put("status", value.status).put("code", value.code)
            .put("adapter_version", value.adapterVersion ?: JSONObject.NULL)
            .put("mode", value.mode)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - startedAt)
            .put("gui_attempts", value.guiAttempts).put("planner_attempts", 0)
            .put("screenshot_calls", value.screenshotCalls)
            .put("model_perform_calls", value.modelPerformCalls).put("control_perform_calls", value.controlPerformCalls)
            .put("phone_perform_calls", value.modelPerformCalls + value.controlPerformCalls)
            .put("model_code", value.modelCode).put("flags", JSONObject(value.flags))
            .put("response_outcome", value.responseOutcome).put("model_stated_reason", value.statedReason)
            .put("finish_message_chars", value.finishMessageChars ?: JSONObject.NULL)
            .put("protocol", JSONObject(value.protocol))
        val file = AtomicFile(File(noBackupFilesDir, "fixture-grounding-verification.json"))
        val stream = file.startWrite()
        try { stream.write(payload.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
