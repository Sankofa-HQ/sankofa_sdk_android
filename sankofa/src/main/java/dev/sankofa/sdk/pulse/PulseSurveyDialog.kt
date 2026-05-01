package dev.sankofa.sdk.pulse

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import java.io.ByteArrayOutputStream

/**
 * Programmatic-View survey renderer. We deliberately avoid Compose
 * + Material so the SDK stays drop-in for any host (no transitive
 * deps, minSdk 21). Looks plain by design — the operator's theme
 * config (passed via PulseSurvey.theme) tunes accent + bg colors.
 *
 * Layout shape:
 *
 *   ┌──────────────────────────────────┐
 *   │ Survey name              [✕]    │
 *   │ Description (if any)            │
 *   │ ━━━━━━━━━━━━━━━━━━━━            │ progress bar
 *   │                                  │
 *   │ Question prompt                 │
 *   │ Helptext                        │
 *   │ [ per-kind input area ]         │
 *   │                                  │
 *   │ [ Back ]              [ Next ]  │
 *   └──────────────────────────────────┘
 *
 * Lifecycle: caller creates the dialog with a survey and two
 * callbacks (onSubmit + onDismiss). The dialog drives the
 * back/next/submit state machine and assembles the
 * PulseSubmitPayload on submit.
 */
