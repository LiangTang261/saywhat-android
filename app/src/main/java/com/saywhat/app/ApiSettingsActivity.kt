package com.saywhat.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.saywhat.app.data.FeatureFlags
import com.saywhat.app.data.Provider
import com.saywhat.app.data.Providers
import com.saywhat.app.data.Settings
import com.saywhat.app.engine.EngineHub
import com.saywhat.app.engine.EngineResult
import com.saywhat.app.engine.SubtextState
import java.util.concurrent.Executors

/**
 * 填写 AI 接口页。
 *
 * ═══ 为什么单独开一个页面 ═══
 * 用户拿到 Key 之后，最容易被「接口地址」和「模型名」两个空框卡住 ——
 * 他不知道该填什么，然后就放弃了。
 *
 * 所以这一页的核心是**服务商预设**：选一下，地址和模型名自动填好，
 * 用户实际只需要粘贴一个 Key。地址与模型名折叠在下面「一般不用改」的区域里，
 * 保留给用中转站的高级用户。
 */
class ApiSettingsActivity : Activity() {

    private lateinit var settings: Settings
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var spEngine: Spinner
    private lateinit var spProvider: Spinner
    private lateinit var tvGuide: TextView
    private lateinit var etKey: EditText
    private lateinit var etBaseUrl: EditText
    private lateinit var etModel: EditText
    private lateinit var btnToggleShow: Button
    private lateinit var tvResult: TextView

    private var keyVisible = false

    /** 初始化 spinner 时会触发一次回调，用它压住，免得把已存的地址冲掉 */
    private var suppressProviderEvent = false

    private val providerNames: List<String> get() = Providers.ALL.map { it.name }
    private val engineIds: List<String> get() = EngineHub.available.map { it.first }
    private val engineNames: List<String> get() = EngineHub.available.map { it.second }

    private fun currentProvider(): Provider {
        val idx = spProvider.selectedItemPosition
        return Providers.ALL.getOrElse(idx) { Providers.DEEPSEEK }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_settings)

        settings = Settings(this)

        spEngine = findViewById(R.id.sp_engine)
        spProvider = findViewById(R.id.sp_provider)
        tvGuide = findViewById(R.id.tv_guide)
        etKey = findViewById(R.id.et_key)
        etBaseUrl = findViewById(R.id.et_base_url)
        etModel = findViewById(R.id.et_model)
        btnToggleShow = findViewById(R.id.btn_toggle_show)
        tvResult = findViewById(R.id.tv_result)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        setupEngineSpinner()
        setupProviderSpinner()
        applyFeatureVisibility()
        loadFromSettings()

        btnToggleShow.setOnClickListener { toggleKeyVisibility() }
        findViewById<Button>(R.id.btn_save_only).setOnClickListener {
            save()
            toast(getString(R.string.api_saved))
            finish()
        }
        findViewById<Button>(R.id.btn_save_test).setOnClickListener { saveAndTest() }

