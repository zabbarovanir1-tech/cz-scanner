package ru.czcheck.scanner

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var store: HistoryStore
    private lateinit var adapter: HistoryAdapter
    private val client = CrptClient()
    private val items = ArrayList<ScanItem>()

    private val netExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null

    private lateinit var card: View
    private lateinit var cardTitle: TextView
    private lateinit var cardProduct: TextView
    private lateinit var cardDetails: TextView
    private lateinit var cardCode: TextView
    private lateinit var input: EditText
    private lateinit var counters: TextView
    private lateinit var listView: ListView

    private var shownItem: ScanItem? = null
    private var receiverRegistered = false
    private var lastSubmitCode: String? = null
    private var lastSubmitAt = 0L
    private var firstInputAt = 0L
    private var lastInputAt = 0L
    private var nextId = 1L

    private val timeFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())

    /** Сканер ТСД в режиме Intent/Broadcast */
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val data = ScannerIntents.extract(intent) ?: return
            submit(data)
        }
    }

    /** Сканер в режиме клавиатуры без Enter: отправляем по паузе после быстрого ввода */
    private val autoSubmitTask = Runnable {
        val t = input.text.toString()
        if (t.length >= 20) {
            val perChar = (lastInputAt - firstInputAt) / t.length
            if (perChar <= 40) submitFromInput()
        }
    }

    private val saveTask = Runnable { saveHistoryNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = Settings(this)
        store = HistoryStore(File(filesDir, "history.json"))

        card = findViewById(R.id.card)
        cardTitle = findViewById(R.id.cardTitle)
        cardProduct = findViewById(R.id.cardProduct)
        cardDetails = findViewById(R.id.cardDetails)
        cardCode = findViewById(R.id.cardCode)
        input = findViewById(R.id.input)
        counters = findViewById(R.id.counters)
        listView = findViewById(R.id.list)

        items.addAll(store.load())
        nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1

        adapter = HistoryAdapter(this, items)
        listView.adapter = adapter
        listView.itemsCanFocus = false
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in items.indices) showDetails(items[position])
        }

        card.setOnClickListener { shownItem?.let { showDetails(it) } }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        findViewById<Button>(R.id.btnCheck).setOnClickListener { submitFromInput() }
        findViewById<Button>(R.id.btnKeyboard).setOnClickListener { showKeyboard() }

        setupInput()
        updateCounters()
        items.firstOrNull()?.let { show(it) }

        handleLaunchIntent(intent)
    }

    private fun setupInput() {
        // Экранная клавиатура не выскакивает сама — данные приходят от сканера
        input.showSoftInputOnFocus = false

        input.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_NEXT || isEnter
            ) {
                if (event == null || event.action == KeyEvent.ACTION_DOWN) submitFromInput()
                true
            } else {
                false
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                main.removeCallbacks(autoSubmitTask)
                val t = s?.toString() ?: ""
                if (t.isEmpty()) {
                    firstInputAt = 0L
                    return
                }
                val now = SystemClock.uptimeMillis()
                if (firstInputAt == 0L) firstInputAt = now
                lastInputAt = now
                if (t.indexOf('\n') >= 0 || t.indexOf('\r') >= 0) {
                    main.post { submitFromInput() }
                    return
                }
                if (settings.autoSubmit) main.postDelayed(autoSubmitTask, 300)
            }
        })
        input.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val k = event.keyCode
        if ((k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_TAB) && input.hasFocus()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && input.text.isNotEmpty()) {
                submitFromInput()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(scanReceiver, ScannerIntents.filter(), Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(scanReceiver, ScannerIntents.filter())
                }
                receiverRegistered = true
            } catch (e: Exception) {
                // без приёма Intent продолжит работать режим клавиатуры
            }
        }
        input.requestFocus()
    }

    override fun onPause() {
        if (receiverRegistered) {
            try { unregisterReceiver(scanReceiver) } catch (e: Exception) {}
            receiverRegistered = false
        }
        main.removeCallbacks(saveTask)
        saveHistoryNow()
        super.onPause()
    }

    override fun onDestroy() {
        tone?.release()
        tone = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    /** Zebra DataWedge и др. в режиме «Start activity» */
    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == ScannerIntents.OWN_ACTION) {
            ScannerIntents.extract(intent)?.let { submit(it) }
        }
    }

    // ---------------------------------------------------------------- сканирование

    private fun submitFromInput() {
        main.removeCallbacks(autoSubmitTask)
        val t = input.text.toString()
        input.setText("")
        firstInputAt = 0L
        hideKeyboard()
        input.requestFocus()
        // если в поле попало несколько кодов подряд — проверяем каждый
        t.split('\n', '\r').filter { it.isNotBlank() }.forEach { submit(it) }
    }

    private fun submit(raw: String) {
        val n = CodeNormalizer.normalize(raw, settings.restoreGs, settings.fixLayout)
        if (n.code.isEmpty()) return

        // один и тот же скан мог прийти двумя путями (Intent + клавиатура)
        val now = SystemClock.uptimeMillis()
        if (n.code == lastSubmitCode && now - lastSubmitAt < 1500) return
        lastSubmitCode = n.code
        lastSubmitAt = now

        val key = n.cis ?: n.code
        val repeat = items.any { (it.cis ?: it.code) == key }
        val item = ScanItem(nextId++, System.currentTimeMillis(), raw, n.code, n.gtin, n.serial, n.notes, repeat)
        items.add(0, item)
        while (items.size > HistoryStore.MAX_ITEMS) items.removeAt(items.size - 1)
        show(item)
        adapter.notifyDataSetChanged()
        listView.setSelection(0)
        updateCounters()

        if (n.kind == CodeNormalizer.Kind.EAN) {
            item.level = Level.WARN
            item.title = "Это обычный штрихкод"
            item.details = listOf(
                "Штрихкод" to n.code,
                "Подсказка" to "Нужен квадратный код DataMatrix (код маркировки)"
            )
            onChecked(item)
            return
        }
        runCheck(item, n)
    }

    private fun recheck(item: ScanItem) {
        val n = CodeNormalizer.normalize(item.code, restoreGs = false, fixLayout = false)
        item.level = Level.PENDING
        item.title = "Проверяю…"
        if (shownItem?.id == item.id) show(item)
        adapter.notifyDataSetChanged()
        runCheck(item, n)
    }

    private fun recheckFailed() {
        val failed = items.filter { it.level == Level.ERROR }
        if (failed.isEmpty()) {
            toast("Нет непроверенных кодов")
            return
        }
        failed.forEach { recheck(it) }
        toast("Перепроверяю: ${failed.size}")
    }

    private fun runCheck(item: ScanItem, n: CodeNormalizer.Normalized) {
        item.level = Level.PENDING
        item.title = "Проверяю…"
        val cfg = CrptClient.Config(settings.apiMode, settings.baseUrl, settings.trustAllSsl, settings.workingVariant)
        netExecutor.execute {
            val r = client.check(n, cfg)
            val verdict = if (r.error == null) ResultEvaluator.evaluate(r.httpCode, r.body) else null
            main.post {
                if (r.isSuccess && r.note == null && settings.apiMode == ApiMode.AUTO && settings.workingVariant != r.variant) {
                    settings.workingVariant = r.variant
                }
                item.httpCode = r.httpCode
                item.requestInfo = buildRequestInfo(r)
                item.response = r.body
                if (verdict == null) {
                    item.level = Level.ERROR
                    item.product = null
                    item.statusCode = null
                    if (r.errorKind == CrptClient.ErrorKind.SSL) {
                        item.title = "Ошибка SSL-сертификата"
                        item.details = listOf(
                            "Причина" to (r.error ?: ""),
                            "Что делать" to "Проверьте дату и время на ТСД. Если не поможет — включите в настройках «Не проверять SSL-сертификат»."
                        )
                    } else {
                        item.title = "Не удалось проверить"
                        item.details = listOf(
                            "Причина" to (r.error ?: ""),
                            "Что делать" to "Проверьте интернет и нажмите на строку → «Перепроверить»"
                        )
                    }
                } else {
                    item.level = verdict.level
                    item.title = verdict.title
                    if (r.foundByCisOnly && verdict.level == Level.OK) {
                        // статус известен, но код проверки (криптохвост) сервер не подтвердил
                        item.level = Level.WARN
                        item.title = "В обороте, но код проверки не подтверждён"
                    }
                    item.product = verdict.product
                    item.statusCode = verdict.statusCode
                    item.details = if (r.note != null) listOf("Примечание" to r.note) + verdict.details else verdict.details
                }
                onChecked(item)
            }
        }
    }

    private fun buildRequestInfo(r: CrptClient.Response): String {
        val sb = StringBuilder()
        sb.append(r.variant.title).append('\n').append(r.url)
        r.requestBody?.let { sb.append('\n').append(it) }
        if (r.attempts.size > 1) {
            sb.append("\n\nВсе запросы (").append(r.attempts.size).append("):")
            r.attempts.forEachIndexed { i, a ->
                sb.append("\n").append(i + 1).append(") ").append(a.label).append(" → ")
                sb.append(a.error ?: ("HTTP " + a.httpCode))
                a.requestBody?.let { sb.append("\n   отправлено: ").append(it) }
                a.body?.let { sb.append("\n   ответ: ").append(it.replace('\n', ' ').take(300)) }
            }
        }
        return sb.toString()
    }

    private fun onChecked(item: ScanItem) {
        if (shownItem?.id == item.id) show(item)
        adapter.notifyDataSetChanged()
        updateCounters()
        feedback(item.level)
        scheduleSave()
    }

    // ---------------------------------------------------------------- отображение

    private fun show(item: ScanItem) {
        shownItem = item
        card.setBackgroundColor(LevelColors.of(item.level))
        val fg = if (item.level == Level.WARN) Color.parseColor("#212121") else Color.WHITE
        cardTitle.setTextColor(fg)
        cardProduct.setTextColor(fg)
        cardDetails.setTextColor(fg)
        cardCode.setTextColor(fg)

        cardTitle.text = item.title
        val product = item.product
        if (product.isNullOrBlank()) {
            cardProduct.visibility = View.GONE
        } else {
            cardProduct.visibility = View.VISIBLE
            cardProduct.text = product
        }

        val lines = ArrayList<String>()
        if (item.repeat) lines.add("Этот код уже сканировали")
        item.details
            .filter { !(it.first == "Статус" && item.level == Level.OK) }
            .take(5)
            .forEach { lines.add("${it.first}: ${it.second}") }
        cardDetails.text = lines.joinToString("\n")
        cardDetails.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        cardCode.text = item.cis ?: CodeNormalizer.display(item.code)
    }

    private fun updateCounters() {
        val ok = items.count { it.level == Level.OK }
        val bad = items.count { it.level == Level.BAD }
        val other = items.size - ok - bad
        counters.text = "Всего: ${items.size}    зелёных: $ok    красных: $bad    прочих: $other"
    }

    private fun showDetails(item: ScanItem) {
        val sb = StringBuilder()
        item.product?.let { sb.append(it).append("\n\n") }
        for ((k, v) in item.details) sb.append(k).append(": ").append(v).append('\n')
        if (item.repeat) sb.append("Повторное сканирование\n")
        sb.append("\nВремя: ").append(timeFmt.format(Date(item.time))).append('\n')
        item.cis?.let { sb.append("КИ: ").append(it).append('\n') }
        sb.append("Код: ").append(CodeNormalizer.display(item.code)).append('\n')
        if (item.raw.trim() != item.code) sb.append("От сканера: ").append(CodeNormalizer.display(item.raw.trim())).append('\n')
        if (item.notes.isNotEmpty()) sb.append("Исправления: ").append(item.notes.joinToString(", ")).append('\n')
        item.requestInfo?.let { sb.append("\nЗапрос: ").append(CodeNormalizer.display(it)).append('\n') }
        if (item.httpCode != 0) sb.append("HTTP: ").append(item.httpCode).append('\n')
        item.response?.let { sb.append("\nОтвет сервера:\n").append(pretty(it).take(8000)) }

        val tv = TextView(this)
        tv.text = sb.toString()
        tv.setTextIsSelectable(true)
        tv.textSize = 13f
        tv.setTextColor(Color.parseColor("#212121"))
        val pad = (16 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad / 2, pad, pad / 2)
        val scroll = ScrollView(this)
        scroll.addView(tv)

        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setView(scroll)
            .setPositiveButton("Перепроверить") { _, _ -> recheck(item) }
            .setNeutralButton("Отправить") { _, _ -> shareText("Проверка ЧЗ: " + item.title, item.title + "\n" + sb.toString()) }
            .setNegativeButton("Закрыть", null)
            .setOnDismissListener { input.requestFocus() }
            .show()
    }

    private fun pretty(s: String): String = try {
        JSONObject(s).toString(2)
    } catch (e: Exception) {
        s
    }

    // ---------------------------------------------------------------- меню

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Отправить список")
        pm.menu.add(0, 2, 1, "Перепроверить серые")
        pm.menu.add(0, 3, 2, "Очистить историю")
        pm.menu.add(0, 4, 3, "Настройки")
        pm.menu.add(0, 5, 4, "Как подключить сканер")
        pm.menu.add(0, 6, 5, "Завершить работу")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> exportList()
                2 -> recheckFailed()
                3 -> confirmClear()
                4 -> showSettings()
                5 -> showHelp()
                6 -> showFarewell()
            }
            true
        }
        pm.setOnDismissListener { input.requestFocus() }
        pm.show()
    }

    private fun exportList() {
        if (items.isEmpty()) {
            toast("Список пуст")
            return
        }
        val sb = StringBuilder("Время;Результат;Статус;Товар;GTIN;Серийный номер;КИ\n")
        for (s in items.asReversed()) {
            val row = listOf(
                timeFmt.format(Date(s.time)), s.title, s.statusCode ?: "", s.product ?: "",
                s.gtin ?: "", s.serial ?: "", s.cis ?: CodeNormalizer.display(s.code)
            )
            sb.append(row.joinToString(";") { csv(it) }).append('\n')
        }
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_SUBJECT, "Проверка ЧЗ " + timeFmt.format(Date()))
        send.putExtra(Intent.EXTRA_TEXT, sb.toString())
        try {
            startActivity(Intent.createChooser(send, "Отправить список"))
        } catch (e: Exception) {
            toast("Нет приложения, чтобы отправить список")
        }
    }

    private fun shareText(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_SUBJECT, subject)
        send.putExtra(Intent.EXTRA_TEXT, text)
        try {
            startActivity(Intent.createChooser(send, "Отправить"))
        } catch (e: Exception) {
            copyToClipboard(text)
        }
    }

    private fun csv(s: String): String =
        if (s.contains(';') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Очистить историю?")
            .setMessage("Будут удалены все ${items.size} записей.")
            .setPositiveButton("Очистить") { _, _ ->
                items.clear()
                shownItem = null
                adapter.notifyDataSetChanged()
                updateCounters()
                card.setBackgroundColor(LevelColors.IDLE)
                cardTitle.setTextColor(Color.WHITE)
                cardTitle.text = "Отсканируйте код"
                cardProduct.visibility = View.GONE
                cardDetails.visibility = View.GONE
                cardCode.text = ""
                scheduleSave()
            }
            .setNegativeButton("Отмена", null)
            .setOnDismissListener { input.requestFocus() }
            .show()
    }

    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val rg = v.findViewById<RadioGroup>(R.id.rgApi)
        val ids = mapOf(
            ApiMode.AUTO to R.id.rbAuto,
            ApiMode.V2_POST to R.id.rbV2,
            ApiMode.V1_POST to R.id.rbV1Post,
            ApiMode.V1_GET to R.id.rbV1Get
        )
        rg.check(ids[settings.apiMode] ?: R.id.rbAuto)
        val etUrl = v.findViewById<EditText>(R.id.etBaseUrl)
        etUrl.setText(settings.baseUrl)
        val cbAuto = v.findViewById<CheckBox>(R.id.cbAutoSubmit)
        val cbGs = v.findViewById<CheckBox>(R.id.cbRestoreGs)
        val cbLayout = v.findViewById<CheckBox>(R.id.cbLayout)
        val cbSound = v.findViewById<CheckBox>(R.id.cbSound)
        val cbVibrate = v.findViewById<CheckBox>(R.id.cbVibrate)
        val cbTrust = v.findViewById<CheckBox>(R.id.cbTrustAll)
        cbAuto.isChecked = settings.autoSubmit
        cbGs.isChecked = settings.restoreGs
        cbLayout.isChecked = settings.fixLayout
        cbSound.isChecked = settings.sound
        cbVibrate.isChecked = settings.vibrate
        cbTrust.isChecked = settings.trustAllSsl

        val working = settings.workingVariant
        v.findViewById<TextView>(R.id.tvVersion).text =
            "Версия " + appVersion() +
                (if (working != null) "\nСработал способ: " + working.title else "")

        AlertDialog.Builder(this)
            .setTitle("Настройки")
            .setView(v)
            .setPositiveButton("Сохранить") { _, _ ->
                val mode = ids.entries.firstOrNull { it.value == rg.checkedRadioButtonId }?.key ?: ApiMode.AUTO
                if (mode != settings.apiMode) settings.workingVariant = null
                settings.apiMode = mode
                val url = etUrl.text.toString().trim()
                val newUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else Settings.DEFAULT_BASE_URL
                if (newUrl.trimEnd('/') != settings.baseUrl) settings.workingVariant = null
                settings.baseUrl = newUrl
                settings.autoSubmit = cbAuto.isChecked
                settings.restoreGs = cbGs.isChecked
                settings.fixLayout = cbLayout.isChecked
                settings.sound = cbSound.isChecked
                settings.vibrate = cbVibrate.isChecked
                settings.trustAllSsl = cbTrust.isChecked
                toast("Сохранено")
            }
            .setNegativeButton("Отмена", null)
            .setOnDismissListener { input.requestFocus() }
            .show()
    }

    private fun showHelp() {
        val text = """
            |Цвета
            |• Зелёный — код в обороте, всё в порядке.
            |• Красный — код не найден, не введён в оборот, уже выбыл (продан/списан), заблокирован или истёк срок годности.
            |• Жёлтый — код найден, но нужен ручной контроль (откройте подробности).
            |• Серый — не удалось проверить (нет интернета, ошибка сервера). Меню → «Перепроверить серые».
            |
            |Подключение сканера ТСД
            |
            |1. Режим клавиатуры (проще всего)
            |В настройках сканера включите вывод «Клавиатура / Keyboard / Эмуляция клавиатуры» и суффикс Enter. Приложение само поймает код.
            |
            |2. Режим Intent / Broadcast (надёжнее: не теряются символы GS)
            |Urovo, Newland, Mertech, АТОЛ, iData, Chainway, Sunmi, CipherLab, Point Mobile и др. поддерживаются автоматически — достаточно выбрать вывод «Broadcast / Intent».
            |Если ТСД не определился, укажите в настройках сканера:
            |  Action: ${ScannerIntents.OWN_ACTION}
            |  Extra (ключ данных): data
            |
            |Zebra (DataWedge): профиль для приложения ru.czcheck.scanner → Intent output: ON, Intent action: ${ScannerIntents.OWN_ACTION}, Intent delivery: Broadcast intent. Keystroke output можно выключить.
            |
            |Проверка идёт через тот же сервер, что у приложения «Честный знак», и работает только через российский интернет.
        """.trimMargin()
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.setTextIsSelectable(true)
        tv.setTextColor(Color.parseColor("#212121"))
        val pad = (18 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad / 2, pad, pad / 2)
        val scroll = ScrollView(this)
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Справка")
            .setView(scroll)
            .setPositiveButton("Понятно", null)
            .setOnDismissListener { input.requestFocus() }
            .show()
    }

    // ---------------------------------------------------------------- служебное

    private fun feedback(level: Level) {
        if (settings.sound) {
            try {
                val tg = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 100).also { tone = it }
                when (level) {
                    Level.OK -> tg.startTone(ToneGenerator.TONE_PROP_ACK, 250)
                    Level.BAD -> tg.startTone(ToneGenerator.TONE_PROP_NACK, 600)
                    else -> tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
                }
            } catch (e: Exception) {
                // звук недоступен
            }
        }
        if (settings.vibrate && level != Level.OK && level != Level.PENDING) {
            vibrate(if (level == Level.BAD) 450L else 120L)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(ms)
            }
        } catch (e: Exception) {
            // нет вибромотора
        }
    }

    private fun showKeyboard() {
        input.requestFocus()
        input.showSoftInputOnFocus = true
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, 0)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        input.showSoftInputOnFocus = false
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Код маркировки", text))
        toast("Скопировано")
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveTask)
        main.postDelayed(saveTask, 1500)
    }

    private fun saveHistoryNow() {
        val snapshot = ArrayList(items)
        ioExecutor.execute { store.write(store.toJsonString(snapshot)) }
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- завершение работы

    private var closing = false

    /** Кнопка «Назад» на главном экране — завершение работы с Тигрулей. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        showFarewell()
    }

    /** Экран «Тигруля прав!» на 2 секунды, затем приложение закрывается. Касание — закрыть сразу. */
    private fun showFarewell() {
        if (closing) return
        closing = true
        main.removeCallbacks(saveTask)
        saveHistoryNow()

        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#2E7D32"))
        root.setPadding(dp(24), dp(24), dp(24), dp(24))

        val tiger = ImageView(this)
        tiger.setImageResource(R.drawable.tiger)
        tiger.adjustViewBounds = true
        root.addView(tiger, LinearLayout.LayoutParams(dp(200), dp(264)))

        val title = TextView(this)
        title.text = "Тигруля прав!"
        title.textSize = 34f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        title.setTextColor(Color.WHITE)
        title.gravity = Gravity.CENTER
        val tp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        tp.topMargin = dp(16)
        root.addView(title, tp)

        val sub = TextView(this)
        sub.text = "Смена окончена"
        sub.textSize = 17f
        sub.setTextColor(Color.parseColor("#C8E6C9"))
        sub.gravity = Gravity.CENTER
        root.addView(sub, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val dlg = Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        dlg.setContentView(root)
        dlg.setCancelable(false)
        root.setOnClickListener { closeApp(dlg) }
        try {
            dlg.show()
        } catch (e: Exception) {
            finish()
            return
        }
        main.postDelayed({ closeApp(dlg) }, 2000)
    }

    private fun closeApp(dlg: Dialog) {
        if (isFinishing) return
        try { dlg.dismiss() } catch (e: Exception) {}
        finish()
    }
}