internal class PulseSurveyDialog(
    context: Context,
    private val survey: PulseSurvey,
    private val branchingRules: List<dev.sankofa.sdk.pulse.branching.PulseBranchingRule> = emptyList(),
    initialAnswers: Map<String, Any?> = emptyMap(),
    initialQuestionId: String? = null,
    private val onProgress: (answers: Map<String, Any?>, currentQuestionId: String) -> Unit = { _, _ -> },
    private val onSubmit: (PulseSubmitPayload) -> Unit,
    private val onDismiss: () -> Unit,
) : Dialog(context) {

    private val sortedQuestions = survey.questions.sortedBy { it.orderIndex }
    private val answers: MutableMap<String, Any?> =
        initialAnswers.toMutableMap()

    /**
     * Stack of indices the user has visited; used to retrace
     * Back across skip-logic jumps. We push on every forward step
     * (whether a fall-through or a branching jump) and pop on Back.
     */
    private val history: ArrayDeque<Int> = ArrayDeque()

    private var currentIndex = run {
        if (initialQuestionId != null) {
            val target = sortedQuestions.indexOfFirst { it.id == initialQuestionId }
            if (target >= 0) target else 0
        } else 0
    }
    private lateinit var promptText: TextView
    private lateinit var helptext: TextView
    private lateinit var inputContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var backButton: Button
    private lateinit var nextButton: Button
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(true)
        setOnCancelListener { onDismiss() }
        setContentView(buildRoot())
        bindHeader()
        renderCurrent()
    }

    // ── Layout building ──────────────────────────────────────────

    private fun buildRoot(): View {
        val ctx = context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        // Header
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(ctx).apply {
            text = survey.name
            textSize = 16f
            setTextColor(Color.parseColor("#18181B"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        titleColumn.addView(title)
        survey.description?.takeIf { it.isNotBlank() }?.let {
            titleColumn.addView(TextView(ctx).apply {
                text = it
                textSize = 12f
                setTextColor(Color.parseColor("#71717A"))
            })
        }
        headerRow.addView(titleColumn)

        val close = Button(ctx).apply {
            text = "×"
            textSize = 18f
            background = null
            setTextColor(Color.parseColor("#71717A"))
            setOnClickListener { onDismiss(); dismiss() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        headerRow.addView(close)
        root.addView(headerRow)

        progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = sortedQuestions.size.coerceAtLeast(1)
            progress = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4),
            ).also { it.topMargin = dp(8) }
        }
        root.addView(progressBar)

        // Body — scrollable
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also {
                it.topMargin = dp(12)
                it.bottomMargin = dp(12)
            }
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        promptText = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#18181B"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        helptext = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#71717A"))
            visibility = View.GONE
        }
        inputContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(8) }
        }
        body.addView(promptText)
        body.addView(helptext)
        body.addView(inputContainer)
        scroll.addView(body)
        root.addView(scroll)

        errorText = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#DC2626"))
            visibility = View.GONE
        }
        root.addView(errorText)

        // Footer
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(8) }
        }
        backButton = Button(ctx).apply { text = "Back" }
        backButton.setOnClickListener { goBack() }
        nextButton = Button(ctx).apply { text = "Next" }
        nextButton.setOnClickListener { goForward() }

        footer.addView(backButton, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(View(ctx), LinearLayout.LayoutParams(dp(8),
            ViewGroup.LayoutParams.WRAP_CONTENT))
        footer.addView(nextButton, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(footer)
        return root
    }

    private fun bindHeader() {
        progressBar.max = sortedQuestions.size.coerceAtLeast(1)
    }

    // ── State machine ────────────────────────────────────────────

    private fun current(): PulseQuestion? =
        if (currentIndex in sortedQuestions.indices) sortedQuestions[currentIndex] else null

    private fun renderCurrent() {
        val q = current() ?: return
        promptText.text = q.prompt
        helptext.text = q.helptext.orEmpty()
        helptext.visibility = if (q.helptext.isNullOrBlank()) View.GONE else View.VISIBLE
        progressBar.progress = currentIndex + 1
        inputContainer.removeAllViews()
        renderInput(q)
        errorText.visibility = View.GONE
        backButton.isEnabled = history.isNotEmpty()
        nextButton.text = if (currentIndex == sortedQuestions.lastIndex) "Submit" else "Next"
    }

    private fun goBack() {
        if (history.isNotEmpty()) {
            currentIndex = history.removeLast()
            renderCurrent()
        }
    }

    private fun goForward() {
        val q = current() ?: return
        if (q.required && !hasAnswer(q.id)) {
            errorText.text = "This question is required."
            errorText.visibility = View.VISIBLE
            return
        }
        // Ask the branching evaluator first. The Outcome can:
        //  - end the survey early (sentinel)
        //  - jump to a target question id
        //  - fall through (next by order_index)
        val outcome = dev.sankofa.sdk.pulse.branching.PulseBranching.resolveNext(
            rules = branchingRules,
            currentQuestionId = q.id,
            answers = answers,
        )
        when {
            outcome.nextQuestionId == dev.sankofa.sdk.pulse.branching.PULSE_BRANCHING_END_OF_SURVEY -> {
                submit()
                return
            }
            outcome.nextQuestionId.isNotEmpty() -> {
                val target = sortedQuestions.indexOfFirst { it.id == outcome.nextQuestionId }
                if (target >= 0) {
                    history.addLast(currentIndex)
                    currentIndex = target
                    renderCurrent()
                    emitProgress()
                    return
                }
                // Target id not found in this survey — fall through
                // rather than getting stuck. A "skip to a question
                // that no longer exists" is a survey-builder error,
                // not something to crash the host on.
            }
        }
        // Fall-through advance.
        if (currentIndex == sortedQuestions.lastIndex) {
            submit()
        } else {
            history.addLast(currentIndex)
            currentIndex += 1
            renderCurrent()
            emitProgress()
        }
    }

    private fun emitProgress() {
        val q = current() ?: return
        runCatching { onProgress(answers.toMap(), q.id) }
    }

    private fun submit() {
        val map = LinkedHashMap<String, Any?>(sortedQuestions.size)
        for (q in sortedQuestions) {
            val v = answers[q.id] ?: continue
            map[q.id] = v
        }
        val payload = PulseSubmitPayload(
            surveyId = survey.id,
            answers = map,
        )
        onSubmit(payload)
        dismiss()
    }

    private fun hasAnswer(qid: String): Boolean {
        val v = answers[qid] ?: return false
        return when (v) {
            is String -> v.isNotBlank()
            is Collection<*> -> v.isNotEmpty()
            is Map<*, *> -> v.isNotEmpty()
            else -> true
        }
    }

    // ── Per-kind input renderers ─────────────────────────────────

    private fun renderInput(q: PulseQuestion) {
        when (q.kind) {
            "short_text" -> renderShortText(q)
            "long_text"  -> renderLongText(q)
            "number"     -> renderNumber(q)
            "rating"     -> renderRating(q)
            "nps"        -> renderNps(q)
            "single", "image_choice" -> renderSingle(q)
            "multi"      -> renderMulti(q)
            "boolean"    -> renderBoolean(q)
            "slider"     -> renderSlider(q)
            "date"       -> renderDate(q)
            "statement"  -> { /* read-only */ }
            "ranking"    -> renderRanking(q)
            "matrix"     -> renderMatrix(q)
            "consent"    -> renderConsent(q)
            "maxdiff"    -> renderMaxDiff(q)
            "signature"  -> renderSignature(q)
            "file", "payment" -> renderUnsupported(q)
            else -> renderUnsupported(q)
        }
    }

    private fun renderShortText(q: PulseQuestion) {
        val input = EditText(context)
        input.inputType = InputType.TYPE_CLASS_TEXT
        (answers[q.id] as? String)?.let { input.setText(it) }
        input.addTextChangedListener(simpleWatcher { answers[q.id] = it.ifBlank { null } })
        inputContainer.addView(input)
    }

    private fun renderLongText(q: PulseQuestion) {
        val input = EditText(context)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.minLines = 3
        input.gravity = Gravity.TOP or Gravity.START
        (answers[q.id] as? String)?.let { input.setText(it) }
        input.addTextChangedListener(simpleWatcher { answers[q.id] = it.ifBlank { null } })
        inputContainer.addView(input)
    }

    private fun renderNumber(q: PulseQuestion) {
        val input = EditText(context)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        (answers[q.id] as? Number)?.let { input.setText(it.toString()) }
        input.addTextChangedListener(simpleWatcher {
            answers[q.id] = it.toDoubleOrNull()
        })
        inputContainer.addView(input)
    }

    private fun renderRating(q: PulseQuestion) {
        val min = (q.validation?.get("min") as? Number)?.toInt() ?: 1
        val max = (q.validation?.get("max") as? Number)?.toInt() ?: 5
        val current = (answers[q.id] as? Number)?.toInt() ?: 0
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in min..max) {
            val star = Button(context).apply {
                text = if (current >= n) "★" else "☆"
                textSize = 22f
                background = null
                setTextColor(Color.parseColor(if (current >= n) "#F59E0B" else "#A1A1AA"))
                setOnClickListener {
                    answers[q.id] = n
                    inputContainer.removeAllViews()
                    renderRating(q)
                }
            }
            row.addView(star)
        }
        inputContainer.addView(row)
    }

    private fun renderNps(q: PulseQuestion) {
        val current = (answers[q.id] as? Number)?.toInt() ?: -1
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in 0..10) {
            val cell = Button(context).apply {
                text = n.toString()
                textSize = 12f
                setTextColor(if (current == n) Color.WHITE else Color.parseColor("#18181B"))
                setBackgroundColor(if (current == n) accentColor() else Color.parseColor("#F4F4F5"))
                setOnClickListener {
                    answers[q.id] = n
                    inputContainer.removeAllViews()
                    renderNps(q)
                }
            }
            row.addView(cell, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        inputContainer.addView(row)
        val anchorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val left = TextView(context).apply {
            text = "Not at all"
            textSize = 11f
            setTextColor(Color.parseColor("#71717A"))
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val right = TextView(context).apply {
            text = "Extremely"
            textSize = 11f
            gravity = Gravity.END
            setTextColor(Color.parseColor("#71717A"))
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        anchorRow.addView(left)
        anchorRow.addView(right)
        inputContainer.addView(anchorRow)
    }

    private fun renderSingle(q: PulseQuestion) {
        val group = RadioGroup(context).apply { orientation = LinearLayout.VERTICAL }
        val current = answers[q.id] as? String
        q.options?.forEach { opt ->
            val rb = RadioButton(context).apply {
                text = opt.label
                isChecked = current == opt.key
                setOnClickListener { answers[q.id] = opt.key }
            }
            group.addView(rb)
        }
        inputContainer.addView(group)
    }

    private fun renderMulti(q: PulseQuestion) {
        @Suppress("UNCHECKED_CAST")
        val current = (answers[q.id] as? List<String>)?.toMutableSet() ?: mutableSetOf()
        q.options?.forEach { opt ->
            val cb = CheckBox(context).apply {
                text = opt.label
                isChecked = current.contains(opt.key)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) current.add(opt.key) else current.remove(opt.key)
                    answers[q.id] = current.toList()
                }
            }
            inputContainer.addView(cb)
        }
    }

    private fun renderBoolean(q: PulseQuestion) {
        val group = RadioGroup(context).apply { orientation = LinearLayout.HORIZONTAL }
        val current = answers[q.id] as? Boolean
        val yes = RadioButton(context).apply {
            text = "Yes"; isChecked = current == true
            setOnClickListener { answers[q.id] = true }
        }
        val no = RadioButton(context).apply {
            text = "No"; isChecked = current == false
            setOnClickListener { answers[q.id] = false }
        }
        group.addView(yes); group.addView(no)
        inputContainer.addView(group)
    }

    private fun renderSlider(q: PulseQuestion) {
        val min = (q.validation?.get("min") as? Number)?.toInt() ?: 0
        val max = (q.validation?.get("max") as? Number)?.toInt() ?: 100
        val current = (answers[q.id] as? Number)?.toInt() ?: min
        val seek = SeekBar(context).apply {
            this.max = max - min
            progress = (current - min).coerceAtLeast(0)
        }
        val readout = TextView(context).apply {
            text = current.toString(); textSize = 12f
            setTextColor(Color.parseColor("#71717A"))
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val v = p + min
                answers[q.id] = v.toDouble()
                readout.text = v.toString()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        inputContainer.addView(seek)
        inputContainer.addView(readout)
    }

    private fun renderDate(q: PulseQuestion) {
        val input = EditText(context)
        input.hint = "YYYY-MM-DD"
        (answers[q.id] as? String)?.let { input.setText(it) }
        input.addTextChangedListener(simpleWatcher { answers[q.id] = it.ifBlank { null } })
        inputContainer.addView(input)
    }

    private fun renderRanking(q: PulseQuestion) {
        // Minimal ranking UI: number-prefixed list with up/down
        // arrows. Drag-to-reorder skipped for v1 — bumps via
        // buttons keeps this implementation under 30 lines.
        val ordered =
            (answers[q.id] as? List<*>)
                ?.mapNotNull { key ->
                    val k = key as? String ?: return@mapNotNull null
                    q.options?.firstOrNull { it.key == k }
                }
                ?.toMutableList()
                ?: q.options?.toMutableList()
                ?: mutableListOf()

        fun publish() {
            answers[q.id] = ordered.map { it.key }
        }
        publish()

        fun rerender() {
            inputContainer.removeAllViews()
            renderRanking(q)
        }

        ordered.forEachIndexed { idx, opt ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val numLabel = TextView(context).apply {
                text = "${idx + 1}."
                setPadding(dp(4), dp(8), dp(8), dp(8))
                setTextColor(Color.parseColor("#71717A"))
            }
            val label = TextView(context).apply {
                text = opt.label
                layoutParams = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val up = Button(context).apply {
                text = "▲"; isEnabled = idx > 0
                setOnClickListener {
                    if (idx > 0) {
                        ordered.removeAt(idx).also { ordered.add(idx - 1, it) }
                        publish(); rerender()
                    }
                }
            }
            val down = Button(context).apply {
                text = "▼"; isEnabled = idx < ordered.lastIndex
                setOnClickListener {
                    if (idx < ordered.lastIndex) {
                        ordered.removeAt(idx).also { ordered.add(idx + 1, it) }
                        publish(); rerender()
                    }
                }
            }
            row.addView(numLabel)
            row.addView(label)
            row.addView(up)
            row.addView(down)
            inputContainer.addView(row)
        }
    }

    private fun renderMatrix(q: PulseQuestion) {
        @Suppress("UNCHECKED_CAST")
        val rowsRaw = q.validation?.get("rows") as? List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val colsRaw = q.validation?.get("columns") as? List<Map<String, Any?>>
        if (rowsRaw == null || colsRaw == null) {
            renderUnsupported(q); return
        }
        @Suppress("UNCHECKED_CAST")
        val picks =
            (answers[q.id] as? Map<String, String>)?.toMutableMap() ?: mutableMapOf()

        rowsRaw.forEach { row ->
            val rk = row["key"] as? String ?: return@forEach
            val rl = row["label"] as? String ?: rk
            val rowLabel = TextView(context).apply {
                text = rl
                textSize = 12f
                setTextColor(Color.parseColor("#71717A"))
            }
            inputContainer.addView(rowLabel)

            val group = RadioGroup(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            colsRaw.forEach { col ->
                val ck = col["key"] as? String ?: return@forEach
                val cl = col["label"] as? String ?: ck
                val rb = RadioButton(context).apply {
                    text = cl
                    isChecked = picks[rk] == ck
                    setOnClickListener {
                        picks[rk] = ck
                        answers[q.id] = picks.toMap()
                    }
                }
                group.addView(rb)
            }
            inputContainer.addView(group)
        }
    }

    private fun renderConsent(q: PulseQuestion) {
        val cb = CheckBox(context).apply {
            text = q.helptext ?: "I agree."
            isChecked = answers[q.id] == true
            setOnCheckedChangeListener { _, checked ->
                answers[q.id] = if (checked) true else null
            }
        }
        inputContainer.addView(cb)
    }

    private fun renderMaxDiff(q: PulseQuestion) {
        @Suppress("UNCHECKED_CAST")
        val current = (answers[q.id] as? Map<String, String>)?.toMutableMap()
            ?: mutableMapOf()

        fun publish() {
            if (current["best"] != null && current["worst"] != null) {
                answers[q.id] = current.toMap()
            } else {
                answers[q.id] = null
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(TextView(context).apply {
            text = "Best"; textSize = 11f
            setTextColor(Color.parseColor("#71717A"))
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(context).apply {
            text = ""
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        })
        header.addView(TextView(context).apply {
            text = "Worst"; textSize = 11f; gravity = Gravity.END
            setTextColor(Color.parseColor("#71717A"))
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        inputContainer.addView(header)

        q.options?.forEach { opt ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val bestRb = RadioButton(context).apply {
                isChecked = current["best"] == opt.key
                isEnabled = current["worst"] != opt.key
                setOnClickListener {
                    current["best"] = opt.key
                    if (current["worst"] == opt.key) current.remove("worst")
                    publish()
                    inputContainer.removeAllViews()
                    renderMaxDiff(q)
                }
            }
            val label = TextView(context).apply {
                text = opt.label
                layoutParams = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val worstRb = RadioButton(context).apply {
                isChecked = current["worst"] == opt.key
                isEnabled = current["best"] != opt.key
                setOnClickListener {
                    current["worst"] = opt.key
                    if (current["best"] == opt.key) current.remove("best")
                    publish()
                    inputContainer.removeAllViews()
                    renderMaxDiff(q)
                }
            }
            row.addView(bestRb, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
            row.addView(worstRb, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            inputContainer.addView(row)
        }
    }

    private fun renderSignature(q: PulseQuestion) {
        val canvasView = SignatureView(context)
        canvasView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(160))
        canvasView.onChange = { dataUri -> answers[q.id] = dataUri }
        // Restore prior drawing isn't supported in the minimal v1 —
        // operator can clear + re-draw.
        val clear = Button(context).apply {
            text = "Clear"; textSize = 12f
            setOnClickListener {
                canvasView.clear()
                answers[q.id] = null
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(4) }
        }
        inputContainer.addView(canvasView)
        inputContainer.addView(clear)
    }

    private fun renderUnsupported(q: PulseQuestion) {
        val tv = TextView(context).apply {
            text = "[Unsupported question kind: ${q.kind}]"
            textSize = 12f
            setTextColor(Color.parseColor("#71717A"))
        }
        inputContainer.addView(tv)
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
            context.resources.displayMetrics).toInt()

    private fun accentColor(): Int =
        runCatching { Color.parseColor(survey.theme?.primaryColor ?: "") }
            .getOrElse { Color.parseColor("#F43F5E") }

    private fun simpleWatcher(onChange: (String) -> Unit): android.text.TextWatcher =
        object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                onChange(s?.toString().orEmpty())
            }
        }

    /**
     * SignatureView — minimal pointer-driven canvas. Captures
     * strokes with onTouchEvent, holds them as a Path, and exports
     * a base64 PNG data URI on every stroke completion.
     */
    private class SignatureView(context: Context) : View(context) {
        private val path = Path()
        private val paint = Paint().apply {
            color = Color.parseColor("#18181B")
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        var onChange: ((String) -> Unit)? = null
        private var lastX = 0f
        private var lastY = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.parseColor("#FAFAFA"))
            canvas.drawPath(path, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    path.moveTo(event.x, event.y)
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    path.quadTo(lastX, lastY,
                        (event.x + lastX) / 2, (event.y + lastY) / 2)
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_UP -> exportPNG()
                else -> return false
            }
            invalidate()
            return true
        }

        fun clear() {
            path.reset()
            invalidate()
        }

        private fun exportPNG() {
            if (width == 0 || height == 0) return
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            canvas.drawPath(path, paint)
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            onChange?.invoke("data:image/png;base64,$base64")
        }
    }
}