        tvGuide.setOnClickListener {
            val url = currentProvider().signupUrl
            if (url.isNotEmpty()) openUrl(url)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    // ── 初始化 ─────────────────────────────────────────────

    private fun setupEngineSpinner() {
        val adapter = ArrayAdapter(this, R.layout.item_spinner, engineNames)
        adapter.setDropDownViewResource(R.layout.item_spinner)
        spEngine.adapter = adapter
        val idx = engineIds.indexOf(settings.engineId)
        spEngine.setSelection(if (idx >= 0) idx else 0)
    }

    /**
     * 0.1.0 只留 DeepSeek 一条路，所以引擎与服务商两个下拉都收起来。
     * 只给一个选项的下拉框纯属干扰 —— 用户会以为还有别的选择。
     * 地址与模型名保留可见，作为万一默认值失效时的逃生口。
     */
    private fun applyFeatureVisibility() {
        val showEngine = FeatureFlags.showChooser(engineNames.size)
        val engineViews = listOf(
            findViewById<View>(R.id.section_engine),
            spEngine,
            findViewById<View>(R.id.tv_engine_hint)
        )
        for (v in engineViews) v.visibility = if (showEngine) View.VISIBLE else View.GONE

        val showProvider = FeatureFlags.showChooser(providerNames.size)
        val providerViews = listOf(
            findViewById<View>(R.id.section_provider),
            spProvider
        )
        for (v in providerViews) v.visibility = if (showProvider) View.VISIBLE else View.GONE
    }

    private fun setupProviderSpinner() {
        val adapter = ArrayAdapter(this, R.layout.item_spinner, providerNames)
        adapter.setDropDownViewResource(R.layout.item_spinner)
        spProvider.adapter = adapter

        val savedId = settings.providerId
        val idx = Providers.ALL.indexOfFirst { it.id == savedId }
        suppressProviderEvent = true
        spProvider.setSelection(if (idx >= 0) idx else 0)
        suppressProviderEvent = false

        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (suppressProviderEvent) return
                applyProvider(Providers.ALL[position], overwrite = true)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        applyProvider(currentProvider(), overwrite = false)
    }

    /**
     * 把预设填进输入框。
     * @param overwrite true = 用户主动切换了服务商，覆盖；false = 首次进入，只在空的时候补
     */
    private fun applyProvider(p: Provider, overwrite: Boolean) {
        tvGuide.text = p.guide
        tvGuide.isClickable = p.signupUrl.isNotEmpty()
        // 有官网链接时给一点视觉提示
        tvGuide.setTextColor(
            if (p.signupUrl.isNotEmpty()) 0xFF7DD3FC.toInt() else 0xFF9BA1A6.toInt()
        )
        etKey.hint = p.keyHint

        if (overwrite || etBaseUrl.text.isNullOrBlank()) {
            if (p.baseUrl.isNotEmpty()) etBaseUrl.setText(p.baseUrl)
        }
        if (overwrite || etModel.text.isNullOrBlank()) {
            if (p.model.isNotEmpty()) etModel.setText(p.model)
        }
    }

    private fun loadFromSettings() {
        etKey.setText(settings.apiKey)
        etBaseUrl.setText(settings.baseUrl)
        etModel.setText(settings.model)
    }

    private fun toggleKeyVisibility() {
        keyVisible = !keyVisible
        val cursor = etKey.selectionEnd
        etKey.inputType = if (keyVisible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        btnToggleShow.text = getString(
            if (keyVisible) R.string.action_hide else R.string.action_show
        )
        etKey.setSelection(cursor)
    }

    // ── 保存与测试 ─────────────────────────────────────────

    private fun save() {
        val p = currentProvider()
        settings.providerId = p.id
        settings.apiKey = etKey.text.toString().trim()
        settings.baseUrl = etBaseUrl.text.toString().trim()
            .ifBlank { p.baseUrl.ifBlank { Settings.DEFAULT_BASE } }
        settings.model = etModel.text.toString().trim()
            .ifBlank { p.model.ifBlank { Settings.DEFAULT_MODEL } }

        val enginePos = spEngine.selectedItemPosition
        if (enginePos in engineIds.indices) settings.engineId = engineIds[enginePos]

        // 回填，让用户看到最终生效的值
        etBaseUrl.setText(settings.baseUrl)
        etModel.setText(settings.model)
    }

    private fun saveAndTest() {
        save()

        if (settings.apiKey.isBlank()) {
            showResult("请先粘贴你的 API Key", ok = false)
            etKey.requestFocus()
            return
        }

        showResult(getString(R.string.api_testing), ok = null)

        worker.execute {
            val engine = EngineHub.create(settings)
            val result = engine.analyze(SubtextState(herMessage = "在吗"))
            main.post {
                when (result) {
                    is EngineResult.Ok -> {
                        val c = result.card
                        showResult(
                            "✅ 接口可用\n模型：${c.engine}\n耗时：${c.elapsedMs} ms\n" +
                                    "试跑判定：${c.scene}" +
                                    (c.advice?.let { "\n建议：$it" } ?: ""),
                            ok = true
                        )
                    }
                    is EngineResult.Fail -> showResult("❌ ${result.message}", ok = false)
                }
            }
        }
    }

    private fun showResult(text: String, ok: Boolean?) {
        tvResult.visibility = View.VISIBLE
        tvResult.text = text
        tvResult.setTextColor(
            when (ok) {
                true -> 0xFF4ADE80.toInt()
                false -> 0xFFF87171.toInt()
                null -> 0xFF9BA1A6.toInt()
            }
        )
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast("打不开浏览器，请手动访问 $url")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
