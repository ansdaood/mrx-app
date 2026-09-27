package com.socialnetwork.app.ui

import com.socialnetwork.app.core.AppConstants
import com.socialnetwork.app.core.AppExecutors
import com.socialnetwork.app.auth.AuthUtils
import com.socialnetwork.app.sync.OfflineSyncCoordinator
import com.socialnetwork.app.core.ConnectivityMonitor
import com.socialnetwork.app.util.AppUtils
import com.socialnetwork.app.security.SecretStore
import com.socialnetwork.app.data.local.OfflineStore
import com.socialnetwork.app.data.model.HttpResult
import com.socialnetwork.app.data.model.UploadedMedia
import com.socialnetwork.app.network.ServiceEndpoints


import android.Manifest
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.VideoView
import android.widget.MediaController
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

import org.json.JSONArray
import org.json.JSONObject

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.math.BigInteger
import java.security.spec.X509EncodedKeySpec
import javax.security.auth.x500.X500Principal
import javax.crypto.spec.SecretKeySpec
import java.util.UUID
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Native Android entry point. No WebView and no website UI. */
open class MainActivity : ComponentActivity() {
    private val PREFS = AppConstants.PREFS
    private val ACCESS_TOKEN = AppConstants.ACCESS_TOKEN
    private val REFRESH_TOKEN = AppConstants.REFRESH_TOKEN
    private val CURRENT_USER_ID = AppConstants.CURRENT_USER_ID
    private val KEYSTORE = AppConstants.KEYSTORE
    private val KEY_ALIAS = AppConstants.KEY_ALIAS
    private val LEGACY_RSA_KEY_ALIAS = KEY_ALIAS + "_rsa"
    private val LEGACY_WRAPPED_AES = "wrapped_session_key_v1"
    private val PICK_MEDIA = AppConstants.PICK_MEDIA
    private val CAMERA_CAPTURE = AppConstants.CAMERA_CAPTURE
    private val MAX_MEDIA_BYTES = AppConstants.MAX_MEDIA_BYTES
    private val NETWORK_CONNECT_TIMEOUT_MS = AppConstants.NETWORK_CONNECT_TIMEOUT_MS
    private val NETWORK_READ_TIMEOUT_MS = AppConstants.NETWORK_READ_TIMEOUT_MS

    private val appExecutors = AppExecutors()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var selectedMedia: Uri? = null
    private var selectedMime: String? = null
    private var pendingCameraUri: Uri? = null
    private val refreshLock = Object()
    private lateinit var offlineStore: OfflineStore
    private lateinit var connectivityMonitor: ConnectivityMonitor
    private lateinit var offlineSyncCoordinator: OfflineSyncCoordinator
    private lateinit var secretStore: SecretStore
    @Volatile private var online = true
    private val syncHandler = Handler(Looper.getMainLooper())
    @Volatile private var syncInFlight = false
    @Volatile private var publishInFlight = false
    @Volatile private var storyInFlight = false
    @Volatile private var feedLoadInFlight = false
    @Volatile private var messageSendInFlight = false
    @Volatile private var profileUpdateInFlight = false
    @Volatile private var peopleLoadInFlight = false
    private val followTargetsInFlight = mutableSetOf<String>()
    @Volatile private var notificationsMarkReadInFlight = false
    private var pickingStoryMedia = false

