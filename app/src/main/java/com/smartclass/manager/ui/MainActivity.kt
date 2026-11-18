package com.smartclass.manager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.smartclass.manager.R
import com.smartclass.manager.core.AppConfig
import com.smartclass.manager.core.ErrorReport
import com.smartclass.manager.core.ErrorTracker
import com.smartclass.manager.data.ClassSession
import com.smartclass.manager.data.DatabaseManager
import com.smartclass.manager.data.EvaluationStatus
import com.smartclass.manager.data.Student
import com.smartclass.manager.databinding.ActivityMainBinding
import com.smartclass.manager.engine.AnalyticsEngine
import com.smartclass.manager.engine.AudioEngine
import com.smartclass.manager.engine.ExcelManager
import com.smartclass.manager.engine.RouletteEngine

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dbManager: DatabaseManager
    private lateinit var audioEngine: AudioEngine
    private lateinit var studentAdapter: StudentAdapter
    private lateinit var historyAdapter: HistoryAdapter

    private var activeSession: ClassSession? = null
    private var currentPickedStudent: Student? = null
    private var bonusActive = false
    private var drawTimer: CountDownTimer? = null
    private var answerTimer: CountDownTimer? = null

    private val importExcelLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        ErrorTracker.safeRun("MainActivity", "importExcel", ::showErrorDialog) {
            val classId = dbManager.db.activeClassId ?: run {
                toast("اختر أو أضف قسماً أولاً")
                return@safeRun
            }
            val fileName = getFileName(uri)
            val preview = ExcelManager.importFromUri(this, uri, fileName)
            val (added, skipped) = dbManager.addStudentsBulk(classId, preview.names)
            val warnMsg = if (preview.warnings.isNotEmpty()) "\nتنبيهات:\n${preview.warnings.take(5).joinToString("\n")}" else ""
            AlertDialog.Builder(this)
                .setTitle("نتيجة الاستيراد")
                .setMessage("تمت إضافة: $added تلميذ\nتم تجاهل المكرر: $skipped$warnMsg")
                .setPositiveButton("موافق", null)
                .show()
            refreshAllViews()
        }
    }

    private val exportRecordsLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        ExcelManager.exportRecordsToCsv(this, uri, dbManager.db.records)
        toast("تم تصدير سجل الإجابات بنجاح")
    }

    private val exportGradesLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        val cls = dbManager.db.classes.find { it.id == dbManager.db.activeClassId } ?: return@registerForActivityResult
        val students = dbManager.getStudentsByClass(cls.id)
        ExcelManager.exportClassGradesToCsv(this, uri, cls.name, students)
        toast("تم تصدير كشف النقاط بنجاح")
    }

    private val exportBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        ErrorTracker.safeRun("MainActivity", "exportBackup", ::showErrorDialog) {
            contentResolver.openOutputStream(uri)?.use {
                it.write(dbManager.exportBackupJson().toByteArray(Charsets.UTF_8))
            }
            toast("تم حفظ النسخة الاحتياطية")
        }
    }

    private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        ErrorTracker.safeRun("MainActivity", "importBackup", ::showErrorDialog) {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            dbManager.importBackupJson(json).onSuccess {
                toast("تم استرجاع البيانات بنجاح")
                refreshAllViews()
            }.onFailure {
                toast(it.message ?: "فشل الاسترجاع")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbManager = DatabaseManager(this)
        audioEngine = AudioEngine(this)

        applyThemeAndWakeLock()
        setupRecyclerViews()
        setupBottomNavigation()
        setupRouletteListeners()
        setupClassesListeners()
        setupHistoryListeners()
        setupAnalyticsListeners()
        setupSettingsListeners()
        setupBehaviorChips()

        ErrorTracker.consumeLastCrash(this)?.let { crashText ->
            AlertDialog.Builder(this)
                .setTitle("تقرير خطأ من الجلسة السابقة")
                .setMessage(crashText)
                .setPositiveButton("نسخ") { _, _ -> copyToClipboard(crashText) }
                .setNegativeButton("إغلاق", null)
                .show()
        }

        refreshAllViews()
    }

    private fun applyThemeAndWakeLock() {
        val mode = if (dbManager.settings.themeMode == "light")
            AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        AppCompatDelegate.setDefaultNightMode(mode)

        if (dbManager.settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupRecyclerViews() {
        studentAdapter = StudentAdapter(emptyList(), dbManager.settings.safeDisplayMode,
            onItemClick = { showStudentCardDialog(it) },
            onItemLongClick = { showStudentActionsDialog(it) }
        )
        binding.rvStudents.layoutManager = LinearLayoutManager(this)
        binding.rvStudents.adapter = studentAdapter

        historyAdapter = HistoryAdapter(emptyList(), dbManager.settings.safeDisplayMode)
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.tabRoulette.visibility = if (item.itemId == R.id.nav_roulette) View.VISIBLE else View.GONE
            binding.tabClasses.visibility = if (item.itemId == R.id.nav_classes) View.VISIBLE else View.GONE
            binding.tabHistory.visibility = if (item.itemId == R.id.nav_history) View.VISIBLE else View.GONE
            binding.tabAnalytics.visibility = if (item.itemId == R.id.nav_analytics) View.VISIBLE else View.GONE
            binding.tabSettings.visibility = if (item.itemId == R.id.nav_settings) View.VISIBLE else View.GONE
            refreshAllViews()
            true
        }
    }

    private fun setupRouletteListeners() {
        binding.btnSelectClass.setOnClickListener { showClassPickerDialog() }
        binding.btnNewSession.setOnClickListener {
            val classId = dbManager.db.activeClassId ?: return@setOnClickListener
            activeSession = dbManager.createNewSession(classId)
            toast("تم بدء حصة جديدة وتصفير صندوق السحب")
            refreshRouletteHeader()
        }
        binding.btnStartDraw.setOnClickListener { startRandomDraw(skipCountdown = false) }
        binding.btnQuickSpin.setOnClickListener { startRandomDraw(skipCountdown = true) }

        binding.btnToggleBonus.setOnClickListener {
            bonusActive = !bonusActive
            binding.btnToggleBonus.text = if (bonusActive) "+${dbManager.settings.bonusPointValue} مفعّل" else "+ تشجيع"
        }

        binding.btnMarkAnswered.setOnClickListener { submitEvaluation(EvaluationStatus.ANSWERED) }
        binding.btnMarkMissed.setOnClickListener { submitEvaluation(EvaluationStatus.MISSED) }
        binding.btnMarkAbsent.setOnClickListener { submitEvaluation(EvaluationStatus.ABSENT) }
    }

    private fun startRandomDraw(skipCountdown: Boolean) {
        ErrorTracker.safeRun("MainActivity", "startRandomDraw", ::showErrorDialog) {
            val classId = dbManager.db.activeClassId ?: run {
                toast("أضف قسماً من تبويب الأقسام أولاً")
                return@safeRun
            }
            val students = dbManager.getStudentsByClass(classId)
            if (students.isEmpty()) {
                toast("القسم فارغ! أضف تلاميذ أولاً")
                return@safeRun
            }
            val session = ensureSession(classId)
            val chosen = RouletteEngine.pickNextStudent(students, session, dbManager.settings.allowRepeatInCycle)
            if (chosen == null) {
                AlertDialog.Builder(this)
                    .setTitle("اكتملت الدورة!")
                    .setMessage("شارك جميع التلاميذ في هذه الدورة. هل تريد بدء دورة جديدة؟")
                    .setPositiveButton("دورة جديدة") { _, _ ->
                        activeSession = dbManager.createNewSession(classId)
                        refreshRouletteHeader()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
                return@safeRun
            }

            drawTimer?.cancel()
            answerTimer?.cancel()
            binding.layoutEvaluation.visibility = View.GONE
            val seconds = if (skipCountdown) 0 else dbManager.settings.countdownSeconds.coerceAtLeast(0)

            if (seconds == 0) {
                revealSelectedStudent(chosen)
            } else {
                binding.tvCountdown.visibility = View.VISIBLE
                drawTimer = object : CountDownTimer(seconds * 1000L, 250L) {
                    override fun onTick(millisUntilFinished: Long) {
                        val secLeft = ((millisUntilFinished + 999) / 1000).toInt()
                        binding.tvCountdown.text = secLeft.toString()
                        binding.tvSelectedStudent.text = students.random().fullName
                        if (dbManager.settings.soundEnabled && millisUntilFinished % 1000 < 260) {
                            audioEngine.playCountdownTick()
                        }
                    }

                    override fun onFinish() {
                        binding.tvCountdown.visibility = View.GONE
                        revealSelectedStudent(chosen)
                    }
                }.start()
            }
        }
    }

    private fun revealSelectedStudent(student: Student) {
        currentPickedStudent = student
        binding.tvSelectedStudent.text = student.fullName
        if (dbManager.settings.soundEnabled) audioEngine.playSelectedChime()
        if (dbManager.settings.ttsEnabled) audioEngine.speakArabicName(student.fullName, dbManager.settings.ttsSpeechRate)

        bonusActive = false
        binding.btnToggleBonus.text = "+ تشجيع"
        binding.etScore.setText((dbManager.settings.maxScore / 2).toString())
        binding.etNote.setText("")
        binding.etSubject.visibility = if (dbManager.settings.showSubjectField) View.VISIBLE else View.GONE
        binding.chipGroupBehavior.visibility = if (dbManager.settings.showBehaviorTags) View.VISIBLE else View.GONE
        binding.layoutEvaluation.visibility = View.VISIBLE

        if (dbManager.settings.answerTimerEnabled) {
            binding.tvAnswerTimer.visibility = View.VISIBLE
            answerTimer = object : CountDownTimer(dbManager.settings.answerTimerSeconds * 1000L, 1000L) {
                override fun onTick(ms: Long) {
                    binding.tvAnswerTimer.text = "الوقت المتبقي للإجابة: ${ms / 1000} ث"
                }
                override fun onFinish() {
                    binding.tvAnswerTimer.text = "انتهى الوقت!"
                    if (dbManager.settings.soundEnabled) audioEngine.playGentleMissTone()
                }
            }.start()
        } else {
            binding.tvAnswerTimer.visibility = View.GONE
        }
    }

    private fun submitEvaluation(status: EvaluationStatus) {
        ErrorTracker.safeRun("MainActivity", "submitEvaluation", ::showErrorDialog) {
            val student = currentPickedStudent ?: return@safeRun
            val session = activeSession ?: return@safeRun
            answerTimer?.cancel()

            val rawScore = binding.etScore.text.toString().toDoubleOrNull() ?: 0.0
            val score = rawScore.coerceIn(0.0, dbManager.settings.maxScore)
            val bonus = if (bonusActive) dbManager.settings.bonusPointValue else 0.0
            val subject = binding.etSubject.text.toString()
            val note = binding.etNote.text.toString()

            if (dbManager.settings.soundEnabled) {
                when (status) {
                    EvaluationStatus.ANSWERED -> audioEngine.playSuccessTone()
                    EvaluationStatus.MISSED -> audioEngine.playGentleMissTone()
                    EvaluationStatus.ABSENT -> {}
                }
            }

            val record = dbManager.recordEvaluation(session.id, student, status, score, bonus, subject, note)
            currentPickedStudent = null
            binding.layoutEvaluation.visibility = View.GONE
            binding.tvAnswerTimer.visibility = View.GONE
            binding.tvSelectedStudent.text = "تم رصد: ${student.fullName} (${status.arabicLabel})"
            refreshRouletteHeader()

            Snackbar.make(binding.root, "تم الحفظ بنجاح", Snackbar.LENGTH_LONG)
                .setDuration(AppConfig.UNDO_TIMEOUT_MS.toInt())
                .setAction("تراجع") {
                    if (dbManager.undoRecord(record.id)) {
                        toast("تم التراجع عن الرصد")
                        refreshAllViews()
                    }
                }.show()
        }
    }

    private fun ensureSession(classId: String): ClassSession {
        val current = activeSession
        if (current != null && current.classId == classId && !current.isCompleted) return current
        val existing = dbManager.getLastUnfinishedSession(classId)
        val resolved = existing ?: dbManager.createNewSession(classId)
        activeSession = resolved
        return resolved
    }

    private fun showClassPickerDialog() {
        val classes = dbManager.db.classes
        if (classes.isEmpty()) {
            toast("لا توجد أقسام بعد")
            return
        }
        val names = classes.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("اختر القسم")
            .setItems(names) { _, which ->
                val selected = classes[which]
                dbManager.db.activeClassId = selected.id
                dbManager.saveDatabase()
                activeSession = dbManager.getLastUnfinishedSession(selected.id)
                if (activeSession != null) {
                    AlertDialog.Builder(this)
                        .setTitle("استئناف الحصة السابقة؟")
                        .setMessage("توجد حصة غير مكتملة بتاريخ ${activeSession?.dateStr} (متبقي ${activeSession?.remainingStudentIds?.size} تلميذ).")
                        .setPositiveButton("استئناف") { _, _ -> refreshAllViews() }
                        .setNegativeButton("حصة جديدة") { _, _ ->
                            activeSession = dbManager.createNewSession(selected.id)
                            refreshAllViews()
                        }.show()
                } else {
                    refreshAllViews()
                }
            }.show()
    }

    private fun setupClassesListeners() {
        binding.btnAddClass.setOnClickListener {
            promptText("إضافة قسم جديد", "اسم القسم (مثال: 2 ع ت 1)") { name ->
                dbManager.addClass(name).onSuccess {
                    dbManager.db.activeClassId = it.id
                    dbManager.saveDatabase()
                    refreshAllViews()
                }.onFailure { toast(it.message ?: "خطأ") }
            }
        }
        binding.btnRenameClass.setOnClickListener {
            val cls = dbManager.db.classes.find { it.id == dbManager.db.activeClassId } ?: return@setOnClickListener
            promptText("تعديل اسم القسم", "الاسم الجديد", cls.name) { newName ->
                dbManager.renameClass(cls.id, newName).onFailure { toast(it.message ?: "خطأ") }
                refreshAllViews()
            }
        }
        binding.btnDeleteClass.setOnClickListener {
            val cls = dbManager.db.classes.find { it.id == dbManager.db.activeClassId } ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("تأكيد الحذف")
                .setMessage("هل تريد حذف قسم '${cls.name}' وجميع تلاميذه؟")
                .setPositiveButton("حذف") { _, _ ->
                    dbManager.deleteClass(cls.id)
                    activeSession = null
                    refreshAllViews()
                }.setNegativeButton("إلغاء", null).show()
        }
        binding.btnAddStudent.setOnClickListener {
            val classId = dbManager.db.activeClassId ?: run { toast("أضف قسماً أولاً"); return@setOnClickListener }
            promptText("إضافة تلميذ", "اللقب والاسم") { name ->
                dbManager.addStudent(classId, name).onFailure { toast(it.message ?: "خطأ") }
                refreshAllViews()
            }
        }
        binding.btnImportExcel.setOnClickListener {
            importExcelLauncher.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/csv", "text/comma-separated-values", "text/plain"
            ))
        }
        binding.btnPasteList.setOnClickListener {
            val classId = dbManager.db.activeClassId ?: run { toast("أضف قسماً أولاً"); return@setOnClickListener }
            promptText("لصق قائمة التلاميذ", "ضع كل اسم في سطر مستقل") { block ->
                val list = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val (added, skipped) = dbManager.addStudentsBulk(classId, list)
                toast("تمت إضافة $added وتجاهل $skipped مكرر")
                refreshAllViews()
            }
        }
        binding.btnSeedDemo.setOnClickListener {
            dbManager.seedDemoClass()
            toast("تم إنشاء قسم تجريبي بـ 10 تلاميذ")
            refreshAllViews()
        }
        binding.etSearchStudent.addTextChangedListener(simpleWatcher { refreshStudentsList() })
    }

    private fun setupHistoryListeners() {
        binding.btnExportRecords.setOnClickListener { exportRecordsLauncher.launch("سجل_الإجابات.csv") }
        binding.btnExportGrades.setOnClickListener { exportGradesLauncher.launch("كشف_النقاط.csv") }
        binding.etSearchHistory.addTextChangedListener(simpleWatcher { refreshHistoryList() })
    }

    private fun setupAnalyticsListeners() {
        binding.btnGenerateGroups.setOnClickListener {
            val classId = dbManager.db.activeClassId ?: return@setOnClickListener
            val size = binding.etGroupSize.text.toString().toIntOrNull() ?: 4
            val groups = RouletteEngine.generateRandomGroups(dbManager.getStudentsByClass(classId), size)
            binding.tvGroupsResult.text = groups.mapIndexed { idx, g ->
                "المجموعة ${idx + 1}: ${g.joinToString("، ") { it.fullName }}"
            }.joinToString("\n\n")
        }
    }

    private fun setupSettingsListeners() {
        val s = dbManager.settings
        binding.swAllowRepeat.isChecked = s.allowRepeatInCycle
        binding.swSoundEnabled.isChecked = s.soundEnabled
        binding.swTtsEnabled.isChecked = s.ttsEnabled
        binding.swAnswerTimer.isChecked = s.answerTimerEnabled
        binding.swShowBehavior.isChecked = s.showBehaviorTags
        binding.swShowSubject.isChecked = s.showSubjectField
        binding.swSafeDisplay.isChecked = s.safeDisplayMode
        binding.swKeepScreenOn.isChecked = s.keepScreenOn

        binding.etCountdownSec.setText(s.countdownSeconds.toString())
        binding.etMaxScore.setText(s.maxScore.toString())
        binding.etBonusValue.setText(s.bonusPointValue.toString())

        val saveSwitch = {
            s.allowRepeatInCycle = binding.swAllowRepeat.isChecked
            s.soundEnabled = binding.swSoundEnabled.isChecked
            s.ttsEnabled = binding.swTtsEnabled.isChecked
            s.answerTimerEnabled = binding.swAnswerTimer.isChecked
            s.showBehaviorTags = binding.swShowBehavior.isChecked
            s.showSubjectField = binding.swShowSubject.isChecked
            s.safeDisplayMode = binding.swSafeDisplay.isChecked
            s.keepScreenOn = binding.swKeepScreenOn.isChecked
            dbManager.saveSettings()
            applyThemeAndWakeLock()
        }
        listOf(
            binding.swAllowRepeat, binding.swSoundEnabled, binding.swTtsEnabled,
            binding.swAnswerTimer, binding.swShowBehavior, binding.swShowSubject,
            binding.swSafeDisplay, binding.swKeepScreenOn
        ).forEach { it.setOnCheckedChangeListener { _, _ -> saveSwitch() } }

        binding.btnSaveNumericSettings.setOnClickListener {
            s.countdownSeconds = binding.etCountdownSec.text.toString().toIntOrNull() ?: 3
            s.maxScore = binding.etMaxScore.text.toString().toDoubleOrNull() ?: 20.0
            s.bonusPointValue = binding.etBonusValue.text.toString().toDoubleOrNull() ?: 1.0
            dbManager.saveSettings()
            toast("تم حفظ الإعدادات الرقمية")
        }

        binding.btnThemeDark.setOnClickListener { s.themeMode = "dark"; dbManager.saveSettings(); applyThemeAndWakeLock() }
        binding.btnThemeLight.setOnClickListener { s.themeMode = "light"; dbManager.saveSettings(); applyThemeAndWakeLock() }

        binding.btnBackupExport.setOnClickListener { exportBackupLauncher.launch("SmartClass_Backup.json") }
        binding.btnBackupImport.setOnClickListener { importBackupLauncher.launch(arrayOf("application/json", "*/*")) }
        binding.btnHealthCheck.setOnClickListener { toast(dbManager.runHealthCheck()) }
        binding.btnViewErrorLog.setOnClickListener {
            val logs = ErrorTracker.getHistory()
            val msg = if (logs.isEmpty()) "لا توجد أخطاء مسجلة! التطبيق يعمل بسلاسة."
            else logs.joinToString("\n\n") { it.toCopyableText() }
            AlertDialog.Builder(this)
                .setTitle("سجل تشخيص الأخطاء (${logs.size})")
                .setMessage(msg)
                .setPositiveButton("نسخ الكل") { _, _ -> copyToClipboard(msg) }
                .setNegativeButton("مسح") { _, _ -> ErrorTracker.clearHistory() }
                .show()
        }
    }

    private fun setupBehaviorChips() {
        AppConfig.DEFAULT_BEHAVIOR_TAGS.forEach { tag ->
            val chip = Chip(this).apply {
                text = tag
                setOnClickListener {
                    val cur = binding.etNote.text.toString()
                    binding.etNote.setText(if (cur.isBlank()) tag else "$cur | $tag")
                }
            }
            binding.chipGroupBehavior.addView(chip)
        }
    }

    private fun refreshAllViews() {
        refreshRouletteHeader()
        refreshStudentsList()
        refreshHistoryList()
        refreshAnalyticsView()
    }

    private fun refreshRouletteHeader() {
        val cls = dbManager.db.classes.find { it.id == dbManager.db.activeClassId }
        if (cls == null) {
            binding.btnSelectClass.text = "اختر القسم ▼"
            binding.tvSessionInfo.text = "أضف قسماً من تبويب الأقسام للبدء"
            return
        }
        binding.btnSelectClass.text = "${cls.name} ▼"
        val total = dbManager.getStudentsByClass(cls.id).size
        val session = ensureSession(cls.id)
        binding.tvSessionInfo.text = "${session.title} | المتبقي في الدورة: ${session.remainingStudentIds.size} / $total"
    }

    private fun refreshStudentsList() {
        val classId = dbManager.db.activeClassId ?: run {
            studentAdapter.update(emptyList(), dbManager.settings.safeDisplayMode)
            return
        }
        val query = binding.etSearchStudent.text.toString()
        studentAdapter.update(dbManager.searchStudents(classId, query), dbManager.settings.safeDisplayMode)
    }

    private fun refreshHistoryList() {
        val q = binding.etSearchHistory.text.toString().trim()
        val list = if (q.isEmpty()) dbManager.db.records else dbManager.db.records.filter {
            it.studentName.contains(q, true) || it.dateStr.contains(q, true) ||
                it.status.arabicLabel.contains(q, true) || it.className.contains(q, true)
        }
        historyAdapter.update(list, dbManager.settings.safeDisplayMode)
    }

    private fun refreshAnalyticsView() {
        val classId = dbManager.db.activeClassId ?: return
        val students = dbManager.getStudentsByClass(classId)
        val records = dbManager.db.records.filter { it.classId == classId }
        val data = AnalyticsEngine.compute(students, records)

        binding.tvCoverageSummary.text = "تغطية القسم: ${data.coveragePercent}% (${data.participatedStudents}/${data.totalStudents})"
        binding.progressCoverage.progress = data.coveragePercent
        binding.tvFairnessInfo.text = "عدالة السحب: أقل تلميذ سُحب (${data.minDraws}) | أكثر تلميذ (${data.maxDraws}) | أجاب: ${data.totalAnswered} | لم يجب: ${data.totalMissed} | غياب: ${data.totalAbsent}"
        binding.tvTopPerformers.text = "لوحة الشرف (الأعلى نقاطاً):\n" +
            data.topPerformers.joinToString("\n") { "• ${it.fullName} (${it.totalScore} نقطة)" }
        binding.tvLeastParticipated.text = "الأقل مشاركة (أولوية السحب):\n" +
            data.leastParticipated.joinToString("\n") { "• ${it.fullName} (${it.answeredCount + it.missedCount} مشاركة)" }
    }

    private fun showStudentCardDialog(s: Student) {
        val history = dbManager.db.records.filter { it.studentId == s.id }.take(6)
            .joinToString("\n") { "- ${it.dateStr}: ${it.status.arabicLabel} (${it.score})" }
        AlertDialog.Builder(this)
            .setTitle("بطاقة: ${s.fullName}")
            .setMessage("المجموع: ${s.totalScore} | تشجيع: ${s.bonusCount}\nأجاب: ${s.answeredCount} | لم يجب: ${s.missedCount} | غياب: ${s.absentCount}\n\nآخر المشاركات:\n${history.ifBlank { "لا توجد مشاركات بعد" }}")
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun showStudentActionsDialog(s: Student) {
        AlertDialog.Builder(this)
            .setTitle(s.fullName)
            .setItems(arrayOf("تعديل الاسم", "حذف التلميذ")) { _, which ->
                if (which == 0) {
                    promptText("تعديل اسم التلميذ", "الاسم الجديد", s.fullName) {
                        dbManager.updateStudent(s.id, it)
                        refreshAllViews()
                    }
                } else {
                    dbManager.deleteStudent(s.id)
                    refreshAllViews()
                }
            }.show()
    }

    private fun showErrorDialog(report: ErrorReport) {
        AlertDialog.Builder(this)
            .setTitle("تم رصد خطأ تقني")
            .setMessage(report.toCopyableText())
            .setPositiveButton("نسخ التقرير") { _, _ -> copyToClipboard(report.toCopyableText()) }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun promptText(title: String, hint: String, initial: String = "", onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            this.hint = hint
            setText(initial)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("حفظ") { _, _ -> onConfirm(input.text.toString()) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun getFileName(uri: android.net.Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) return cursor.getString(idx)
        }
        return "file.csv"
    }

    private fun simpleWatcher(onChanged: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { onChanged() }
        override fun afterTextChanged(s: Editable?) {}
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Report", text))
        toast("تم النسخ إلى الحافظة")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        drawTimer?.cancel()
        answerTimer?.cancel()
        audioEngine.release()
        super.onDestroy()
    }
}
