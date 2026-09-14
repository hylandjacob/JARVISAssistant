package com.jarvis.assistant.overlay

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.agent.AgentPlanner
import com.jarvis.assistant.ai.ChatMessage
import com.jarvis.assistant.ai.PromptBuilder
import com.jarvis.assistant.command.CommandEngine
import com.jarvis.assistant.command.ExecutionResult
import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.command.LocalIntentRouter
import com.jarvis.assistant.command.describe
import com.jarvis.assistant.command.requiresConfirmation
import com.jarvis.assistant.di.AppContainer
import com.jarvis.assistant.search.needsWebSearch
import com.jarvis.assistant.voice.JarvisGlobalState
import com.jarvis.assistant.voice.SpeechEvent
import com.jarvis.assistant.voice.VoiceState
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.WakeWordModel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Background JARVIS service.
 *
 * Wake-word detection is now handled fully on-device with openWakeWord.
 * The local detector listens for "Hey Jarvis" without repeatedly invoking
 * Android SpeechRecognizer.
 *
 * Once "Hey Jarvis" is detected:
 * 1. openWakeWord releases the microphone
 * 2. Android SpeechRecognizer listens for the actual command
 * 3. JARVIS processes the command
 * 4. openWakeWord resumes local listening
 */
class OverlayService : Service() {

    private lateinit var container: AppContainer
    private var conversationId: Long? = null
    private var isBusy = false

    private var wakeWordJob: Job? = null
    private var wakeWordEngine: WakeWordEngine? = null

    /** A command awaiting a spoken "haan"/"yes" or "nahi"/"no" from the user. */
    private var pendingConfirmation: JarvisCommand? = null
    private var pendingPlan: List<JarvisCommand>? = null
    private var lastUserRequestText: String? = null

    private val agentPlanner = AgentPlanner()

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        container =
            (applicationContext as JarvisApplication).container

        startForegroundNotification()
        installAutomationVoiceFeedback()
        startWakeWordLoop()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeWordJob?.cancel()

        runCatching {
            wakeWordEngine?.stop()
        }

        runCatching {
            wakeWordEngine?.release()
        }

        wakeWordEngine = null

        com.jarvis.assistant.accessibility
            .JarvisAccessibilityService.onStepEvent = null

        container.voiceActivityDetector.stop()
        container.speechToTextManager.cancel()

        serviceScope.cancel()