    // Modern Android Activity Result APIs (2026). These replace the deprecated
    // startActivityForResult/onActivityResult and requestPermissions callbacks.
    private val mediaPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        handlePickedMedia(uri)
    }

    private val cameraPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true ||
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val storageGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted && storageGranted) {
            launchCameraCapture()
        } else {
            toast("يلزم السماح بالكاميرا والتخزين لالتقاط الصورة على هذا الإصدار من Android.")
        }
    }

    private val cameraCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        handleCameraCaptureResult(success)
    }
    @Volatile private var verifiedUserId = ""
    @Volatile private var verifiedUserIdAt = 0L
    private val VERIFIED_USER_CACHE_MS = AppConstants.VERIFIED_USER_CACHE_MS
    private val MAX_OFFLINE_SYNC_ATTEMPTS = 8
    private var feedOffset = 0
    private val FEED_PAGE_SIZE = AppConstants.FEED_PAGE_SIZE
    private val MAX_RESPONSE_BYTES = AppConstants.MAX_RESPONSE_BYTES
    private val MAX_REQUEST_BODY_BYTES = AppConstants.MAX_REQUEST_BODY_BYTES
    private val MAX_IMAGE_PREVIEW_BYTES = AppConstants.MAX_IMAGE_PREVIEW_BYTES
    private val TRANSIENT_RETRY_COUNT = AppConstants.TRANSIENT_RETRY_COUNT
    private val TRANSIENT_RETRY_BASE_MS = AppConstants.TRANSIENT_RETRY_BASE_MS
    private val TRANSIENT_RETRY_MAX_MS = AppConstants.TRANSIENT_RETRY_MAX_MS
    private val APP_NAME = "mr.x"
    private val AUTH_CALLBACK_SCHEME = "mrx"
    private val AUTH_CALLBACK_HOST = "auth"
    private val AUTH_CALLBACK_PATH = "/callback"
    private val RECOVERY_STATE = "recovery_state"
    private val RECOVERY_STATE_AT = "recovery_state_at"
    private val RECOVERY_STATE_TTL_MS = TimeUnit.MINUTES.toMillis(15)
    private val OFFLINE_POST_URL = "mrx://offline/post"
    private val OFFLINE_STORY_URL = "mrx://offline/story"
    @Volatile private var passwordResetInFlight = false
    @Volatile private var currentOperationId = ""

    private val services = ServiceEndpoints(
            BuildConfig.SUPABASE_URL,
            BuildConfig.SUPABASE_DATA_READ_URL,
            BuildConfig.SUPABASE_DATA_WRITE_URL,
            BuildConfig.SUPABASE_AUTH_URL,
            BuildConfig.SUPABASE_STORAGE_URL,
            BuildConfig.SUPABASE_PUBLISHABLE_KEY)

    private fun supabaseUrl(): String = services.primary
    private fun dataReadUrl(): String = services.clients.dataRead.baseUrl()
    private fun dataWriteUrl(): String = services.clients.dataWrite.baseUrl()
    private fun authUrl(): String = services.clients.auth.baseUrl()
    private fun storageUrl(): String = services.clients.storage.baseUrl()
    private fun supabaseKey(): String = services.key

    /** All feature requests are routed through the immutable service map. */
    private fun routeServiceEndpoint(method: String, url: String): String {
        return services.route(method, url) ?: url
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        configureSystemBars()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // Remove the legacy plaintext user-id cache; current identity binding is Keystore-backed.
        prefs.edit().remove("offline_user_id").apply()
        secretStore = SecretStore(this, prefs, KEYSTORE, KEY_ALIAS, LEGACY_WRAPPED_AES)
        offlineStore = OfflineStore(this)
        offlineSyncCoordinator = OfflineSyncCoordinator(offlineStore, MAX_OFFLINE_SYNC_ATTEMPTS,
                { online },
                this::hasSession,
                this::readSecret,
                this::refreshSessionIfNeeded,
                { method: String, url: String, body: String?, token: String ->
                    val result = request(method, url, body, token)
                    OfflineSyncCoordinator.HttpResult(result.code)
                },
                this::syncOfflineMediaOperation,
                this::pendingOperationBelongsToUser,
                { message: String -> postUi { status.text = message } },
                {
                    val activeOwner = readSecret(CURRENT_USER_ID)
                    if (online && !activeOwner.isEmpty() && offlineStore.hasRetryablePending(MAX_OFFLINE_SYNC_ATTEMPTS, activeOwner)) {
                        syncHandler.postAtTime(this::syncPendingOperations, "offline-sync", android.os.SystemClock.uptimeMillis() + 15000)
                    }
                    postUi(this::updateConnectivityStatus)
                })
        connectivityMonitor = ConnectivityMonitor(this, object : ConnectivityMonitor.Listener {
    override fun onConnectivityChanged(connected: Boolean) {
                online = connected
                updateConnectivityStatus()
            }
    override fun onConnectivityAvailable() {
                scheduleSync(250)
            }
        })
        online = connectivityMonitor.isNetworkAvailable()
        connectivityMonitor.register()
        ensureKeystoreKey()
        showBoot()
        if (!isConfigured()) showConfigurationError()
        else if (handleAuthCallback(intent)) return
        else if (hasSession()) {
            showFeed()
        } else {
            showLogin()
        }
    }

    private fun configureSystemBars() {
        AppUtils.configureSystemBars(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent
        if (!isFinishing && handleAuthCallback(intent)) return
    }

    override fun onStart() {
        super.onStart()
        online = isNetworkAvailable()
        if (online) scheduleSync(0)
    }

    override fun onStop() {
        super.onStop()
    }

    private fun isNetworkAvailable(): Boolean {
        return ::connectivityMonitor.isInitialized && connectivityMonitor.isNetworkAvailable()
    }

    private fun registerConnectivityMonitor() {
        if (::connectivityMonitor.isInitialized) connectivityMonitor.register()
    }

    private fun updateConnectivityStatus() {
        postUi {
            status.text = if (online) "متصل بالإنترنت · تتم المزامنة تلقائيًا" else "وضع عدم الاتصال · البيانات المحفوظة متاحة والتغييرات ستتزامن لاحقًا"
        }
    }

    private fun scheduleSync(delayMs: Long) {
        syncHandler.removeCallbacksAndMessages("offline-sync")
        syncHandler.postAtTime({ syncPendingOperations() }, "offline-sync", android.os.SystemClock.uptimeMillis() + maxOf(0L, delayMs))
    }

    private fun syncPendingOperations() {
        if (::offlineSyncCoordinator.isInitialized) {
            submitIo { offlineSyncCoordinator.runOnce() }
        }
    }

    override fun onDestroy() {
        if (isFinishing && pendingCameraUri != null) {
            try { contentResolver.delete(pendingCameraUri, null, null) } catch (_: Exception) {}
            pendingCameraUri = null
        }
        main.removeCallbacksAndMessages(null)
        syncHandler.removeCallbacksAndMessages(null)
        if (::connectivityMonitor.isInitialized) connectivityMonitor.unregister()
        appExecutors.shutdown()
        super.onDestroy()
    }

    private fun isConfigured(): Boolean {
        return try {
            services.configured() && !supabaseKey().contains("xxx") && !supabaseKey().contains("YOUR_PUBLIC")
        } catch (_: Exception) {
            false
        }
    }

    private fun showConfigurationError() {
        base("إعداد التطبيق")
        status.text = "لم يتم إعداد Supabase بعد. ضع SUPABASE_URL وSUPABASE_PUBLISHABLE_KEY في supabase.properties ثم أعد البناء."
    }

    private fun showBoot() {
        base(APP_NAME)
        val p = ProgressBar(this)
        root.addView(p, LinearLayout.LayoutParams(-1, 80))
        status.text = "جارٍ تشغيل التطبيق..."
    }

    private fun hasSession(): Boolean = readSecret(ACCESS_TOKEN).isNotEmpty() && readSecret(REFRESH_TOKEN).isNotEmpty()

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(fill: Int, radiusDp: Float): GradientDrawable {
        val d = GradientDrawable()
        d.color = fill
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun beginOperation(): String {
        currentOperationId = UUID.randomUUID().toString()
        return currentOperationId
    }

    private fun currentOperationId(): String {
        if (currentOperationId.isBlank()) return beginOperation()
        return currentOperationId
    }

    private fun base(title: String) {
        beginOperation()
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(8), dp(16), dp(8))
        root.setBackgroundColor(Color.rgb(250,250,250))

        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(0, dp(4), 0, dp(10))
        val brandBlock = LinearLayout(this)
        brandBlock.orientation = LinearLayout.VERTICAL
        brandBlock.gravity = Gravity.CENTER_VERTICAL
        val heading = TextView(this)
        heading.text = APP_NAME
        heading.textSize = 23f
        heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        heading.setTextColor(Color.rgb(18,18,18))
        brandBlock.addView(heading, LinearLayout.LayoutParams(-1, -2))
        val operationIdView = TextView(this)
        operationIdView.text = "ID: " + currentOperationId()
        operationIdView.textSize = 9f
        operationIdView.setTextColor(Color.rgb(105,105,105))
        operationIdView.isSingleLine = true
        operationIdView.contentDescription = "معرّف عملية mr.x: " + currentOperationId()
        brandBlock.addView(operationIdView, LinearLayout.LayoutParams(-1, -2))
        top.addView(brandBlock, LinearLayout.LayoutParams(0, -2, 1))
        val dot = TextView(this)
        dot.text = "♡   ✉"
        dot.textSize = 21f
        dot.setTextColor(Color.rgb(18,18,18))
        dot.contentDescription = "معلومات وحقوق تطبيق mr.x"
        dot.setOnClickListener { showLegalNotice() }
        top.addView(dot, LinearLayout.LayoutParams(-2, -2))
        root.addView(top)

        status = TextView(this)
        status.setTextColor(Color.rgb(105,105,105))
        status.textSize = 12f
        status.setPadding(0, 0, 0, dp(6))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        updateConnectivityStatus()
        val baseLeft = dp(16)
        val baseTop = dp(8)
        val baseRight = dp(16)
        val baseBottom = dp(8)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun showLegalNotice() {
        var text =
                "mr.x\n\n" +
                "Copyright © 2026 mr.x. All rights reserved.\n\n" +
                "هذا التطبيق والاسم والعلامات والهوية البصرية والشفرة البرمجية الأصلية الخاصة بالمشروع محمية بالحقوق المطبقة عليها. لا يجوز نسخ أو إعادة توزيع أو تعديل أو بيع أو إعادة نشر الأجزاء الأصلية من المشروع دون إذن من صاحب الحقوق، ما لم ينص ترخيص مستقل على خلاف ذلك.\n\n" +
                "المكونات أو الخدمات التابعة لأطراف أخرى تظل خاضعة لتراخيصها وشروطها الخاصة، ولا يمنح هذا الإشعار حقوقًا إضافية عليها. استخدام Supabase أو أي خدمة خارجية لا يعني أن تلك الخدمة مملوكة لـ mr.x.\n\n" +
                "هذا الإشعار يوضح حقوق الملكية للمشروع ولا يُعد بديلًا عن التسجيل الرسمي للحقوق أو العلامات التجارية حيث يكون التسجيل مطلوبًا قانونًا."
        android.app.AlertDialog.Builder(this)
                .setTitle("حول mr.x وحقوق النشر")
                .setMessage(text)
                .setPositiveButton("إغلاق", null)
                .show()
    }

    private fun input(hint: String, password: Boolean): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            isSingleLine = true
            setPadding(18, 14, 18, 14)
        }
        if (password) e.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        root.addView(e, LinearLayout.LayoutParams(-1, -2))
        return e
    }

    private fun button(text: String): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 14f
        b.isAllCaps = false
        b.setTextColor(Color.rgb(25,25,25))
        b.setPadding(dp(14), dp(8), dp(14), dp(8))
        b.setBackground(rounded(Color.WHITE, 18))
        root.addView(b, LinearLayout.LayoutParams(-1, dp(48)))
        return b
    }

    private fun showLogin() {
        base(APP_NAME)
        status.text = "تسجيل الدخول إلى mr.x"
        val email = input("البريد الإلكتروني", false)
        val password = input("كلمة المرور", true)
        val login = button("تسجيل الدخول")
        val signup = button("إنشاء حساب")
        val forgot = button("نسيت كلمة المرور؟")
        login.setOnClickListener { authenticate(email.text.toString().trim(), password.text.toString(), false) }
        signup.setOnClickListener { authenticate(email.text.toString().trim(), password.text.toString(), true) }
        forgot.setOnClickListener { requestPasswordReset(email.text.toString().trim()) }
    }

    private fun randomRecoveryState(): String {
        return AuthUtils.randomRecoveryState()
    }

    private fun clearRecoveryState() {
        try { prefs.edit().remove(RECOVERY_STATE_AT).apply() } catch (_: Exception) {}
        try { prefs.edit().remove(RECOVERY_STATE).apply() } catch (_: Exception) {}
    }

    private fun requestPasswordReset(email: String) {
        if (!AppUtils.isValidEmail(email)) { toast("أدخل بريدًا إلكترونيًا صحيحًا أولًا."); return; }
        if (passwordResetInFlight) return
        passwordResetInFlight = true
        status.text = "جارٍ إرسال رسالة استعادة كلمة المرور..."
        if (!submitIo({
            try {
                val recoveryState = randomRecoveryState()
                saveSecret(RECOVERY_STATE, recoveryState)
                prefs.edit().putLong(RECOVERY_STATE_AT, System.currentTimeMillis()).apply()
                val redirectTo = "$AUTH_CALLBACK_SCHEME://$AUTH_CALLBACK_HOST$AUTH_CALLBACK_PATH"
                        + "?state=" + Uri.encode(recoveryState)
                val body = JSONObject().put("email", email).put("redirect_to", redirectTo)
                val r = request("POST", supabaseUrl() + "/auth/v1/recover", body.toString(), null)
                if (!AuthUtils.isSuccessfulHttp(r.code)) throw Exception(AppUtils.errorMessage(r.body))
                postUi { status.text = "إذا كان البريد مرتبطًا بحساب، ستصلك الآن رسالة من mr.x لإعادة تعيين كلمة المرور." }
            } catch (e: Exception) {
                clearRecoveryState()
                postUi { status.text = "تعذر إرسال رسالة الاستعادة: " + AppUtils.safeMessage(e) }
            } finally { passwordResetInFlight = false
            }
        })) {
            passwordResetInFlight = false
        }
    }

        private fun handleAuthCallback(intent: Intent): Boolean {
        val data = intent.data ?: return false
        if (!AUTH_CALLBACK_SCHEME.equals(data.scheme, ignoreCase = true)
                || !AUTH_CALLBACK_HOST.equals(data.host, ignoreCase = true)
                || AUTH_CALLBACK_PATH != data.path) return false
        var callbackState = data.getQueryParameter("state")
        var access = data.getQueryParameter("access_token")
        var refresh = data.getQueryParameter("refresh_token")
        var error = data.getQueryParameter("error_description")
        if (access == null || access.isEmpty()) {
            var fragment = data.fragment
            if (fragment != null) {
                for (part in fragment.split("&")) {
                    var eq = part.indexOf('=')
                    if (eq <= 0) continue
                    try {
                        var k = Uri.decode(part.substring(0, eq))
                        var v = Uri.decode(part.substring(eq + 1))
                        if ("access_token".equals(k)) access = v
                        else if ("refresh_token".equals(k)) refresh = v
                        else if ("error_description".equals(k)) error = v
                        else if ("state".equals(k)) callbackState = v
                    } catch (_: Exception) {}
                }
            }
        }
        val expectedState = readSecret(RECOVERY_STATE)
        val issuedAt = prefs.getLong(RECOVERY_STATE_AT, 0L)
        val stateValid = expectedState.isNotEmpty() && !callbackState.isNullOrEmpty()
                && AppUtils.constantTimeEquals(expectedState, callbackState)
                && AuthUtils.isRecoveryStateFresh(issuedAt, System.currentTimeMillis(), RECOVERY_STATE_TTL_MS)
        if (!stateValid) {
            base(APP_NAME)
            status.text = "رابط الاستعادة غير صالح أو منتهي."
            return true
        }
        clearRecoveryState()
        if (error != null && error.isNotEmpty()) {
            base(APP_NAME)
            status.text = "تعذر فتح رابط الاستعادة: " + error
            return true
        }
        if (access == null || access.isEmpty()) {
            base(APP_NAME)
            status.text = "رابط الاستعادة غير صالح أو منتهي."
            return true
        }
        refresh = refresh.orEmpty()
        try { saveSecret(ACCESS_TOKEN, access); if (refresh.isNotEmpty()) saveSecret(REFRESH_TOKEN, refresh); }
        catch (e: Exception) { clearSecrets(); base(APP_NAME); status.text = "تعذر تأمين جلسة الاستعادة."; return true; }
        showPasswordUpdate()
        return true
    }

    private fun showPasswordUpdate() {
        base(APP_NAME)
        status.text = "أنشئ كلمة مرور جديدة لحسابك."
        val password = input("كلمة المرور الجديدة", true)
        val confirm = input("تأكيد كلمة المرور", true)
        val update = button("تحديث كلمة المرور")
        val cancel = button("إلغاء")
        update.setOnClickListener { updatePassword(password.text.toString(), confirm.text.toString()) }
        cancel.setOnClickListener { clearSecrets()
        showLogin() }
    }

    private fun updatePassword(password: String, confirm: String) {
        if (password.length < 8) { toast("كلمة المرور يجب أن تكون 8 أحرف على الأقل."); return; }
        if (password != confirm) { toast("كلمتا المرور غير متطابقتين."); return; }
        status.text = "جارٍ تحديث كلمة المرور..."
        submitIo({
            try {
                val r = authenticatedRequest("PUT", supabaseUrl() + "/auth/v1/user", JSONObject().put("password", password).toString())
                if (r.code < 200 || r.code >= 300) throw Exception(AppUtils.errorMessage(r.body))
                postUi { toast("تم تغيير كلمة المرور بنجاح."); showFeed() }
            } catch (e: Exception) { postUi { status.text = "تعذر تغيير كلمة المرور: " + AppUtils.safeMessage(e) } }
        })
    }

    private fun authenticate(email: String, password: String, signup: Boolean) {
        if (email.isEmpty() || password.length < 8){toast("أدخل بريدًا صحيحًا وكلمة مرور من 8 أحرف على الأقل.");return;}
        status.text = "جارٍ الاتصال بالخادم..."
        submitIo({
            try {
                var endpoint =(if (signup) "/auth/v1/signup" else "/auth/v1/token?grant_type=password")
                val body = JSONObject().put("email",email).put("password",password)
                val r = request("POST",supabaseUrl()+endpoint,body.toString(),null)
                if(r.code<200||r.code>=300) throw Exception(AppUtils.errorMessage(r.body))
                val json = JSONObject(r.body)
                var token = json.optString("access_token", "")
                var refresh = json.optString("refresh_token", "")
                if (signup && token.isEmpty()){ postUi { status.text = "تم إنشاء الحساب. إذا كان تأكيد البريد مفعّلًا، أكد البريد ثم سجّل الدخول." }; return }
                if (token.isEmpty() || refresh.isEmpty()) throw Exception("الخادم لم يُرجع جلسة كاملة.")
                try {
                    saveSecret(ACCESS_TOKEN,token)
                    saveSecret(REFRESH_TOKEN,refresh)
                    // Bind the offline queue to the authenticated account immediately.
                    // Supabase password-token responses normally include the user object
                    // if a deployment omits it, currentUserId() will verify it lazily later.
                    var userId = json.optJSONObject("user")?.optString("id", "") ?: ""
                    if (AppUtils.isUuid(userId)) saveSecret(CURRENT_USER_ID, userId)
                    clearRecoveryState()
                } catch (secureError: Exception) {
                    clearSecrets()
                    throw secureError
                }
                postUi(this::showFeed)
            }catch (e: Exception){postUi { status.text = "فشل تسجيل الدخول: "+AppUtils.safeMessage(e) };}
        })
    }

    private fun queueOfflineMedia(targetUrl: String, media: Uri, mime: String, metadata: JSONObject): Boolean {
        val extension = AppUtils.mediaExtension(mime) ?: return false
        try {
            val size = querySize(media)
            if (size > MAX_MEDIA_BYTES) throw Exception("حجم الملف أكبر من 50MB.")
            val dir = File(filesDir, "offline-media")
            if (!dir.exists() && !dir.mkdirs()) throw Exception("تعذر إنشاء مساحة التخزين المحلية.")
            val file = File(dir, UUID.randomUUID().toString()+"."+extension)
            contentResolver.openInputStream(media)?.use { input: InputStream ->
                FileOutputStream(file).use { out: OutputStream ->
                val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        if (total > MAX_MEDIA_BYTES - n) throw Exception("حجم الملف أكبر من 50MB.")
                        out.write(buf, 0, n)
                        total += n
                    }
                }
            } ?: throw Exception("تعذر قراءة الوسائط.")
            metadata.put("_offline_media_path", file.absolutePath)
            metadata.put("_offline_media_mime", mime)
            var ownerUserId = readSecret(CURRENT_USER_ID)
            if (ownerUserId.isEmpty()) throw Exception("تعذر ربط العملية بحساب المستخدم الحالي.")
            var queuedId = offlineStore.enqueue("POST", targetUrl, metadata.toString(), ownerUserId, currentOperationId())
            if (queuedId < 0) {
                try { file.delete(); } catch (_: Exception) {}
                throw Exception("تعذر إضافة عملية الوسائط إلى طابور Offline.")
            }
            postUi { status.text = "تم حفظ الوسائط محليًا. ستُرفع تلقائيًا عند عودة الإنترنت." }
            return true
        } catch (e: Exception) {
            toast("تعذر حفظ الوسائط للعمل Offline: "+AppUtils.safeMessage(e))
            return false
        }
    }

    private fun pendingOperationBelongsToUser(op: OfflineStore.PendingOperation, userId: String): Boolean {
        if (userId.isNullOrEmpty() || op.body == null) return false
        try {
            val body = JSONObject(op.body)
            val ownerFields = arrayOf("user_id", "sender_id", "follower_id")
            for (field in ownerFields) {
                var value = body.optString(field, "")
                if (!value.isEmpty()) return userId.equals(value)
            }
            var url = op.url ?: ""
            return url.contains("id=eq." + userId) || url.contains("user_id=eq." + userId)
                    || url.contains("follower_id=eq." + userId)
        } catch (_: Exception) { return false }
    }

    private fun syncOfflineMediaOperation(op: OfflineStore.PendingOperation): Boolean {
        val m = JSONObject(op.body ?: "{}")
        var path =m.optString("_offline_media_path","")
        var mime =m.optString("_offline_media_mime","")
        if (path.isEmpty() || mime.isEmpty()) return true
        val root = File(filesDir, "offline-media").canonicalFile
        val f = File(path).canonicalFile
        if (f.parentFile?.canonicalPath != root.canonicalPath || !f.isFile) return true
        val uri = Uri.fromFile(f)
        var folder =(if (OFFLINE_STORY_URL == op.url) "stories" else "posts")
        val uploaded = uploadMedia(uri,mime,folder)
        try {
            var userId =currentUserId()
            val body = JSONObject()
            if (OFFLINE_STORY_URL == op.url) {
                body.put("user_id",userId).put("media_url",uploaded.url)
                    .put("media_type",(if (mime.startsWith("video/")) "video" else "image"))
                    .put("caption",(if (m.optString("caption","").isEmpty()) JSONObject.NULL else m.optString("caption")))
                val r = request("POST",supabaseUrl()+"/rest/v1/stories",body.toString(),readSecret(ACCESS_TOKEN))
                if(r.code<200||r.code>=300) throw Exception(AppUtils.errorMessage(r.body))
            } else {
                var content =m.optString("content","")
                body.put("user_id",userId).put("content",(if (content.isEmpty()) JSONObject.NULL else content))
                    .put("media_urls",JSONArray().put(uploaded.url))
                    .put("media_type",(if (mime.startsWith("video/")) "video" else "image"))
                val r = request("POST",supabaseUrl()+"/rest/v1/posts",body.toString(),readSecret(ACCESS_TOKEN))
                if(r.code<200||r.code>=300) throw Exception(AppUtils.errorMessage(r.body))
            }
            if(!f.delete()) f.deleteOnExit()
            return true
        } catch (e: Exception) {
            deleteMedia(uploaded.path)
            throw e
        }
    }

    private fun showFeed() {
        base(APP_NAME)

        val storiesBar = LinearLayout(this)
        storiesBar.orientation = LinearLayout.HORIZONTAL
        storiesBar.setPadding(0, dp(4), 0, dp(12))
        root.addView(storiesBar)
        loadStoryBar(storiesBar)

        val quick = LinearLayout(this)
        quick.orientation = LinearLayout.HORIZONTAL
        val refresh = buttonIn(quick,"⌕ تحديث"); val create = buttonIn(quick,"＋ منشور"); val more = buttonIn(quick,"المزيد")
        refresh.setOnClickListener { feedOffset = 0
        loadPosts(postsContainer, false) }
        create.setOnClickListener { showCreatePost(postsContainer) }
        more.setOnClickListener { loadPosts(postsContainer, true) }
        root.addView(quick,LinearLayout.LayoutParams(-1,dp(48)))

        val scroll = ScrollView(this)
        val posts = LinearLayout(this)
        posts.orientation = LinearLayout.VERTICAL
        posts.setPadding(0,dp(8),0,dp(12))
        postsContainer=posts
        scroll.addView(posts)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1))

        val nav = LinearLayout(this)
        nav.gravity = Gravity.CENTER
        nav.setPadding(0,dp(6),0,0)
        val home = buttonIn(nav,"⌂\nالرئيسية")
        val people = buttonIn(nav,"⌕\nاستكشاف")
        val notifications = buttonIn(nav,"♡\nإشعارات")
        val messages = buttonIn(nav,"✉\nرسائل")
        val profile = buttonIn(nav,"◎\nحسابي")
        home.setOnClickListener { feedOffset = 0
        loadPosts(postsContainer, false) }
        people.setOnClickListener { showPeople() }
        notifications.setOnClickListener { showNotifications() }
        messages.setOnClickListener { showMessages() }
        profile.setOnClickListener { showProfile() }
        root.addView(nav,LinearLayout.LayoutParams(-1,dp(58)))

        storiesBar.setOnClickListener { showStories() }
        loadPosts(posts,false)
    }

    private var postsContainer: LinearLayout? = null

    /** Builds the story strip from live backend data
    no sample users are shown. */
    private fun loadStoryBar(storiesBar: LinearLayout) {
        storiesBar.removeAllViews()
        val own = TextView(this)
        own.text = "◉\nقصتك"
        own.gravity = Gravity.CENTER
        own.textSize = 11f
        own.setTextColor(Color.DKGRAY)
        own.setBackground(rounded(Color.WHITE, 32))
        own.setOnClickListener { showStories() }
        val ownParams = LinearLayout.LayoutParams(dp(68), dp(68))
        ownParams.setMargins(0, 0, dp(8), 0)
        storiesBar.addView(own, ownParams)

        submitIo({
            try {
                var now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
                var filter = URLEncoder.encode(now, "UTF-8").replace("+", "%20")
                var url = supabaseUrl() + "/rest/v1/stories?select=user_id&expires_at=gt." + filter + "&order=created_at.desc&limit=50"
                val r = authenticatedRequest("GET", url, null)
                if (r.code < 200 || r.code >= 300) throw Exception(AppUtils.errorMessage(r.body))
                val stories = JSONArray(r.body)
                val ids = linkedSetOf<String>()
                for (i in 0 until stories.length) {
                    val story = stories.optJSONObject(i)
                    var id = story?.optString("user_id", "") ?: ""
                    if (AppUtils.isUuid(id)) ids.add(id)
                }
                if (ids.isEmpty()) return
                val inBuilder = StringBuilder()
                for (id in ids) {
                    if (inBuilder.length > 0) inBuilder.append(',')
                    inBuilder.append(id)
                }
                var profileUrl = supabaseUrl() + "/rest/v1/profiles?select=id,username,display_name&id=in.(" + inBuilder + ")&limit=50"
                val profilesResult = authenticatedRequest("GET", profileUrl, null)
                if (profilesResult.code < 200 || profilesResult.code >= 300) throw Exception(AppUtils.errorMessage(profilesResult.body))
                val profiles = JSONArray(profilesResult.body)
                val names = mutableMapOf<String, String>()
                for (i in 0 until profiles.length) {
                    val profile = profiles.optJSONObject(i)
                    if (profile == null) continue
                    var id = profile.optString("id", "")
                    var name = profile.optString("display_name", "").trim()
                    if (name.isEmpty()) name = profile.optString("username", "").trim()
                    if (!name.isEmpty() && AppUtils.isUuid(id)) names[id] = name
                }
                postUi {
                    for (id in ids) {
                        var name = names[id]
                        if (name == null || name.isEmpty()) continue
                        val bubble = TextView(this)
                        bubble.text = "◉\n" + name
                        bubble.gravity = Gravity.CENTER
                        bubble.textSize = 11f
                        bubble.setTextColor(Color.DKGRAY)
                        bubble.setBackground(rounded(Color.WHITE, 32))
                        bubble.setOnClickListener { showStories() }
                        val bp = LinearLayout.LayoutParams(dp(68), dp(68))
                        bp.setMargins(0, 0, dp(8), 0)
                        storiesBar.addView(bubble, bp)
                    }
                }
            } catch (ignored: Exception) {
                // The feed remains usable when the optional story strip cannot load.
            }
        })
    }

    private fun loadPosts(posts: LinearLayout, append: Boolean) {
        if (feedLoadInFlight) return
        feedLoadInFlight = true
        status.text = "جارٍ تحميل المنشورات..."
        var requestedOffset = (if (append) feedOffset + FEED_PAGE_SIZE  else 0)
        if (!submitIo({
            try{
                var url =supabaseUrl()+"/rest/v1/posts?select=id,user_id,content,media_urls,media_type,likes_count,comments_count,created_at&order=created_at.desc&limit="+FEED_PAGE_SIZE+"&offset="+requestedOffset
                val r = authenticatedRequest("GET",url,null)
                if(r.code==401){feedLoadInFlight=false
                clearSecrets()
                postUi(this::showLogin)
                return
                }
                if(r.code<200||r.code>=300)throw Exception(AppUtils.errorMessage(r.body))
                val a = JSONArray(r.body)
                postUi {
                    if(!append) posts.removeAllViews()
                    feedOffset=requestedOffset
                    for (i in 0 until a.length){
                        try{
                            val post = a.getJSONObject(i)
                            addPostCard(posts,post)
                        }catch (ignored: Exception){}
                    }
                    status.text = a.length+" منشورًا تم تحميلها. الصفحة الحالية تبدأ من "+(feedOffset+1)+"."
                    feedLoadInFlight = false
                }
            }catch (e: Exception){
                feedLoadInFlight = false
                postUi { status.text = "تعذر تحميل المنشورات: "+AppUtils.safeMessage(e) }
            }
        })) {
            feedLoadInFlight = false
        }
    }


    private fun currentUserId(): String {
        var now = System.currentTimeMillis()
        if (!verifiedUserId.isEmpty() && now - verifiedUserIdAt < VERIFIED_USER_CACHE_MS) return verifiedUserId
        try {
            val r = authenticatedRequest("GET", supabaseUrl() + "/auth/v1/user", null)
            if (r.code < 200 || r.code >= 300) throw Exception(AppUtils.errorMessage(r.body))
            val user = JSONObject(r.body)
            val id = user.optString("id", "")
            if (!AppUtils.isUuid(id)) throw Exception("جلسة المستخدم غير صالحة.")
            saveSecret(CURRENT_USER_ID, id)
            verifiedUserId = id
            verifiedUserIdAt = now
            return id
        } catch (networkError: Exception) {
            var cached = readSecret(CURRENT_USER_ID)
            if (!cached.isEmpty() && AppUtils.isUuid(cached)) { verifiedUserId = cached
            verifiedUserIdAt = now
            return cached
            }
            throw networkError
        }
    }

    private fun showProfile() {
        base("الملف الشخصي")
        val back = button("العودة"); back.setOnClickListener { showFeed() }
        submitIo({
            try {
                var id =currentUserId()
                val r = authenticatedRequest("GET",supabaseUrl()+"/rest/v1/profiles?id=eq."+id+"&select=id,username,display_name,bio,avatar_url,cover_url,website,location,is_verified,is_private,followers_count,following_count,posts_count&limit=1",null)
                if(r.code<200||r.code>=300) throw Exception(AppUtils.errorMessage(r.body))
                val a = JSONArray(r.body); if(a.length==0) throw Exception("الملف الشخصي غير موجود.")
                val me = a.getJSONObject(0)
                postUi {
                    val username = input("اسم المستخدم",false); username.text = me.optString("username","")
                    val name = input("الاسم الظاهر",false); name.text = me.optString("display_name","")
                    val bio = input("النبذة",false); bio.text = me.optString("bio","")
                    val location = input("الموقع",false); location.text = me.optString("location","")
                    val website = input("الموقع الإلكتروني",false); website.text = me.optString("website","")
                    val save = button("حفظ التعديلات"); val privateBtn = button((if (me.optBoolean("is_private",false)) "الحساب خاص — اضغط للتبديل" else "الحساب عام — اضغط للتبديل"))
                    val logout = button("تسجيل الخروج")
                    logout.setOnClickListener { logout() }
                    val stats = TextView(this); stats.text = "المنشورات: "+me.optInt("posts_count")+"   المتابعون: "+me.optInt("followers_count")+"   المتابَعون: "+me.optInt("following_count"); stats.setTextColor(Color.DKGRAY); root.addView(stats)
                    val isPrivate = booleanArrayOf(me.optBoolean("is_private",false))
                    privateBtn.setOnClickListener { isPrivate[0] = !isPrivate[0]; privateBtn.text = if (isPrivate[0]) "الحساب خاص — اضغط للتبديل" else "الحساب عام — اضغط للتبديل" }
                    save.setOnClickListener { updateProfile(username.text.toString().trim(), name.text.toString().trim(), bio.text.toString().trim(), location.text.toString().trim(), website.text.toString().trim(), isPrivate[0]) }
                }
            } catch (e: Exception){ postUi { status.text = "تعذر تحميل الملف: "+AppUtils.safeMessage(e) } }
        })
    }

    private fun logout() {
        if (passwordResetInFlight || profileUpdateInFlight) return
        submitIo({
            try {
                var token =readSecret(ACCESS_TOKEN)
                if(token.isNotEmpty() && isNetworkAvailable()) {
                    try { request("POST", authUrl()+"/auth/v1/logout", null, token); } catch (ignored: Exception) { }
                }
            } finally {
                offlineStore.clearCache()
                clearRecoveryState()
                clearSecrets()
                postUi(this::showLogin)
            }
        })
    }

    private fun updateProfile(username: String, name: String, bio: String, location: String, website: String, isPrivate: Boolean) {
        if(username.isEmpty()||name.isEmpty()){toast("اسم المستخدم والاسم الظاهر مطلوبان.");return;}
        if(username.length>32||name.length>100||bio.length>2000||location.length>120||website.length>500){toast("بيانات الملف أطول من الحد المسموح.");return;}
        if(profileUpdateInFlight) return
        profileUpdateInFlight=true
        if (!submitIo({
            try{
                var id =currentUserId()
                val body = JSONObject().put("username",username).put("display_name",name).put("bio",(if (bio.isEmpty()) JSONObject.NULL else bio)).put("location",(if (location.isEmpty()) JSONObject.NULL else location)).put("website",(if (website.isEmpty()) JSONObject.NULL else website)).put("is_private",isPrivate)
                val r = authenticatedRequest("PATCH",supabaseUrl()+"/rest/v1/profiles?id=eq."+id,body.toString())
                if(r.code<200||r.code>=300)throw Exception(AppUtils.errorMessage(r.body))
                postUi {toast((if (isOfflineQueued(r)) "تم حفظ تعديل الملف Offline وسيُزامن عند الاتصال." else "تم تحديث الملف الشخصي."));showProfile()}
            }catch (e: Exception){postUi { toast("تعذر حفظ الملف: "+AppUtils.safeMessage(e)) };}
            finally { profileUpdateInFlight=false
            }
        })) {
            profileUpdateInFlight=false
        }
    }

    private fun showPeople() {
        base("المستخدمون والمتابعة"); val back = button("العودة"); back.setOnClickListener { showFeed() }
        val search = input("بحث باسم المستخدم",false); val find = button("بحث")
        val scroll = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1))
        find.setOnClickListener { loadPeople(list, search.text.toString().trim()) }; loadPeople(list,"")
    }

    private fun loadPeople(list: LinearLayout, search: String) {
        if(peopleLoadInFlight) return
        peopleLoadInFlight = true
        if (!submitIo({
            try{
                var normalizedSearch = AppUtils.normalizeUsernameSearch(search)
                var safeSearch =URLEncoder.encode(normalizedSearch,"UTF-8").replace("+","%20")
                var q =(if (normalizedSearch.isEmpty()) "" else "&username=ilike.*"+safeSearch+"*")
                val r = authenticatedRequest("GET",supabaseUrl()+"/rest/v1/profiles?select=id,username,display_name,bio,is_private,followers_count&order=created_at.desc&limit=50"+q,null)
                if(r.code<200||r.code>=300)throw Exception(AppUtils.errorMessage(r.body))
                val a = JSONArray(r.body)
                val me = currentUserId()
                postUi { list.removeAllViews(); for (i in 0 until a.length) { try { val u = a.getJSONObject(i); if (me == u.optString("id")) continue; addPerson(list,u) } catch (_: Exception) {} }; status.text = a.length.toString()+" ملفًا متاحًا." }
            }catch (e: Exception){postUi { status.text = "تعذر تحميل المستخدمين: "+AppUtils.safeMessage(e) };}
            finally { peopleLoadInFlight = false
            }
        })) {
            peopleLoadInFlight = false
        }
    }

        private fun addPerson(list: LinearLayout, u: JSONObject) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(14,14,14,14)
        val t = TextView(this);t.text = u.optString("display_name",u.optString("username"))+"  @"+u.optString("username")+"\nمتابعون: "+u.optInt("followers_count");t.textSize = 17f;row.addView(t)
        val follow = buttonIn(row,"متابعة / إلغاء المتابعة"); follow.setOnClickListener { toggleFollow(u.optString("id"), follow) }; list.addView(row,LinearLayout.LayoutParams(-1,-2))
    }

    private fun toggleFollow(target: String, b: Button) {
        if (!AppUtils.isUuid(target)) return
        synchronized(followTargetsInFlight){
            if(!followTargetsInFlight.add(target)) return
        }
        b.isEnabled = false
        submitIo({
            try{
                var me =currentUserId()
                if (me.isEmpty() || me == target) throw Exception("حساب المتابعة غير صالح.")
                var q =supabaseUrl()+"/rest/v1/follows?select=id&follower_id=eq."+me+"&following_id=eq."+target+"&limit=1"
                val c = authenticatedRequest("GET",q,null)
                if(c.code<200||c.code>=300)throw Exception(AppUtils.errorMessage(c.body))
                val a = JSONArray(c.body)
                val r: HttpResult
                if(a.length>0){
                    r=authenticatedRequest("DELETE",supabaseUrl()+"/rest/v1/follows?follower_id=eq."+me+"&following_id=eq."+target,null)
                }else{
                    r=authenticatedRequest("POST",supabaseUrl()+"/rest/v1/follows",JSONObject().put("follower_id",me).put("following_id",target).toString())
                }
                if(r.code<200||r.code>=300)throw Exception(AppUtils.errorMessage(r.body))
                val removed = a.length > 0
                postUi { b.isEnabled = true; toast(if (isOfflineQueued(r)) "تم حفظ تغيير المتابعة Offline وسيُزامن عند الاتصال." else if (removed) "تم إلغاء المتابعة." else "تمت المتابعة.") }
            }catch (e: Exception){
                postUi { b.isEnabled = true; toast("تعذر تحديث المتابعة: " + AppUtils.safeMessage(e)) }
            }finally{
                followTargetsInFlight.remove(target)
            }
        })
    }

    private fun showNotifications() {
        base("الإشعارات"); val back = button("العودة"); back.setOnClickListener { showFeed() }; val s = ScrollView(this);val list = LinearLayout(this);list.orientation = LinearLayout.VERTICAL;s.addView(list);root.addView(s,LinearLayout.LayoutParams(-1,0,1))
        submitIo({
            try{
                var id =currentUserId()
                val r = authenticatedRequest("GET",supabaseUrl()+"/rest/v1/notifications?user_id=eq."+id+"&select=id,type,actor_id,post_id,comment_id,is_read,created_at&order=created_at.desc&limit=100",null)
                if(r.code<200||r.code>=300)throw Exception(AppUtils.errorMessage(r.body))
                val a = JSONArray(r.body)
                val unread = StringBuilder()
                for (i in 0 until a.length){
                    val n = a.optJSONObject(i)
                    if(n!=null&&!n.optBoolean("is_read",false)){
                        var nid =n.optString("id","")
                        if(AppUtils.isUuid(nid)){ if (unread.isNotEmpty()) unread.append(','); unread.append(nid); }
                    }
                }
                val unreadIds = unread.toString()
                postUi {
                    for (i in 0 until a.length) { val n = a.optJSONObject(i); if (n == null) continue; val t = TextView(this); t.text = notificationText(n)+"\n"+n.optString("created_at"); t.setPadding(10,16,10,16); list.addView(t) }
                    status.text = a.length.toString()+" إشعارًا."
                }
                if (unreadIds.isNotEmpty()) markNotificationsRead(id,unreadIds)
            }catch (e: Exception){postUi { status.text = "تعذر تحميل الإشعارات: "+AppUtils.safeMessage(e) };}
        })
    }

    private fun notificationText(n: JSONObject): String {var type =n.optString("type");if(type == "like")return "♥ لديك إعجاب جديد";if(type == "comment")return "💬 لديك تعليق جديد";if(type == "follow")return "👤 لديك متابع جديد";if(type == "message")return "✉ لديك رسالة جديدة";return "🔔 إشعار جديد";}
    private fun markNotificationsRead(userId: String, ids: String) {
        if (userId.isNullOrEmpty() || ids.isNullOrEmpty() || notificationsMarkReadInFlight)return
        notificationsMarkReadInFlight=true
        if (!submitIo({
            try{
                val r = authenticatedRequest("PATCH",supabaseUrl()+"/rest/v1/notifications?user_id=eq."+userId+"&id=in.("+ids+")",JSONObject().put("is_read",true).toString())
                if(r.code<200||r.code>=300)throw Exception(AppUtils.errorMessage(r.body))
            }catch (ignored: Exception){}
            finally{notificationsMarkReadInFlight=false
            }
        })) {
            notificationsMarkReadInFlight=false
        }
    }

        private fun showMessages() {
        base("الرسائل"); val back = button("العودة"); back.setOnClickListener { showFeed() }; val recipient = input("معرّف المستخدم UUID للمستلم",false); val open = button("فتح المحادثة")
        val s = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        s.addView(list)
        root.addView(s,LinearLayout.LayoutParams(-1,0,1))
        open.setOnClickListener { openConversation(recipient.text.toString().trim(), list) }
        loadConversations(list)
    }

    private fun loadConversations(list: LinearLayout) {
        submitIo {
            try {
                val r = authenticatedRequest("GET", supabaseUrl()+"/rest/v1/conversations?select=id,created_at,updated_at&order=updated_at.desc&limit=30", null)
                if (r.code < 200 || r.code >= 300) throw Exception(AppUtils.errorMessage(r.body))
                val a = JSONArray(r.body)
                postUi {
                    for (i in 0 until a.length) {
                        val c = a.optJSONObject(i) ?: continue
                        val b = buttonIn(list, "محادثة " + c.optString("id"))
                        b.setOnClickListener { loadMessages(c.optString("id"), list) }
                    }
                }
            } catch (e: Exception) {
                postUi { status.text = "تعذر تحميل المحادثات: "+AppUtils.safeMessage(e) }
            }
        }
    }

    private fun openConversation(other: String, list: LinearLayout) {
        if (!AppUtils.isUuid(other)) { toast("أدخل UUID صحيحًا للمستخدم."); return }
        submitIo {
            try {
                val body = JSONObject().put("p_other_user_id", other).toString()
                val r = authenticatedRequest("POST", supabaseUrl()+"/rest/v1/rpc/create_direct_conversation", body)
                if (r.code < 200 || r.code >= 300) throw Exception(AppUtils.errorMessage(r.body))
                val id = r.body.replace("\"", "").trim()
                postUi { loadMessages(id, list) }
            } catch (e: Exception) {
                postUi { toast("تعذر فتح المحادثة: "+AppUtils.safeMessage(e)) }
            }
        }
    }

    private fun loadMessages(conversationId: String, list: LinearLayout) {
        if (!AppUtils.isUuid(conversationId)) { toast("معرّف المحادثة غير صالح."); return }
        list.removeAllViews()
        val message = input("اكتب رسالة", false)
        val send = button("إرسال")
        list.addView(message)
        list.addView(send)
        submitIo {
            try {
                val me = currentUserId()
                val r = authenticatedRequest("GET", supabaseUrl()+"/rest/v1/messages?conversation_id=eq."+conversationId+"&select=id,sender_id,content,media_url,is_read,created_at&order=created_at.asc&limit=100", null)
                if (r.code < 200 || r.code >= 300) throw Exception(AppUtils.errorMessage(r.body))
                val a = JSONArray(r.body)
                postUi {
                    for (i in 0 until a.length) {
                        val m = a.optJSONObject(i) ?: continue
                        val t = TextView(this)
                        t.text = if (m.optString("sender_id") == me) "أنا: "+m.optString("content") else "هو: "+m.optString("content")
                        t.setPadding(8,12,8,12)
                        list.addView(t)
                    }
                    send.setOnClickListener { sendMessage(conversationId, message) }
                }
            } catch (e: Exception) { postUi { status.text = "تعذر تحميل الرسائل: "+AppUtils.safeMessage(e) } }
        }
    }

    private fun sendMessage(conversationId: String, field: EditText) {
        var text =field.text.toString().trim()
        if (text.isEmpty() || text.length > 5000){toast("الرسالة يجب أن تكون بين 1 و5000 حرف.");return;}
        if(messageSendInFlight) return
        messageSendInFlight=true
        field.isEnabled = false
        if (!submitIo({
            try{
                var me =currentUserId()
                val r = authenticatedRequest("POST",supabaseUrl()+"/rest/v1/messages",JSONObject().put("conversation_id",conversationId).put("sender_id",me).put("content",text).toString())
                if(r.code<200||r.code>=300)throw Exception(AppUtils.errorMessage(r.body))
                postUi { field.text.clear(); field.isEnabled = true; toast(if (isOfflineQueued(r)) "تم حفظ الرسالة Offline وستُرسل عند الاتصال." else "تم إرسال الرسالة.") }
            }catch (e: Exception){postUi { field.isEnabled = true; toast("تعذر إرسال الرسالة: " + AppUtils.safeMessage(e)) };}
            finally { messageSendInFlight=false
            }
        })) {
            messageSendInFlight=false
            field.isEnabled = true
        }
    }

    private fun showStories() {
        base("القصص")
        val back = button("العودة")
        back.setOnClickListener { showFeed() }
        val add = button("إضافة قصة من صورة/فيديو")
        val scroll = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1))
        add.setOnClickListener {
            pickingStoryMedia = true
            mediaPickerLauncher.launch(arrayOf("image/*", "video/*"))
        }
        submitIo {
            try {
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
                val expiresFilter = URLEncoder.encode(now, "UTF-8").replace("+", "%20")
                val r = authenticatedRequest("GET", supabaseUrl()+"/rest/v1/stories?select=id,user_id,media_url,media_type,caption,views_count,expires_at,created_at&expires_at=gt."+expiresFilter+"&order=created_at.desc&limit=50", null)
                if (r.code < 200 || r.code >= 300) throw Exception(AppUtils.errorMessage(r.body))
                val a = JSONArray(r.body)
                postUi {
                    for (i in 0 until a.length) {
                        val st = a.optJSONObject(i) ?: continue
                        val t = TextView(this)
                        t.text = "📖 "+st.optString("media_type")+"\n"+st.optString("caption","")+"\nمشاهدات: "+st.optInt("views_count")
                        t.setPadding(8,16,8,16)
                        list.addView(t)
                    }
                }
            } catch (e: Exception) { postUi { status.text = "تعذر تحميل القصص: "+AppUtils.safeMessage(e) } }
        }
    }

    private fun publishStoryFromSelectedMedia() {
        if (storyInFlight || selectedMedia == null) return
        storyInFlight = true
        val media = selectedMedia
        val mime = selectedMime ?: "image/jpeg"
        if (!online) {
            if (!submitIo {
                try {
                    val userId = currentUserId()
                    val queued = JSONObject().put("user_id", userId).put("caption", "")
                    if (queueOfflineMedia(OFFLINE_STORY_URL, media, mime, queued)) {
                        clearSelectedMedia()
                        storyInFlight = false
                        postUi { toast("القصة محفوظة Offline وستُنشر تلقائيًا عند الاتصال.") }
                        return@submitIo
                    }
                } catch (_: Exception) {}
                storyInFlight = false
            }) storyInFlight = false
            return
        }
        if (!submitIo {
            var uploaded: UploadedMedia? = null
            try {
                val storyUserId = currentUserId()
                uploaded = uploadMedia(media, mime, "stories")
                val body = JSONObject().put("user_id", storyUserId).put("media_url", uploaded.url)
                    .put("media_type", if (mime.startsWith("video/")) "video" else "image")
                    .put("caption", JSONObject.NULL)
                val r = authenticatedRequest("POST", supabaseUrl()+"/rest/v1/stories", body.toString())
                if (r.code < 200 || r.code >= 300) throw Exception(AppUtils.errorMessage(r.body))
                clearSelectedMedia()
                postUi { storyInFlight = false; toast("تم نشر القصة بنجاح."); showStories() }
            } catch (e: Exception) {
                uploaded?.let { deleteMedia(it.path) }
                storyInFlight = false
                postUi { toast("تعذر نشر القصة: "+AppUtils.safeMessage(e)) }
            }
        }) storyInFlight = false
    }

    private fun addPostCard(posts: LinearLayout, post: JSONObject) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(12), dp(14), dp(10))
        card.background = rounded(Color.WHITE,18)
        val author = TextView(this); author.text = "●  مستخدم  ·  الآن"; author.textSize = 14f; author.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); author.setTextColor(Color.rgb(35,35,35)); card.addView(author)
        val content = post.optString("content","")
        val text = TextView(this); text.text = if (content.isEmpty()) "منشور بدون نص" else content; text.textSize = 16f; text.setTextColor(Color.rgb(45,45,45)); text.setPadding(0,dp(10),0,dp(8)); card.addView(text)
        val media = post.optJSONArray("media_urls")
        if (media != null && media.length > 0) {
            val mediaInfo = TextView(this); mediaInfo.text = "▣  "+media.length+" وسائط  ·  "+post.optString("media_type","media"); mediaInfo.textSize = 14f; mediaInfo.gravity = Gravity.CENTER; mediaInfo.setTextColor(Color.rgb(70,70,70)); mediaInfo.background = rounded(Color.rgb(245,245,245),14); mediaInfo.setPadding(0,dp(38),0,dp(38))
            mediaInfo.setOnClickListener { val u = media.optString(0,""); val mt = post.optString("media_type",""); if (u.isNotEmpty()) showNativeMediaViewer(u, mt) }
            card.addView(mediaInfo)
        }
        val counts = TextView(this); counts.text = "♥ "+post.optInt("likes_count")+"    💬 "+post.optInt("comments_count"); counts.setTextColor(Color.GRAY); counts.setPadding(0,dp(10),0,dp(4)); card.addView(counts)
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        val like = buttonIn(actions,"♡ إعجاب"); val comment = buttonIn(actions,"💬 تعليق"); val share = buttonIn(actions,"↗ مشاركة")
        like.setOnClickListener { toggleLike(post.optString("id"), like, counts, post) }
        comment.setOnClickListener { showCommentDialog(post.optString("id")) }
        share.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND); shareIntent.type = "text/plain"; shareIntent.putExtra(Intent.EXTRA_TEXT, if (content.isEmpty()) "منشور من mr.x" else content)
            try { startActivity(Intent.createChooser(shareIntent,"مشاركة المنشور")) } catch (_: Exception) { toast("لا يوجد تطبيق متاح للمشاركة.") }
        }
        card.addView(actions)
        val lp = LinearLayout.LayoutParams(-1,-2)
        lp.setMargins(0,0,0,dp(10))
        posts.addView(card,lp)
    }

    private fun showNativeMediaViewer(url: String, mediaType: String) {
        if (!AppUtils.isTrustedMediaUrl(url, storageUrl())) { toast("رابط الوسائط غير موثوق."); return }
        val videoType = mediaType.lowercase(Locale.US).startsWith("video/")
        val dialog = android.app.Dialog(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8),dp(8),dp(8),dp(8))
        val close = TextView(this); close.text = "إغلاق"; close.textSize = 16f; close.gravity = Gravity.CENTER; close.setPadding(0,dp(10),0,dp(10)); close.setOnClickListener { dialog.dismiss() }; box.addView(close,LinearLayout.LayoutParams(-1,-2))
        if (videoType) {
            val video = VideoView(this)
            val headers = mutableMapOf<String, String>("android-allow-cross-domain-redirect" to "0")
            video.setVideoURI(Uri.parse(url), headers)
            val controls = MediaController(this)
            controls.setAnchorView(video)
            video.mediaController = controls
            box.addView(video,LinearLayout.LayoutParams(-1,0,1)); video.setOnPreparedListener { mp: android.media.MediaPlayer -> mp.isLooping = false; video.start() }; video.setOnErrorListener { _: android.media.MediaPlayer, _: Int, _: Int -> toast("تعذر تشغيل الفيديو داخل التطبيق."); true }
            dialog.setOnDismissListener { try { if (video.isPlaying) video.stopPlayback() } catch (_: Exception) {} }
        } else {
            val image = ImageView(this)
            image.adjustViewBounds = true
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            box.addView(image,LinearLayout.LayoutParams(-1,0,1))
            val task = submitIoFuture {
                var c: HttpURLConnection? = null
                try {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    c = connection
                    connection.connectTimeout = 10000
                    connection.readTimeout = 15000
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "image/*")
                    connection.connect()
                    val code = connection.responseCode
                    if (code < 200 || code >= 300) throw Exception("HTTP $code")
                    val ct = connection.contentType
                    if (ct != null && !ct.lowercase(Locale.US).startsWith("image/")) throw Exception("نوع الوسائط ليس صورة.")
                    val data = readLimited(connection.inputStream, MAX_IMAGE_PREVIEW_BYTES)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw Exception("بيانات الصورة غير صالحة.")
                    postUi { if(!dialog.isShowing){bmp.recycle()
                    return@postUi}
                    image.setImageBitmap(bmp) }
                } catch(e:Exception){postUi { if(dialog.isShowing) toast("تعذر تحميل الصورة: "+AppUtils.safeMessage(e)) }} finally { c?.disconnect() }
            }
            dialog.setOnDismissListener { task?.cancel(true)
            image.setImageDrawable(null) }
        }
        dialog.setContentView(box)
        dialog.setOnShowListener { dialog.window?.setLayout(-1,-1) }
        dialog.show()
    }

    private fun buttonIn(parent: LinearLayout, label: String): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.rgb(35, 35, 35))
        background = rounded(Color.WHITE, 16f)
        setPadding(dp(5f), dp(2f), dp(5f), dp(2f))
        parent.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun toggleLike(postId: String, likeButton: Button, counts: TextView, post: JSONObject) {
        if (!AppUtils.isUuid(postId)) return
        likeButton.isEnabled=false
        submitIo { try { val uid=currentUserId(); if(uid.isEmpty()) throw Exception("جلسة المستخدم غير صالحة."); val q=supabaseUrl()+"/rest/v1/likes?select=id&post_id=eq."+postId+"&user_id=eq."+uid+"&limit=1"; val check=authenticatedRequest("GET",q,null); if(check.code<200||check.code>=300) throw Exception(AppUtils.errorMessage(check.body)); val existing=JSONArray(check.body); val result=if(existing.length>0) authenticatedRequest("DELETE",supabaseUrl()+"/rest/v1/likes?post_id=eq."+postId+"&user_id=eq."+uid,null) else authenticatedRequest("POST",supabaseUrl()+"/rest/v1/likes",JSONObject().put("post_id",postId).put("user_id",uid).toString()); if(result.code<200||result.code>=300) throw Exception(AppUtils.errorMessage(result.body)); postUi { likeButton.isEnabled=true; toast("تم تحديث الإعجاب.") } } catch(e:Exception){postUi {likeButton.isEnabled=true;toast("تعذر تحديث الإعجاب: "+AppUtils.safeMessage(e))}} }
    }

    private fun showCommentDialog(postId: String) {
        if (postId.isEmpty()) return
        val input = EditText(this).apply {
            hint = "اكتب تعليقك..."
            minLines = 3
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("تعليق")
            .setView(input)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("نشر") { _, _ ->
                createComment(postId, input.text.toString().trim())
            }
            .show()
    }

    private fun createComment(postId: String, content: String) {
        if (content.isEmpty() || content.length > 2000) {
            toast("التعليق يجب أن يكون بين حرف واحد و2000 حرف.")
            return
        }

        submitIo {
            try {
                val userId = currentUserId()
                if (userId.isEmpty()) throw Exception("جلسة المستخدم غير صالحة.")

                val body = JSONObject()
                    .put("post_id", postId)
                    .put("user_id", userId)
                    .put("content", content)
                    .toString()
                val response = authenticatedRequest(
                    "POST",
                    "${supabaseUrl()}/rest/v1/comments",
                    body
                )
                if (response.code !in 200..299) {
                    throw Exception(AppUtils.errorMessage(response.body))
                }

                postUi {
                    toast(
                        if (isOfflineQueued(response)) {
                            "تم حفظ التعليق Offline وسيُنشر عند الاتصال."
                        } else {
                            "تم نشر التعليق."
                        }
                    )
                    showFeed()
                }
            } catch (e: Exception) {
                postUi { toast("تعذر نشر التعليق: ${AppUtils.safeMessage(e)}") }
            }
        }
    }

    private fun showCreatePost(posts: LinearLayout) { clearSelectedMedia(); base("منشور جديد"); val content=EditText(this);content.hint="اكتب منشورك...";content.minLines=5;root.addView(content,LinearLayout.LayoutParams(-1,-2)); val pick=button("اختيار صورة/فيديو"); val camera=button("التقاط صورة بالكاميرا"); val publish=button("نشر"); val cancel=button("إلغاء"); pick.setOnClickListener { mediaPickerLauncher.launch(arrayOf("image/*", "video/*")) };camera.setOnClickListener{captureImage()};publish.setOnClickListener{if(!publishInFlight){publish.isEnabled=false;createPost(content.text.toString().trim(),publish)}};cancel.setOnClickListener{clearSelectedMedia();showFeed()} }

    private fun captureImage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            launchCameraCapture()
            return
        }

        val permissions = buildList {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isEmpty()) {
            launchCameraCapture()
        } else {
            cameraPermissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun launchCameraCapture() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "social_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        pendingCameraUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        val uri = pendingCameraUri
        if (uri == null) {
            toast("تعذر إنشاء ملف الكاميرا.")
            return
        }
        cameraCaptureLauncher.launch(uri)
    }

    private fun handlePickedMedia(uri: Uri?) {
        if (uri == null) {
            if (pickingStoryMedia) {
                pickingStoryMedia = false
                clearSelectedMedia()
            }
            return
        }

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable permissions; the transient
            // grant is still usable for the current operation.
        }

        val mime = contentResolver.getType(uri)
        if (mime.isNullOrEmpty() || !(mime.startsWith("image/") || mime.startsWith("video/"))) {
            clearSelectedMedia()
            if (pickingStoryMedia) pickingStoryMedia = false
            toast("نوع الملف غير مدعوم.")
            return
        }

        selectedMedia = uri
        selectedMime = mime
        toast("تم اختيار الوسائط. اضغط نشر لرفعها فعليًا.")
        if (pickingStoryMedia) {
            pickingStoryMedia = false
            publishStoryFromSelectedMedia()
        }
    }

    private fun handleCameraCaptureResult(success: Boolean) {
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) {
            selectedMedia = uri
            selectedMime = "image/jpeg"
            toast("تم التقاط الصورة. اضغط نشر لرفعها فعليًا.")
            return
        }
        if (uri != null) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
                // Best-effort cleanup of an abandoned MediaStore item.
            }
        }
    }

    private fun createPost(content:String,publishButton:Button){ if(content.isEmpty()&&selectedMedia==null){toast("اكتب محتوى أو اختر وسائط أولًا.");publishButton.isEnabled=true;return};publishInFlight=true;val media=selectedMedia;val mime = selectedMime ?: "image/jpeg";if(!online&&media!=null){if(!submitIo{try{val userId=currentUserId();val queued=JSONObject().put("user_id",userId).put("content",content);if(queueOfflineMedia(OFFLINE_POST_URL,media,mime,queued)){clearSelectedMedia();publishInFlight=false;postUi{publishButton.isEnabled=true;toast("المنشور والوسائط محفوظان Offline وسيتم نشرهما تلقائيًا عند الاتصال.")};return@submitIo}}catch(_:Exception){};publishInFlight=false;postUi{publishButton.isEnabled=true}}){publishInFlight=false;publishButton.isEnabled=true};return};status.text = "جارٍ تجهيز المنشور...";if(!submitIo{var uploaded:UploadedMedia?=null;try{var mediaType="none";if(media!=null){uploaded=uploadMedia(media,mime);mediaType=if(mime.startsWith("video/"))"video" else "image"};val mediaUrl=uploaded?.url;val userId=currentUserId();if(userId.isEmpty()){clearSecrets();throw Exception("جلسة المستخدم غير صالحة؛ سجّل الدخول مرة أخرى.")};val body=JSONObject().put("user_id",userId).put("content",if(content.isEmpty())JSONObject.NULL else content).put("media_urls",if(mediaUrl==null)JSONArray() else JSONArray().put(mediaUrl)).put("media_type",mediaType);val r=authenticatedRequest("POST",supabaseUrl()+"/rest/v1/posts",body.toString());if(r.code==401){clearSecrets();throw Exception("انتهت الجلسة؛ سجّل الدخول مرة أخرى.")};if(r.code<200||r.code>=300)throw Exception(AppUtils.errorMessage(r.body));clearSelectedMedia();publishInFlight=false;postUi{showFeed()}}catch(e:Exception){uploaded?.let{deleteMedia(it.path)};publishInFlight=false;postUi{publishButton.isEnabled=true;status.text = "فشل النشر: "+AppUtils.safeMessage(e)}}}){publishInFlight=false;publishButton.isEnabled=true} }

    private fun uploadMedia(uri: Uri, mime: String): UploadedMedia = uploadMedia(uri, mime, "posts")

    private fun uploadMedia(uri:Uri,mime:String,folder:String):UploadedMedia { val scheme = uri.scheme;if(scheme==null||!(scheme.equals("content", ignoreCase = true)||scheme.equals("file", ignoreCase = true)))throw Exception("مصدر الملف غير آمن.");if(!(mime.startsWith("image/")||mime.startsWith("video/")))throw Exception("نوع الملف غير مدعوم.");return uploadMediaOnce(uri,mime,folder,true) }

    private fun uploadMediaOnce(uri: Uri, mime: String, folder: String, allowRefresh: Boolean): UploadedMedia {
        val userId = currentUserId()
        if (userId.isEmpty()) throw Exception("جلسة غير صالحة.")
        val size = querySize(uri)
        if (size > MAX_MEDIA_BYTES) throw Exception("حجم الملف أكبر من 50MB.")
        val ext = AppUtils.mediaExtension(mime) ?: throw Exception("نوع الوسائط غير مدعوم.")
        val safeFolder = if (folder == "stories") "stories" else "posts"
        val path = "$userId/$safeFolder/${UUID.randomUUID()}.$ext"
        val uploadUrl = services.clients.storage.storage("object/media/$path")
        if (!services.trusts(uploadUrl)) throw Exception("وجهة التخزين غير موثوقة.")
        val requestRefreshToken = readSecret(REFRESH_TOKEN)
        var connection: HttpURLConnection? = null
        var uploadStarted = false
        try {
            val http = URL(uploadUrl).openConnection() as HttpURLConnection
            connection = http
            http.instanceFollowRedirects = false
            http.requestMethod = "POST"
            http.connectTimeout = 20000
            http.readTimeout = 60000
            http.doOutput = true
            http.setRequestProperty("apikey", supabaseKey())
            http.setRequestProperty("Authorization", "Bearer " + readSecret(ACCESS_TOKEN))
            http.setRequestProperty("Content-Type", mime)
            http.setRequestProperty("x-upsert", "false")
            if (size >= 0) http.setFixedLengthStreamingMode(size)

            val input = contentResolver.openInputStream(uri) ?: throw Exception("تعذر قراءة الملف.")
            input.use { ins ->
                http.outputStream.use { out ->
                    uploadStarted = true
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n == -1) break
                        if (total > MAX_MEDIA_BYTES - n) throw Exception("حجم الملف أكبر من 50MB.")
                        out.write(buf, 0, n)
                        total += n
                    }
                }
            }

            val code = http.responseCode
            val body = readResponse(http, code)
            if (code == 401 && allowRefresh) {
                if (!refreshSessionIfNeeded(requestRefreshToken)) throw Exception("انتهت الجلسة؛ سجّل الدخول مرة أخرى.")
                return uploadMediaOnce(uri, mime, folder, false)
            }
            if (code < 200 || code >= 300) throw Exception(AppUtils.errorMessage(body))
            return UploadedMedia(services.clients.storage.storage("object/public/media/$path"), path)
        } catch (e: Exception) {
            if (uploadStarted) cleanupUploadedPath(path, requestRefreshToken)
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    private fun cleanupUploadedPath(path: String, failedRefreshToken: String) {
        if (path.isEmpty()) return

        runCatching {
            val deleteUrl = services.clients.storage.storage("object/media/$path")
            val result = request("DELETE", deleteUrl, null, readSecret(ACCESS_TOKEN))
            if (result.code == 401 && refreshSessionIfNeeded(failedRefreshToken)) {
                request("DELETE", deleteUrl, null, readSecret(ACCESS_TOKEN))
            }
        }
    }
    private fun refreshSession(): Boolean = refreshSessionIfNeeded(null)
    private fun refreshSessionIfNeeded(failedRefreshToken:String?):Boolean { synchronized(refreshLock){try{val currentRefresh=readSecret(REFRESH_TOKEN);if(currentRefresh.isEmpty())return false;if(!failedRefreshToken.isNullOrEmpty()&&failedRefreshToken!=currentRefresh)return true;val rr=request("POST",authUrl()+"/auth/v1/token?grant_type=refresh_token",JSONObject().put("refresh_token",currentRefresh).toString(),null);if(rr.code<200||rr.code>=300)return false;val j=JSONObject(rr.body);val a=j.optString("access_token","");val n=j.optString("refresh_token","");if(a.isEmpty()||n.isEmpty())return false;try{saveSecret(ACCESS_TOKEN,a);saveSecret(REFRESH_TOKEN,n)}catch(_:Exception){clearSecrets();return false};return true}catch(_:Exception){return false}}}
    private fun deleteMedia(path: String) {
        if (path.isEmpty()) return
        submitIo { cleanupUploadedPath(path, readSecret(REFRESH_TOKEN)) }
    }
    private fun querySize(uri: Uri): Long = runCatching {
        contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun authenticatedRequest(method: String, url: String, body: String?): HttpResult {
        var token = readSecret(ACCESS_TOKEN)
        val refresh = readSecret(REFRESH_TOKEN)
        val read = method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)
        val networkAvailableAtStart = isNetworkAvailable()
        online = networkAvailableAtStart
        if (!networkAvailableAtStart && read) {
            val cached = offlineStore.getCached(url, readSecret(CURRENT_USER_ID))
            if (cached != null) return HttpResult(200, cached)
            throw OfflineException("لا توجد شبكة ولا توجد نسخة محفوظة من هذه البيانات.")
        }
        if (!networkAvailableAtStart && !read && shouldQueueOffline(method, url)) {
            val ownerUserId = readSecret(CURRENT_USER_ID)
            if (ownerUserId.isEmpty()) throw Exception("تعذر حفظ العملية Offline قبل التحقق من حساب المستخدم.")
            val id = offlineStore.enqueue(method, url, body, ownerUserId, currentOperationId())
            if (id < 0) throw Exception("تعذر حفظ العملية Offline.")
            postUi { status.text = "تم الحفظ محليًا وسيتم الإرسال تلقائيًا عند عودة الإنترنت (مهمة #$id)." }
            return HttpResult(202, "{\"offline_queued\":true}", null)
        }
        try {
            var r = requestResilient(method, url, body, token)
            if (r.code == 401 && token.isNotEmpty() && refresh.isNotEmpty() && refreshSessionIfNeeded(refresh)) {
                token = readSecret(ACCESS_TOKEN)
                r = requestResilient(method, url, body, token)
            }
            if (read && r.code in 200..299) offlineStore.putCached(url, r.body, readSecret(CURRENT_USER_ID))
            if (!read && r.code in 200..299) scheduleSync(0)
            return r
        } catch (e: Exception) {
            // Do not enqueue here: the request may have reached the server before the transport failed.
            // Offline mutations are queued only before a request is sent, preventing duplicate writes.
            if (read) offlineStore.getCached(url, readSecret(CURRENT_USER_ID))?.let { return HttpResult(200, it) }
            if (!isNetworkAvailable()) online = false
            throw e
        }
    }

    private fun isOfflineQueued(r: HttpResult): Boolean = r.code == 202 && r.body.contains("offline_queued")

    private fun shouldQueueOffline(method: String, url: String): Boolean {
        if (online) return false
        val lower = url.lowercase(Locale.US)
        if (lower.contains("/auth/v1/") || lower.contains("/storage/v1/") || lower.contains("/rpc/")) return false
        return method.equals("POST", ignoreCase = true) || method.equals("PATCH", ignoreCase = true) || method.equals("DELETE", ignoreCase = true)
    }

    private fun requestResilient(method: String, url: String, body: String?, token: String?): HttpResult {
        val retryable = method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true) || method.equals("DELETE", ignoreCase = true)
        var last: HttpResult? = null
        var lastError: Exception? = null
        for (attempt in 0..TRANSIENT_RETRY_COUNT) {
            try {
                val result = request(method, url, body, token)
                last = result
                lastError = null
                if (!retryable || !isTransientHttpCode(result.code) || attempt == TRANSIENT_RETRY_COUNT) break
                TimeUnit.MILLISECONDS.sleep(retryDelayMs(result, attempt))
            } catch (e: Exception) {
                lastError = e
                if (!retryable || !isReadReplicaRequest(method, url) || attempt == TRANSIENT_RETRY_COUNT) break
                TimeUnit.MILLISECONDS.sleep(minOf(
                    TRANSIENT_RETRY_MAX_MS,
                    TRANSIENT_RETRY_BASE_MS * (1L shl minOf(attempt, 3))
                ))
            }
        }

        if (retryable && isReadReplicaRequest(method, url)) {
            val lastResult = last
            if (lastResult != null && isTransientHttpCode(lastResult.code)) {
                return requestDirect(method, readFallbackUrl(url), body, token)
            }
            if (last == null && lastError != null) {
                val replicaError = lastError
                return try {
                    requestDirect(method, readFallbackUrl(url), body, token)
                } catch (fallbackError: Exception) {
                    fallbackError.addSuppressed(replicaError)
                    throw fallbackError
                }
            }
        }

        lastError?.let { throw it }
        return last ?: throw Exception("لم يتم الحصول على استجابة من الخادم.")
    }

    private fun isReadReplicaRequest(method: String, url: String): Boolean =
        services.isReadReplica(method, url)
    private fun readFallbackUrl(url: String): String = services.fallbackWrite(url)
    private fun isTransientHttpCode(code: Int): Boolean = code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504
    private fun retryDelayMs(response: HttpResult, attempt: Int): Long {
        val fallback = TRANSIENT_RETRY_BASE_MS * (1L shl minOf(attempt,3))
        val retryAfterMs = parseRetryAfterMs(response.retryAfter ?: "")
        return minOf(TRANSIENT_RETRY_MAX_MS, maxOf(250L, if (retryAfterMs > 0) retryAfterMs else fallback))
    }
    private fun parseRetryAfterMs(value: String): Long {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return -1L

        return runCatching { trimmed.toLong() }
            .getOrNull()
            ?.takeIf { it >= 0L }
            ?.let { seconds ->
                if (seconds > Long.MAX_VALUE / 1_000L) TRANSIENT_RETRY_MAX_MS
                else minOf(TRANSIENT_RETRY_MAX_MS, seconds * 1_000L)
            }
            ?: -1L
    }
    private fun isTrustedEndpoint(value: String): Boolean = services.trusts(value)

    private fun request(method: String, url: String, body: String?, token: String?): HttpResult {
        if(!isTrustedEndpoint(url)) throw Exception("وجهة اتصال غير موثوقة.")
        val routedUrl=routeServiceEndpoint(method,url)
        if(!isTrustedEndpoint(routedUrl)) throw Exception("وجهة اتصال غير موثوقة.")
        return requestDirect(method,routedUrl,body,token)
    }

    private fun requestDirect(method: String, routedUrl: String, body: String?, token: String?): HttpResult {
        if (!isTrustedEndpoint(routedUrl)) throw Exception("وجهة اتصال غير موثوقة.")

        val connection = URL(routedUrl).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = NETWORK_CONNECT_TIMEOUT_MS
            connection.readTimeout = NETWORK_READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("apikey", supabaseKey())
            if (!token.isNullOrEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.setRequestProperty("X-MRX-Operation-ID", currentOperationId())
            connection.setRequestProperty("X-Request-ID", UUID.randomUUID().toString())
            connection.setRequestProperty("Accept", "application/json")

            if (body != null) {
                val payload = body.toByteArray(StandardCharsets.UTF_8)
                if (payload.size > MAX_REQUEST_BODY_BYTES) {
                    throw Exception("حجم الطلب أكبر من الحد المسموح.")
                }
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { output -> output.write(payload) }
            }

            val code = connection.responseCode
            return HttpResult(
                code = code,
                body = readResponse(connection, code),
                retryAfter = connection.getHeaderField("Retry-After")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "الحد الأقصى غير صالح." }
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        input.use { stream ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                if (count > maxBytes - total) {
                    throw Exception("الملف أكبر من الحد المسموح.")
                }
                output.write(buffer, 0, count)
                total += count
            }
        }
        return output.toByteArray()
    }

    private fun readResponse(connection: HttpURLConnection, code: Int): String {
        val declaredLength = connection.contentLengthLong
        if (declaredLength > MAX_RESPONSE_BYTES.toLong()) {
            throw Exception("استجابة الخادم أكبر من الحد المسموح.")
        }
        val stream = if (code >= 400) {
            connection.errorStream ?: return ""
        } else {
            connection.inputStream
        }
        return readLimited(stream, MAX_RESPONSE_BYTES).toString(StandardCharsets.UTF_8)
    }

    private fun postUi(task: () -> Unit) {
        if (isFinishing || isDestroyed) return
        main.post {
            if (!isFinishing && !isDestroyed) task()
        }
    }

    private fun toast(message: String) {
        if (isFinishing || isDestroyed) return
        main.post {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun submitIo(task: () -> Unit): Boolean {
        if (isFinishing || isDestroyed) return false
        return try {
            appExecutors.io().execute(task)
            true
        } catch (_: RejectedExecutionException) {
            postUi { status.text = "التطبيق مشغول حاليًا. أعد المحاولة بعد لحظات." }
            false
        }
    }

    private fun submitIoFuture(task: () -> Unit): Future<*>? {
        if (isFinishing || isDestroyed) return null
        return try {
            appExecutors.io().submit(task)
        } catch (_: RejectedExecutionException) {
            postUi { status.text = "التطبيق مشغول حاليًا. أعد المحاولة بعد لحظات." }
            null
        }
    }

    private fun ensureKeystoreKey() { secretStore.ensureKey() }
    private fun saveSecret(name: String, value: String) {
        secretStore.save(name, value)
    }
    private fun readSecret(name: String): String = secretStore.read(name)
    private fun clearSecrets() {
        secretStore.clear(ACCESS_TOKEN, REFRESH_TOKEN, CURRENT_USER_ID)
        verifiedUserId = ""
        verifiedUserIdAt = 0L
    }
    private fun clearSelectedMedia() {
        pendingCameraUri?.let { uri ->
            runCatching { contentResolver.delete(uri, null, null) }
        }
        selectedMedia = null
        selectedMime = null
        pendingCameraUri = null
    }

}