        super.onDestroy()
    }

    /**
     * Speaks concise live progress for multi-step Accessibility automation.
     */
    private fun installAutomationVoiceFeedback() {
        com.jarvis.assistant.accessibility
            .JarvisAccessibilityService.onStepEvent =
            { current, total, label, success ->

                serviceScope.launch {
                    when (success) {
                        null -> {
                            speakStatus(
                                "Step $current of $total: ${friendlyStep(label)}"
                            )
                        }

                        true -> {
                            if (current == total) {
                                speakStatus(
                                    "Step $current of $total complete."
                                )
                            }
                        }

                        false -> {
                            speakStatus(
                                "Step $current of $total failed: " +
                                    "${friendlyStep(label)}. " +
                                    "I couldn't do that step."
                            )
                        }
                    }
                }
            }
    }

    private suspend fun speakStatus(text: String) {
        setState(VoiceState.SPEAKING)

        val prefs = container.securePrefs

        container.textToSpeechManager.setRate(
            prefs.speechRate
        )

        val voiceName =
            prefs.voiceName
                ?: container.textToSpeechManager
                    .autoSelectMaleVoice()
                    ?.also {
                        prefs.voiceName = it
                    }

        container.textToSpeechManager.setVoice(
            voiceName
        )

        container.textToSpeechManager.speak(text)

        if (!isBusy) {
            setState(VoiceState.IDLE)
        }
    }

    private fun friendlyStep(label: String): String =
        label
            .replace(
                "TAP ",
                "tap ",
                ignoreCase = true
            )
            .replace(
                "TYPE ",
                "type ",
                ignoreCase = true
            )
            .replace(
                "LONG_PRESS ",
                "long-press ",
                ignoreCase = true
            )
            .replace(
                "SCROLL",
                "scroll",
                ignoreCase = true
            )
            .replace(
                "BACK",
                "go back",
                ignoreCase = true
            )
            .replace(
                "HOME",
                "go home",
                ignoreCase = true
            )
            .replace(
                "SUBMIT",
                "submit the field",
                ignoreCase = true
            )
            .replace(
                "TAP FIRST RESULT",
                "open the first result",
                ignoreCase = true
            )
            .replace(
                "WAIT",
                "wait",
                ignoreCase = true
            )

    // -------------------------------------------------------------------------
    // Wake word
    // -------------------------------------------------------------------------

    /**
     * Starts the fully local openWakeWord detector.
     *
     * The ONNX files are expected at:
     *
     * app/src/main/assets/
     *   embedding_model.onnx
     *   melspectrogram.onnx
     *   hey_jarvis_v0.1.onnx
     *
     * Wake-word detection does not use SpeechRecognizer or a cloud service.
     */
    private fun startWakeWordLoop() {
        wakeWordJob?.cancel()

        wakeWordJob =
            serviceScope.launch {

                if (
                    ContextCompat.checkSelfPermission(
                        this@OverlayService,
                        Manifest.permission.RECORD_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(
                        WAKE_LOG_TAG,
                        "Microphone permission missing"
                    )

                    return@launch
                }

                try {
                    wakeWordEngine =
                        WakeWordEngine(
                            context = this@OverlayService,
                            models =
                                listOf(
                                    WakeWordModel(
                                        name = "Hey Jarvis",
                                        modelPath =
                                            "hey_jarvis_v0.1.onnx",
                                        threshold = 0.10f
                                    )
                                ),
                            detectionCooldownMs = 2500L
                        )

                    Log.i(
                        WAKE_LOG_TAG,
                        "openWakeWord engine created"
                    )

                    wakeWordEngine?.start()

                    Log.i(
                        WAKE_LOG_TAG,
                        "Local wake-word detector started"
                    )
                } catch (t: Throwable) {
                    Log.e(
                        WAKE_LOG_TAG,
                        "Failed to initialize wake-word engine",
                        t
                    )

                    return@launch
                }

                try {
                    wakeWordEngine
                        ?.detections
                        ?.collect { detection ->

                            if (
                                detection.model.name ==
                                    "Hey Jarvis" &&
                                container
                                    .securePrefs
                                    .wakeWordEnabled &&
                                !isBusy
                            ) {
                                Log.i(
                                    WAKE_LOG_TAG,
                                    "Hey Jarvis detected. " +
                                        "score=${detection.score}"
                                )

                                handleWakeWordDetection()
                            }
                        }
                } catch (t: Throwable) {
                    Log.e(
                        WAKE_LOG_TAG,
                        "Wake-word detection loop failed",
                        t
                    )
                }
            }
    }

    /**
     * Handles the transition from local wake-word detection to
     * Android command recognition.
     */
    private suspend fun handleWakeWordDetection() {
        if (isBusy) return

        isBusy = true

        try {
            Log.i(
                WAKE_LOG_TAG,
                "Pausing wake-word microphone"
            )

            runCatching {
                wakeWordEngine?.stop()
            }.onFailure {
                Log.w(
                    WAKE_LOG_TAG,
                    "Wake-word engine stop failed",
                    it
                )
            }

            /*
             * Small delay gives AudioRecord/openWakeWord time to release
             * the microphone before SpeechRecognizer requests it.
             */
            delay(300)

            setState(VoiceState.LISTENING)

            /*
             * Listen for the user's actual command AFTER "Hey Jarvis".
             */
            runVoiceTurn()
        } catch (t: Throwable) {
            Log.e(
                WAKE_LOG_TAG,
                "Voice turn failed after wake word",
                t
            )
        } finally {
            isBusy = false
            setState(VoiceState.IDLE)

            resumeWakeWordDetector()
        }
    }

    /**
     * Restarts local wake-word listening after a command finishes.
     */
    private suspend fun resumeWakeWordDetector() {
        if (!container.securePrefs.wakeWordEnabled) {
            Log.i(
                WAKE_LOG_TAG,
                "Wake word disabled; detector will not resume"
            )

            return
        }

        if (
            ContextCompat.checkSelfPermission(
                this@OverlayService,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(
                WAKE_LOG_TAG,
                "Cannot resume detector: microphone permission missing"
            )

            return
        }

        delay(350)

        runCatching {
            wakeWordEngine?.start()

            Log.i(
                WAKE_LOG_TAG,
                "Wake-word detector resumed"
            )
        }.onFailure {
            Log.e(
                WAKE_LOG_TAG,
                "Failed to resume wake-word detector",
                it
            )
        }
    }

    // -------------------------------------------------------------------------
    // Notification / lifecycle
    // -------------------------------------------------------------------------

    private fun startForegroundNotification() {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "JARVIS Background",
                    NotificationManager.IMPORTANCE_LOW
                )

            getSystemService(
                NotificationManager::class.java
            )?.createNotificationChannel(channel)
        }

        val contentText =
            if (
                container
                    .securePrefs
                    .wakeWordEnabled
            ) {
                "Listening locally for \"Hey Jarvis\"."
            } else {
                "Background JARVIS is running. " +
                    "Enable Wake Word for hands-free use."
            }

        val notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(
                    "JARVIS is running in the background"
                )
                .setContentText(contentText)
                .setSmallIcon(
                    R.drawable.ic_notification
                )
                .setOngoing(true)
                .build()

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    // -------------------------------------------------------------------------
    // Voice pipeline
    // -------------------------------------------------------------------------

    private suspend fun runVoiceTurn(
        depth: Int = 0
    ) {
        if (depth > 4) return

        setState(VoiceState.LISTENING)

        var finalText: String? = null

        container
            .speechToTextManager
            .listen(languageTag = null)
            .collect { event ->

                if (
                    event is SpeechEvent.FinalResult
                ) {
                    finalText = event.text
                }
            }

        val text =
            finalText
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return

        val bargedIn =
            processCommand(text)

        if (bargedIn) {
            runVoiceTurn(
                depth + 1
            )
        }
    }

    /**
     * Returns true if the user started talking again while JARVIS
     * was replying.
     */
    private suspend fun processCommand(
        text: String
    ): Boolean {

        pendingConfirmation?.let {
            pending ->

            return handlePendingConfirmation(
                pending,
                text
            )
        }

        pendingPlan?.let {
            plan ->

            return handlePendingPlanConfirmation(
                plan,
                text
            )
        }

        /*
         * Routing-critical commands are matched locally first.
         */
        LocalIntentRouter
            .match(text)
            ?.let { command ->

                setState(
                    VoiceState.THINKING
                )

                val convId =
                    ensureConversation()

                container
                    .conversationRepository
                    .addMessage(
                        convId,
                        "user",
                        text
                    )

                if (
                    command is
                    JarvisCommand.StopAction
                ) {
                    container
                        .taskEngine
                        .cancel()
                }

                if (
                    command is
                    JarvisCommand.PauseTask
                ) {
                    container
                        .taskEngine
                        .pause()

                    return speakWithBargeIn(
                        "Paused."
                    )
                }

                if (
                    command is
                    JarvisCommand.ResumeTask
                ) {
                    container
                        .taskEngine
                        .resume()

                    return speakWithBargeIn(
                        "Resuming."
                    )
                }

                if (
                    command is
                    JarvisCommand.RetryLastTask
                ) {
                    val target =
                        lastUserRequestText

                    return if (
                        target.isNullOrBlank()
                    ) {
                        speakWithBargeIn(
                            "There's nothing to retry yet."
                        )
                    } else {
                        processCommand(target)
                    }
                }

                if (
                    container
                        .confirmationManager
                        .required(command)
                ) {
                    pendingConfirmation =
                        command

                    return speakWithBargeIn(
                        "${command.describe()} Say yes or no."
                    )
                }

                val currentApp =
                    container
                        .phoneContextEngine
                        .snapshot()
                        .appLabel

                speakStatus(
                    "I will " +
                        command
                            .describe()
                            .removeSuffix(".")
                            .removeSuffix("?") +
                        (
                            currentApp?.let {
                                " on $it"
                            } ?: ""
                        ) +
                        "."
                )

                val brainReply =
                    container
                        .brainCommandExecutor
                        .execute(command)

                val reply =
                    brainReply
                        ?: when (
                            val result =
                                container
                                    .actionExecutor
                                    .execute(command)
                        ) {
                            is ExecutionResult.Success ->
                                "Done. ${result.message}"

                            is ExecutionResult.Failure ->
                                "Command failed: " +
                                    "${result.message} " +
                                    failureGuidance(
                                        result.message
                                    )
                        }

                container
                    .conversationRepository
                    .addMessage(
                        convId,
                        "assistant",
                        reply
                    )

                return speakWithBargeIn(
                    reply
                )
            }

        setState(
            VoiceState.THINKING
        )

        val convId =
            ensureConversation()

        container
            .conversationRepository
            .addMessage(
                convId,
                "user",
                text
            )

        lastUserRequestText = text

        var searchContext = ""

        if (
            needsWebSearch(text)
        ) {
            container
                .webSearchService
                .search(text)
                .onSuccess {
                    searchContext = it
                }
        }

        val memoryContext =
            container
                .memoryRepository
                .getAllAsPromptContext()

        val phoneContext =
            container
                .phoneContextEngine
                .snapshot()
                .toPromptString()

        val appRegistry =
            container
                .appRegistry
                .compactPrompt()

        val knowledgeContext =
            container
                .knowledgeBase
                .compactPrompt(
                    container
                        .knowledgeBase
                        .retrieve(text)
                )

        val systemPrompt =
            PromptBuilder.systemPrompt(
                memoryContext,
                languageHint = text,
                phoneContext = phoneContext,
                appRegistry = appRegistry,
                knowledgeContext =
                    knowledgeContext
            ) +
                if (
                    searchContext
                        .isNotBlank()
                ) {
                    "\n\nRecent web search results for this question:\n$searchContext"
                } else {
                    ""
                }

        val history =
            container
                .conversationRepository
                .getHistory(convId)
                .takeLast(20)
                .map {
                    ChatMessage(
                        role =
                            if (
                                it.role ==
                                "user"
                            ) {
                                "user"
                            } else {
                                "assistant"
                            },
                        content =
                            it.content
                    )
                }

        var interrupted = false

        container
            .aiService
            .send(
                history,
                systemPrompt
            )
            .fold(
                onSuccess = { result ->

                    container
                        .conversationRepository
                        .addMessage(
                            convId,
                            "assistant",
                            result.replyText
                        )

                    val command =
                        CommandEngine.parse(
                            result.commandJson
                        )

                    val plan =
                        agentPlanner.parsePlan(
                            result.commandJson
                        )

                    interrupted =
                        when {

                            plan != null -> {

                                if (
                                    plan.actions.any {
                                        container
                                            .confirmationManager
                                            .required(it)
                                    }
                                ) {
                                    pendingPlan =
                                        plan.actions

                                    speakWithBargeIn(
                                        "I have a multi-step task ready. " +
                                            "${plan.actions.size} actions. " +
                                            "Say yes or no."
                                    )
                                } else {

                                    speakStatus(
                                        "I will perform " +
                                            "${plan.actions.size} steps " +
                                            "and verify each one."
                                    )

                                    val task =
                                        container
                                            .taskEngine
                                            .run(
                                                text,
                                                plan.actions
                                            ) { }

                                    val reply =
                                        when (
                                            task.status
                                        ) {

                                            com.jarvis.assistant.agent
                                                .TaskStatus.COMPLETED ->
                                                "Task completed successfully."

                                            com.jarvis.assistant.agent
                                                .TaskStatus.CANCELLED ->
                                                "Task cancelled."

                                            else -> {
                                                val detail =
                                                    task.failedSteps
                                                        .lastOrNull()
                                                        ?.substringAfter(
                                                            ": "
                                                        )

                                                "Command failed" +
                                                    (
                                                        detail?.let {
                                                            ": $it"
                                                        } ?: ""
                                                    ) +
                                                    ". " +
                                                    failureGuidance(
                                                        detail
                                                            ?: "The required screen element was not available."
                                                    )
                                            }
                                        }

                                    speakWithBargeIn(
                                        reply
                                    )
                                }
                            }

                            command is
                                JarvisCommand.ReadScreen ||
                                command is
                                JarvisCommand.ReadNotifications -> {

                                val screenResult =
                                    container
                                        .actionExecutor
                                        .execute(command)

                                val screenText =
                                    when (
                                        screenResult
                                    ) {
                                        is ExecutionResult.Success ->
                                            screenResult.message

                                        is ExecutionResult.Failure ->
                                            screenResult.message
                                    }

                                container
                                    .conversationRepository
                                    .addMessage(
                                        convId,
                                        "assistant",
                                        screenText
                                    )

                                speakWithBargeIn(
                                    screenText
                                )
                            }

                            command != null &&
                                !container
                                    .confirmationManager
                                    .required(command) -> {

                                speakStatus(
                                    "I will " +
                                        command
                                            .describe()
                                            .removeSuffix(".")
                                            .removeSuffix("?") +
                                        "."
                                )

                                val brainReply =
                                    container
                                        .brainCommandExecutor
                                        .execute(command)

                                val reply =
                                    brainReply
                                        ?: when (
                                            val execution =
                                                container
                                                    .actionExecutor
                                                    .execute(command)
                                        ) {
                                            is ExecutionResult.Success ->
                                                result
                                                    .replyText
                                                    .ifBlank {
                                                        "Done."
                                                    }

                                            is ExecutionResult.Failure ->
                                                "Command failed: " +
                                                    "${execution.message} " +
                                                    failureGuidance(
                                                        execution.message
                                                    )
                                        }

                                container
                                    .conversationRepository
                                    .addMessage(
                                        convId,
                                        "assistant",
                                        reply
                                    )

                                speakWithBargeIn(
                                    reply
                                )
                            }

                            command != null -> {

                                pendingConfirmation =
                                    command

                                speakWithBargeIn(
                                    "${command.describe()} Say yes or no."
                                )
                            }

                            else ->
                                speakWithBargeIn(
                                    result.replyText
                                )
                        }
                },

                onFailure = {
                    interrupted =
                        speakWithBargeIn(
                            "Sorry, something went wrong."
                        )
                }
            )

        return interrupted
    }

    /**
     * Handles the user's spoken answer to a pending confirmation.
     */
    private suspend fun handlePendingConfirmation(
        pending: JarvisCommand,
        answerText: String
    ): Boolean {

        val convId =
            ensureConversation()

        val answer =
            LocalIntentRouter
                .parseConfirmation(
                    answerText
                )

        return when (
            answer
        ) {

            true -> {

                pendingConfirmation =
                    null

                setState(
                    VoiceState.THINKING
                )

                val brainReply =
                    container
                        .brainCommandExecutor
                        .execute(pending)

                val reply =
                    brainReply
                        ?: when (
                            val result =
                                container
                                    .actionExecutor
                                    .execute(pending)
                        ) {

                            is ExecutionResult.Success -> {

                                if (
                                    pending is
                                    JarvisCommand.SendWhatsAppMessage
                                ) {
                                    when (
                                        val sendResult =
                                            container
                                                .actionExecutor
                                                .execute(
                                                    JarvisCommand.SendPendingMessage
                                                )
                                    ) {
                                        is ExecutionResult.Success ->
                                            "Sent."

                                        is ExecutionResult.Failure ->
                                            "Command failed while sending: " +
                                                "${sendResult.message} " +
                                                failureGuidance(
                                                    sendResult.message
                                                )
                                    }
                                } else {
                                    "Done."
                                }
                            }

                            is ExecutionResult.Failure ->
                                "Command failed: " +
                                    "${result.message} " +
                                    failureGuidance(
                                        result.message
                                    )
                        }

                container
                    .conversationRepository
                    .addMessage(
                        convId,
                        "assistant",
                        reply
                    )

                speakWithBargeIn(
                    reply
                )
            }

            false -> {

                pendingConfirmation =
                    null

                container
                    .conversationRepository
                    .addMessage(
                        convId,
                        "assistant",
                        "Cancelled."
                    )

                speakWithBargeIn(
                    "Cancelled."
                )
            }

            null -> {

                pendingConfirmation =
                    null

                val said =
                    speakWithBargeIn(
                        "I didn't catch that — " +
                            "please open JARVIS to approve it."
                    )

                openAppForConfirmation()

                said
            }
        }
    }

    private suspend fun handlePendingPlanConfirmation(
        plan: List<JarvisCommand>,
        answerText: String
    ): Boolean {

        val answer =
            LocalIntentRouter
                .parseConfirmation(
                    answerText
                )

        return when (
            answer
        ) {

            true -> {

                pendingPlan =
                    null

                setState(
                    VoiceState.THINKING
                )

                val task =
                    container
                        .taskEngine
                        .run(
                            "confirmed plan",
                            plan
                        )

                val reply =
                    when (
                        task.status
                    ) {

                        com.jarvis.assistant.agent
                            .TaskStatus.COMPLETED ->
                            "Task completed."

                        com.jarvis.assistant.agent
                            .TaskStatus.CANCELLED ->
                            "Cancelled."

                        else ->
                            "I couldn't complete the task."
                    }

                speakWithBargeIn(
                    reply
                )
            }

            false -> {

                pendingPlan =
                    null

                speakWithBargeIn(
                    "Cancelled."
                )
            }

            null ->
                speakWithBargeIn(
                    "Please say yes or no."
                )
        }
    }

    private suspend fun speakWithBargeIn(
        text: String
    ): Boolean {

        setState(
            VoiceState.SPEAKING
        )

        val prefs =
            container.securePrefs

        container
            .textToSpeechManager
            .setRate(
                prefs.speechRate
            )

        val voiceName =
            prefs.voiceName
                ?: container
                    .textToSpeechManager
                    .autoSelectMaleVoice()
                    ?.also {
                        prefs.voiceName =
                            it
                    }

        container
            .textToSpeechManager
            .setVoice(
                voiceName
            )

        val interrupted =
            AtomicBoolean(false)

        container
            .voiceActivityDetector
            .start {
                if (
                    interrupted.compareAndSet(
                        false,
                        true
                    )
                ) {
                    container
                        .textToSpeechManager
                        .stop()
                }
            }

        container
            .textToSpeechManager
            .speak(text)

        container
            .voiceActivityDetector
            .stop()

        return interrupted.get()
    }

    private fun failureGuidance(
        message: String
    ): String {

        val m =
            message.lowercase()

        return when {

            "accessibility" in m ||
                "screen automation" in m ->
                "Please enable JARVIS Accessibility and " +
                    "Screen automation in Settings, then try again."

            "isn't installed" in m ||
                "not installed" in m ->
                "Please check the app name or install that app first."

            "permission" in m ->
                "Please grant the required Android permission and try again."

            "search" in m ->
                "Please open the target app and make sure its search field is visible."

            "send" in m ->
                "Please make sure the correct chat is open and the Send button is visible."

            "volume" in m ->
                "Please check that Android allows volume control for the selected audio stream."

            else ->
                "I stopped safely instead of making random taps. " +
                    "You can retry after checking the current screen."
        }
    }

    private suspend fun ensureConversation(): Long {

        conversationId?.let {
            return it
        }

        val id =
            container
                .conversationRepository
                .createConversation(
                    "Background session"
                )

        conversationId = id

        return id
    }

    private fun setState(
        state: VoiceState
    ) {
        JarvisGlobalState.update(
            state
        )
    }

    private fun openAppForConfirmation() {

        val intent =
            packageManager
                .getLaunchIntentForPackage(
                    packageName
                )
                ?: Intent(
                    this,
                    MainActivity::class.java
                )

        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
        )

        startActivity(intent)
    }

    companion object {

        private const val CHANNEL_ID =
            "jarvis_background"

        private const val NOTIFICATION_ID =
            4201

        private const val WAKE_LOG_TAG =
            "JARVIS_WAKE"
    }
}
