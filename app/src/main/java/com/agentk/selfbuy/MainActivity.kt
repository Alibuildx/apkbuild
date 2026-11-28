package com.agentk.selfbuy

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

class MainActivity : AppCompatActivity() {

    private val STORE_URL = "https://flat-wildflower-216b.rdxali20111.workers.dev/"
    private val SUPPORT_EMAIL = "Aliraza2020.net@gmail.com"

    // Live Supabase Configuration (Pre-configured)
    private val SUPABASE_URL = "https://cfprnkgkpteehakhhqfw.supabase.co"
    private val SUPABASE_ANON_KEY = "sb_publishable_BEyiSfrsF8h2YQ34r5DsBw_eZLErtT0"

    private lateinit var webView: WebView
    private lateinit var walletWebView: WebView
    private lateinit var cryptoWebView: WebView
    private lateinit var layout2fa: View
    private lateinit var layoutSupport: View

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var attachedScreenshotUri: Uri? = null

    private val handler = Handler(Looper.getMainLooper())
    private var currentOtp = ""

    private val webFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            filePathCallback?.onReceiveValue(arrayOf(uri))
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private val supportImgPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            attachedScreenshotUri = uri
            findViewById<TextView>(R.id.tvAttachedFile).text = "Screenshot attached ✓"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.storeWebView)
        walletWebView = findViewById(R.id.walletWebView)
        cryptoWebView = findViewById(R.id.cryptoWebView)
        layout2fa = findViewById(R.id.layout2fa)
        layoutSupport = findViewById(R.id.layoutSupport)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        // 1. STORE WEBVIEW
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                webFilePicker.launch("image/*")
                return true
            }
        }
        webView.loadUrl(STORE_URL)

        // 2. ONLINE AUTH-GATED WALLET WEBVIEW
        walletWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        cookieManager.setAcceptThirdPartyCookies(walletWebView, true)
        walletWebView.webViewClient = WebViewClient()
        walletWebView.webChromeClient = WebChromeClient()
        walletWebView.loadDataWithBaseURL("https://selfbuy.app", getWalletHtml(), "text/html", "UTF-8", null)

        // 3. CRYPTO LIVE MARKET WEBVIEW
        cryptoWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        cryptoWebView.webViewClient = WebViewClient()
        cryptoWebView.loadDataWithBaseURL("https://api.binance.com", getCryptoHtml(), "text/html", "UTF-8", null)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        // 4. BOTTOM NAVIGATION (5 TABS)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            webView.visibility = View.GONE
            walletWebView.visibility = View.GONE
            cryptoWebView.visibility = View.GONE
            layout2fa.visibility = View.GONE
            layoutSupport.visibility = View.GONE

            when (item.itemId) {
                R.id.nav_store -> webView.visibility = View.VISIBLE
                R.id.nav_wallet -> walletWebView.visibility = View.VISIBLE
                R.id.nav_crypto -> cryptoWebView.visibility = View.VISIBLE
                R.id.nav_2fa -> layout2fa.visibility = View.VISIBLE
                R.id.nav_support -> layoutSupport.visibility = View.VISIBLE
            }
            true
        }

        // 5. 2FA TOOL
        val etSecret = findViewById<EditText>(R.id.etSecretKey)
        val tvOtp = findViewById<TextView>(R.id.tvOtpCode)
        val tvTimer = findViewById<TextView>(R.id.tvTimer)

        findViewById<Button>(R.id.btnCopyOtp).setOnClickListener {
            if (currentOtp.length == 6) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("2FA OTP", currentOtp))
                Toast.makeText(this, "OTP copied: $currentOtp", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please paste a valid 2FA secret key first", Toast.LENGTH_SHORT).show()
            }
        }

        val otpRunnable = object : Runnable {
            override fun run() {
                val secret = etSecret.text.toString().trim()
                if (secret.isNotEmpty()) {
                    val code = generateTOTP(secret)
                    if (code != null) {
                        currentOtp = code
                        tvOtp.text = "${code.substring(0, 3)} ${code.substring(3)}"
                        val secLeft = 30 - ((System.currentTimeMillis() / 1000L) % 30)
                        tvTimer.text = "Expires in: ${secLeft}s"
                    } else {
                        currentOtp = ""
                        tvOtp.text = "INVALID"
                        tvTimer.text = "Invalid Base32 Secret Key"
                    }
                } else {
                    currentOtp = ""
                    tvOtp.text = "------"
                    tvTimer.text = "Expires in: --s"
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(otpRunnable)

        // 6. SUPPORT TAB
        findViewById<Button>(R.id.btnAttachSS).setOnClickListener {
            supportImgPicker.launch("image/*")
        }

        findViewById<Button>(R.id.btnSendSupport).setOnClickListener {
            val subject = findViewById<EditText>(R.id.etSupSubject).text.toString().trim()
            val message = findViewById<EditText>(R.id.etSupMessage).text.toString().trim()
            if (subject.isEmpty() || message.isEmpty()) {
                Toast.makeText(this, "Please enter both a subject and a message", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (attachedScreenshotUri != null) "image/*" else "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, "[SelfBuy Support] $subject")
                putExtra(Intent.EXTRA_TEXT, message)
                if (attachedScreenshotUri != null) {
                    putExtra(Intent.EXTRA_STREAM, attachedScreenshotUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            try {
                startActivity(Intent.createChooser(intent, "Send Support Email"))
            } catch (e: Exception) {
                Toast.makeText(this, "No email application found on this device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getWalletHtml(): String {
        val sbUrl = SUPABASE_URL
        val sbKey = SUPABASE_ANON_KEY
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <script src="https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2"></script>
          <style>
            * { box-sizing: border-box; margin: 0; padding: 0; font-family: system-ui, -apple-system, sans-serif; }
            body { background: #07090F; color: #F8FAFC; padding: 14px; padding-bottom: 36px; }

            .top-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
            .brand { font-size: 1.05rem; font-weight: 800; letter-spacing: 1.2px; color: #F3D078; }
            .brand span { color: #94A3B8; font-weight: 600; font-size: 0.7rem; display: block; letter-spacing: 0.5px; }

            .top-actions { display: flex; gap: 8px; align-items: center; }
            .curr-select {
              background: #121826; color: #F3D078; border: 1px solid rgba(243, 208, 120, 0.45);
              padding: 7px 9px; border-radius: 10px; font-size: 0.78rem; font-weight: 800; outline: none;
            }
            .logout-btn {
              background: rgba(255, 77, 109, 0.14); color: #FF4D6D; border: 1px solid rgba(255, 77, 109, 0.4);
              padding: 7px 10px; border-radius: 10px; font-size: 0.74rem; font-weight: 800; cursor: pointer;
            }

            /* AUTH GATE SCREEN */
            .auth-wrap { margin-top: 26px; }
            .auth-card {
              background: linear-gradient(145deg, #131A2A 0%, #0C101A 100%);
              border: 1px solid rgba(212, 175, 55, 0.45); border-radius: 20px; padding: 22px 18px;
              box-shadow: 0 14px 36px rgba(0, 0, 0, 0.6);
            }
            .auth-logo { text-align: center; font-size: 1.35rem; font-weight: 900; color: #F3D078; letter-spacing: 1.5px; margin-bottom: 4px; }
            .auth-sub { text-align: center; font-size: 0.78rem; color: #94A3B8; margin-bottom: 18px; }
            .auth-tabs { display: flex; gap: 8px; margin-bottom: 14px; }
            .auth-tab {
              flex: 1; padding: 10px; text-align: center; border-radius: 10px; font-size: 0.84rem; font-weight: 800;
              background: #090C14; border: 1px solid #1E293B; color: #94A3B8; cursor: pointer;
            }
            .auth-tab.active { background: rgba(212,175,55,0.18); border-color: #D4AF37; color: #F3D078; }
            .status-msg {
              background: rgba(255, 77, 109, 0.14); border: 1px solid rgba(255, 77, 109, 0.45); color: #FDA4AF;
              padding: 10px; border-radius: 10px; font-size: 0.75rem; margin-bottom: 12px; display: none;
            }

            /* WALLET MAIN (HIDDEN UNTIL LOGGED IN) */
            #walletApp { display: none; }

            .vault-card {
              background: linear-gradient(135deg, #1A2236 0%, #0E131F 55%, #1F1B12 100%);
              border: 1px solid rgba(212, 175, 55, 0.4); border-radius: 20px; padding: 18px;
              position: relative; overflow: hidden; box-shadow: 0 12px 30px rgba(0, 0, 0, 0.55); margin-bottom: 14px;
            }
            .vault-card::after {
              content: ""; position: absolute; top: -40px; right: -40px; width: 140px; height: 140px;
              background: radial-gradient(circle, rgba(212,175,55,0.22) 0%, transparent 70%); pointer-events: none;
            }
            .card-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
            .chip { width: 34px; height: 24px; border-radius: 6px; background: linear-gradient(135deg, #F3D078, #B8860B); opacity: 0.9; }
            .pkg-pill {
              font-size: 0.68rem; font-weight: 800; letter-spacing: 0.8px; padding: 4px 10px; border-radius: 99px;
              background: rgba(212, 175, 55, 0.16); border: 1px solid rgba(212, 175, 55, 0.55); color: #F3D078;
            }
            .bal-label { font-size: 0.72rem; color: #94A3B8; display: flex; align-items: center; gap: 8px; }
            .eye-btn { background: none; border: none; color: #F3D078; font-size: 0.85rem; cursor: pointer; }
            .bal-main { font-size: 1.95rem; font-weight: 800; color: #FFFFFF; margin: 4px 0 2px; }
            .bal-sub { font-size: 0.76rem; color: #00F5D4; font-weight: 600; margin-bottom: 12px; }
            .card-foot { display: flex; justify-content: space-between; align-items: center; border-top: 1px solid rgba(255,255,255,0.08); padding-top: 10px; font-size: 0.74rem; color: #CBD5E1; }
            .uid-box { cursor: pointer; font-family: monospace; color: #F3D078; font-weight: 700; }

            .tabs { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; margin-bottom: 14px; }
            .tab-btn {
              background: #111623; border: 1px solid #1E293B; color: #94A3B8;
              padding: 9px 4px; border-radius: 12px; font-size: 0.72rem; font-weight: 700; text-align: center; cursor: pointer;
            }
            .tab-btn.active {
              background: linear-gradient(135deg, rgba(212,175,55,0.22), rgba(212,175,55,0.08));
              border-color: #D4AF37; color: #F3D078;
            }

            .panel { display: none; }
            .panel.active { display: block; }
            .box { background: #0F1420; border: 1px solid #1E293B; border-radius: 16px; padding: 16px; margin-bottom: 12px; }
            .box-title { font-size: 0.94rem; font-weight: 800; color: #F8FAFC; margin-bottom: 10px; display: flex; justify-content: space-between; align-items: center; }
            .fee-tag { font-size: 0.66rem; color: #00F5D4; background: rgba(0,245,212,0.12); padding: 3px 8px; border-radius: 99px; }

            label { display: block; font-size: 0.74rem; color: #94A3B8; margin-bottom: 5px; margin-top: 10px; }
            input, select.field {
              width: 100%; background: #090C14; border: 1px solid #26324A; color: #FFFFFF;
              padding: 12px; border-radius: 10px; font-size: 0.88rem; outline: none;
            }
            input:focus, select.field:focus { border-color: #D4AF37; }
            .hint { font-size: 0.72rem; color: #64748B; margin-top: 5px; }

            .dep-info {
              background: #090C14; border: 1px dashed rgba(212,175,55,0.45); border-radius: 12px;
              padding: 12px; margin-top: 8px; font-size: 0.78rem; color: #E2E8F0; line-height: 1.5;
            }
            .dep-info strong { color: #F3D078; }

            .gold-btn {
              width: 100%; margin-top: 14px; background: linear-gradient(135deg, #F3D078 0%, #C59B27 100%);
              color: #090C14; border: none; padding: 13px; border-radius: 12px; font-size: 0.9rem; font-weight: 800; cursor: pointer;
            }
            .gold-btn:disabled { opacity: 0.55; }

            .pkg-card {
              background: linear-gradient(180deg, #131A2A 0%, #0D121D 100%);
              border: 1px solid #24304D; border-radius: 16px; padding: 15px; margin-bottom: 12px;
            }
            .pkg-card.featured { border-color: #D4AF37; box-shadow: 0 0 18px rgba(212,175,55,0.16); }
            .pkg-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
            .pkg-name { font-size: 0.98rem; font-weight: 800; color: #F3D078; }
            .pkg-price { font-size: 1.02rem; font-weight: 800; color: #FFFFFF; }
            .pkg-perks { list-style: none; margin-bottom: 12px; }
            .pkg-perks li { font-size: 0.78rem; color: #CBD5E1; margin-bottom: 4px; }
            .pkg-btn {
              width: 100%; background: #1E293B; color: #F3D078; border: 1px solid rgba(212,175,55,0.5);
              padding: 10px; border-radius: 10px; font-weight: 800; font-size: 0.84rem; cursor: pointer;
            }
            .pkg-card.featured .pkg-btn { background: linear-gradient(135deg, #F3D078, #C59B27); color: #090C14; border: none; }

            .tx-item { display: flex; justify-content: space-between; align-items: center; padding: 11px 0; border-bottom: 1px solid rgba(255,255,255,0.06); }
            .tx-item:last-child { border-bottom: none; }
            .tx-title { font-size: 0.84rem; font-weight: 700; color: #F1F5F9; }
            .tx-meta { font-size: 0.69rem; color: #64748B; margin-top: 2px; }
            .tx-amt { font-size: 0.86rem; font-weight: 800; text-align: right; }
            .tx-amt.in { color: #00F5D4; }
            .tx-amt.out { color: #FF4D6D; }
            .tx-amt.pending { color: #FBBF24; }

            .toast {
              position: fixed; bottom: 18px; left: 50%; transform: translateX(-50%);
              background: #1E293B; color: #F3D078; border: 1px solid #D4AF37;
              padding: 10px 18px; border-radius: 99px; font-size: 0.8rem; font-weight: 700;
              display: none; z-index: 99; max-width: 92%; text-align: center;
            }
          </style>
        </head>
        <body>

          <!-- ==================== AUTH GATE (LOGIN / SIGNUP ONLY) ==================== -->
          <div id="authGate" class="auth-wrap">
            <div class="auth-card">
              <div class="auth-logo">SELFBUY VAULT</div>
              <div class="auth-sub">Sign in or create an account to access your online wallet</div>
              <div id="authError" class="status-msg"></div>

              <div class="auth-tabs">
                <div class="auth-tab active" id="tabLogin" onclick="switchAuthMode('login')">Sign In</div>
                <div class="auth-tab" id="tabSignup" onclick="switchAuthMode('signup')">Create Account</div>
              </div>

              <label>Email Address</label>
              <input type="email" id="authEmail" placeholder="you@example.com">

              <label>Password (minimum 6 characters)</label>
              <input type="password" id="authPass" placeholder="••••••••">

              <div id="pinWrap" style="display:none;">
                <label>Set 4-Digit Wallet Transfer PIN</label>
                <input type="password" id="authPin" maxlength="4" inputmode="numeric" placeholder="e.g. 4829">
              </div>

              <button class="gold-btn" id="authBtn" onclick="submitAuth()">Sign In to Wallet</button>
            </div>
          </div>

          <!-- ==================== ONLINE WALLET (VISIBLE ONLY AFTER LOGIN) ==================== -->
          <div id="walletApp">
            <div class="top-bar">
              <div class="brand">SELFBUY VAULT<span>ONLINE WALLET</span></div>
              <div class="top-actions">
                <select id="currSelect" class="curr-select" onchange="setCurrency(this.value)">
                  <option value="USD">USD</option>
                  <option value="PKR">PKR</option>
                  <option value="INR">INR</option>
                  <option value="BDT">BDT</option>
                  <option value="GBP">GBP</option>
                  <option value="NGN">NGN</option>
                  <option value="AED">AED</option>
                  <option value="VND">VND</option>
                </select>
                <button class="logout-btn" onclick="doLogout()">Sign Out</button>
              </div>
            </div>

            <!-- CLASSIC VAULT BALANCE CARD -->
            <div class="vault-card">
              <div class="card-top">
                <div class="chip"></div>
                <div class="pkg-pill" id="activePkgBadge">FREE TIER</div>
              </div>
              <div class="bal-label">
                AVAILABLE ONLINE BALANCE
                <button class="eye-btn" onclick="toggleHide()" title="Toggle visibility">👁</button>
                <button class="eye-btn" onclick="refreshWallet()" title="Refresh balance">↻</button>
              </div>
              <div class="bal-main" id="balDisplay">Loading...</div>
              <div class="bal-sub" id="balSub">≈ 0.00 USD • Online Verified</div>
              <div class="card-foot">
                <div>UID: <span class="uid-box" id="userUid" onclick="copyUid()">--- ❐</span></div>
                <div id="discountStatus">Store Discount: 0%</div>
              </div>
            </div>

            <!-- 4 WALLET TABS -->
            <div class="tabs">
              <div class="tab-btn active" onclick="openTab('deposit', this)">⬇ Deposit</div>
              <div class="tab-btn" onclick="openTab('transfer', this)">⚡ Transfer</div>
              <div class="tab-btn" onclick="openTab('packages', this)">👑 Packages</div>
              <div class="tab-btn" onclick="openTab('history', this)">📜 History</div>
            </div>

            <!-- TAB 1: DEPOSIT -->
            <div id="panel-deposit" class="panel active">
              <div class="box">
                <div class="box-title">
                  Add Funds (Deposit)
                  <span class="fee-tag">LIVE SERVER</span>
                </div>
                <label>Payment Method</label>
                <select id="depMethod" class="field" onchange="updateDepositInfo()">
                  <option value="USDT (BEP20 / TRC20)">USDT (BEP20 / TRC20)</option>
                  <option value="Binance Pay">Binance Pay</option>
                  <option value="JazzCash / Easypaisa">JazzCash / Easypaisa (PKR)</option>
                  <option value="Bank Transfer">Bank Transfer</option>
                </select>
                <div class="dep-info" id="depDetails">
                  <strong>USDT (BEP20 / TRC20):</strong> Send your payment and enter the transaction hash (TXID) below. Your balance will be credited once verified by admin.
                </div>

                <label>Deposit Amount (in USD)</label>
                <input type="number" id="depAmount" placeholder="e.g. 10.00" oninput="updateDepPreview()">
                <div class="hint" id="depPreview">Equivalent: 0.00</div>

                <label>Transaction ID / Hash / Payment Reference</label>
                <input type="text" id="depRef" placeholder="Paste TXID or payment reference">

                <button class="gold-btn" id="depBtn" onclick="submitDeposit()">Submit Deposit Request</button>
              </div>
            </div>

            <!-- TAB 2: INTERNAL TRANSFER -->
            <div id="panel-transfer" class="panel">
              <div class="box">
                <div class="box-title">
                  Internal User Transfer
                  <span class="fee-tag">INSTANT • 0% FEE</span>
                </div>
                <label>Recipient SelfBuy UID or Email</label>
                <input type="text" id="txTo" placeholder="e.g. SB-492014 or user@example.com">

                <label>Amount (in USD)</label>
                <input type="number" id="txAmount" placeholder="0.00" oninput="updateTxPreview()">
                <div class="hint" id="txPreview">Converted: 0.00</div>

                <label>4-Digit Wallet PIN</label>
                <input type="password" id="txPin" maxlength="4" inputmode="numeric" placeholder="••••">

                <button class="gold-btn" id="txBtn" onclick="doTransfer()">Send Instantly</button>
              </div>
            </div>

            <!-- TAB 3: VIP PACKAGES -->
            <div id="panel-packages" class="panel">
              <div class="pkg-card">
                <div class="pkg-head">
                  <div class="pkg-name">SILVER PASS</div>
                  <div class="pkg-price" data-pkg-usd="9.99">9.99</div>
                </div>
                <ul class="pkg-perks">
                  <li>✓ 10% Instant Discount on All Store Items</li>
                  <li>✓ 0% Fee on Internal User Transfers</li>
                  <li>✓ Verified Silver Vault Badge</li>
                </ul>
                <button class="pkg-btn" onclick="buyPkg('SILVER PASS', 9.99, 10)">Activate Silver Pass</button>
              </div>

              <div class="pkg-card featured">
                <div class="pkg-head">
                  <div class="pkg-name">GOLD ELITE ★</div>
                  <div class="pkg-price" data-pkg-usd="24.99">24.99</div>
                </div>
                <ul class="pkg-perks">
                  <li>✓ 20% Instant Discount on All Store Items</li>
                  <li>✓ 0% Fee + Unlimited Daily Transfers</li>
                  <li>✓ Priority Customer Support</li>
                </ul>
                <button class="pkg-btn" onclick="buyPkg('GOLD ELITE', 24.99, 20)">Activate Gold Elite</button>
              </div>

              <div class="pkg-card">
                <div class="pkg-head">
                  <div class="pkg-name">BLACK DIAMOND</div>
                  <div class="pkg-price" data-pkg-usd="49.99">49.99</div>
                </div>
                <ul class="pkg-perks">
                  <li>✓ 30% Maximum Discount on All Store Items</li>
                  <li>✓ VIP Reseller Badge &amp; Bulk Stock Access</li>
                  <li>✓ Direct Priority Admin Support</li>
                </ul>
                <button class="pkg-btn" onclick="buyPkg('BLACK DIAMOND', 49.99, 30)">Activate Black Diamond</button>
              </div>
            </div>

            <!-- TAB 4: ONLINE HISTORY -->
            <div id="panel-history" class="panel">
              <div class="box">
                <div class="box-title">
                  Transactions &amp; Deposits
                  <span class="fee-tag" id="txCount">0 Records</span>
                </div>
                <div id="txList"><div class="hint">Loading history...</div></div>
              </div>
            </div>
          </div>

          <div class="toast" id="toastBox"></div>

          <script>
            var SB_URL = "$sbUrl";
            var SB_KEY = "$sbKey";
            var sb = null;
            var authMode = 'login';
            var currentUser = null;
            var walletData = { uid_code: '---', balance_usd: 0.00, active_pkg: 'FREE TIER', discount_pct: 0 };
            var historyItems = [];
            var curr = localStorage.getItem('sb_curr') || 'USD';
            var hidden = false;

            var symbols = { USD: '\u0024', PKR: 'Rs ', INR: '₹', BDT: '৳', GBP: '£', NGN: '₦', AED: 'AED ', VND: '₫' };
            var rates = { USD: 1, PKR: 278.5, INR: 84.2, BDT: 120.0, GBP: 0.79, NGN: 1650.0, AED: 3.67, VND: 25400.0 };
            var noDec = ['PKR', 'INR', 'BDT', 'NGN', 'VND'];

            function showToast(msg) {
              var t = document.getElementById('toastBox');
              t.innerText = msg; t.style.display = 'block';
              setTimeout(function() { t.style.display = 'none'; }, 3000);
            }

            function setAuthError(msg) {
              var el = document.getElementById('authError');
              if (!msg) { el.style.display = 'none'; el.innerText = ''; }
              else { el.style.display = 'block'; el.innerText = msg; }
            }

            function fmtMoney(usdVal, cCode) {
              var c = cCode || curr;
              var val = (parseFloat(usdVal) || 0) * (rates[c] || 1);
              var numStr = noDec.indexOf(c) !== -1 ? Math.round(val).toLocaleString('en-US') : val.toFixed(2);
              return (symbols[c] || '\u0024') + numStr;
            }

            function switchAuthMode(mode) {
              authMode = mode;
              setAuthError('');
              document.getElementById('tabLogin').classList.toggle('active', mode === 'login');
              document.getElementById('tabSignup').classList.toggle('active', mode === 'signup');
              document.getElementById('pinWrap').style.display = mode === 'signup' ? 'block' : 'none';
              document.getElementById('authBtn').innerText = mode === 'login' ? 'Sign In to Wallet' : 'Create Account & Open Wallet';
            }

            async function submitAuth() {
              setAuthError('');
              if (!sb) {
                if (window.supabase && window.supabase.createClient) {
                  sb = window.supabase.createClient(SB_URL, SB_KEY);
                } else {
                  setAuthError('Unable to connect to authentication service. Please check your internet connection and retry.');
                  return;
                }
              }
              var email = document.getElementById('authEmail').value.trim();
              var password = document.getElementById('authPass').value.trim();
              var pin = document.getElementById('authPin').value.trim();
              if (!email || password.length < 6) {
                setAuthError('Please enter a valid email address and a password of at least 6 characters.');
                return;
              }

              var btn = document.getElementById('authBtn');
              btn.disabled = true; btn.innerText = 'Please wait...';
              try {
                if (authMode === 'signup') {
                  if (!/^\d{4}$/.test(pin)) {
                    setAuthError('Please set a valid 4-digit numeric transfer PIN.');
                    btn.disabled = false; switchAuthMode('signup'); return;
                  }
                  var res = await sb.auth.signUp({ email: email, password: password, options: { data: { transfer_pin: pin } } });
                  if (res.error) throw res.error;
                  if (res.data.session) {
                    showToast('Account created successfully');
                    await onSignedIn(res.data.user);
                  } else {
                    showToast('Account created! Please verify your email and sign in.');
                  }
                } else {
                  var res = await sb.auth.signInWithPassword({ email: email, password: password });
                  if (res.error) throw res.error;
                  showToast('Signed in successfully');
                  await onSignedIn(res.data.user);
                }
              } catch (e) {
                setAuthError(e.message || 'Authentication failed. Please try again.');
              }
              btn.disabled = false;
              document.getElementById('authBtn').innerText = authMode === 'login' ? 'Sign In to Wallet' : 'Create Account & Open Wallet';
            }

            async function doLogout() {
              if (sb) await sb.auth.signOut();
              currentUser = null;
              document.getElementById('walletApp').style.display = 'none';
              document.getElementById('authGate').style.display = 'block';
              showToast('Signed out');
            }

            async function onSignedIn(user) {
              currentUser = user;
              document.getElementById('authGate').style.display = 'none';
              document.getElementById('walletApp').style.display = 'block';
              await refreshWallet();
            }

            async function refreshWallet() {
              if (!sb || !currentUser) return;
              var wRes = await sb.from('wallets').select('*').eq('user_id', currentUser.id).single();
              if (wRes.data) {
                walletData = wRes.data;
              }
              var txRes = await sb.from('wallet_transactions').select('*').order('created_at', { ascending: false }).limit(25);
              var depRes = await sb.from('deposit_requests').select('*').eq('user_id', currentUser.id).order('created_at', { ascending: false }).limit(15);

              historyItems = [];
              if (depRes.data) {
                depRes.data.forEach(function(d) {
                  if (d.status === 'pending' || d.status === 'rejected') {
                    historyItems.push({
                      cls: d.status === 'pending' ? 'pending' : 'out',
                      sign: d.status === 'pending' ? '⏳ ' : '✕ ',
                      title: 'Deposit (' + d.status.toUpperCase() + ')',
                      meta: d.method + ' • ' + d.tx_ref,
                      usd: d.amount_usd,
                      ts: new Date(d.created_at).getTime()
                    });
                  }
                });
              }
              if (txRes.data) {
                txRes.data.forEach(function(t) {
                  var isIn = t.receiver_id === currentUser.id && t.sender_id !== currentUser.id;
                  historyItems.push({
                    cls: isIn ? 'in' : 'out',
                    sign: isIn ? '+' : '-',
                    title: t.note || t.tx_type.toUpperCase(),
                    meta: t.tx_type.toUpperCase() + ' • ' + new Date(t.created_at).toLocaleDateString(),
                    usd: t.amount_usd,
                    ts: new Date(t.created_at).getTime()
                  });
                });
              }
              historyItems.sort(function(a, b) { return b.ts - a.ts; });
              renderWallet();
            }

            function renderWallet() {
              document.getElementById('currSelect').value = curr;
              document.getElementById('userUid').innerText = (walletData.uid_code || '---') + ' ❐';
              document.getElementById('activePkgBadge').innerText = walletData.active_pkg || 'FREE TIER';
              document.getElementById('discountStatus').innerText = 'Store Discount: ' + (walletData.discount_pct || 0) + '%';

              var bal = parseFloat(walletData.balance_usd) || 0;
              if (hidden) {
                document.getElementById('balDisplay').innerText = '••••••••';
                document.getElementById('balSub').innerText = 'Balance Hidden';
              } else {
                document.getElementById('balDisplay').innerText = fmtMoney(bal);
                document.getElementById('balSub').innerText = '≈ ' + bal.toFixed(2) + ' USD • Online Verified';
              }

              var pkgEls = document.querySelectorAll('[data-pkg-usd]');
              for (var i = 0; i < pkgEls.length; i++) {
                pkgEls[i].innerText = fmtMoney(parseFloat(pkgEls[i].getAttribute('data-pkg-usd')));
              }

              document.getElementById('txCount').innerText = historyItems.length + ' Records';
              var html = '';
              for (var j = 0; j < historyItems.length; j++) {
                var it = historyItems[j];
                html += '<div class="tx-item"><div><div class="tx-title">' + it.title + '</div>' +
                  '<div class="tx-meta">' + it.meta + '</div></div>' +
                  '<div class="tx-amt ' + it.cls + '">' + it.sign + fmtMoney(it.usd) + '</div></div>';
              }
              document.getElementById('txList').innerHTML = html || '<div class="hint">No transactions yet. Submit a deposit to fund your wallet.</div>';
              updateTxPreview();
              updateDepPreview();
            }

            function setCurrency(c) { curr = c; localStorage.setItem('sb_curr', c); renderWallet(); }
            function toggleHide() { hidden = !hidden; renderWallet(); }
            function copyUid() {
              if (navigator.clipboard && walletData.uid_code) navigator.clipboard.writeText(walletData.uid_code);
              showToast('UID copied: ' + walletData.uid_code);
            }
            function openTab(name, el) {
              var panels = document.querySelectorAll('.panel');
              for (var i = 0; i < panels.length; i++) panels[i].classList.remove('active');
              var btns = document.querySelectorAll('.tab-btn');
              for (var j = 0; j < btns.length; j++) btns[j].classList.remove('active');
              document.getElementById('panel-' + name).classList.add('active');
              el.classList.add('active');
            }
            function updateDepPreview() {
              var a = parseFloat(document.getElementById('depAmount').value) || 0;
              document.getElementById('depPreview').innerText = 'Equivalent: ' + fmtMoney(a);
            }
            function updateTxPreview() {
              var a = parseFloat(document.getElementById('txAmount').value) || 0;
              document.getElementById('txPreview').innerText = 'Converted: ' + fmtMoney(a);
            }
            function updateDepositInfo() {
              var m = document.getElementById('depMethod').value;
              var el = document.getElementById('depDetails');
              if (m.indexOf('USDT') !== -1) el.innerHTML = '<strong>USDT (BEP20 / TRC20):</strong> Send your payment and enter the transaction hash (TXID) below. Your balance will be credited once verified by admin.';
              else if (m.indexOf('Binance') !== -1) el.innerHTML = '<strong>Binance Pay:</strong> Send funds to the official Binance Pay ID and paste your Order ID below.';
              else if (m.indexOf('JazzCash') !== -1) el.innerHTML = '<strong>JazzCash / Easypaisa:</strong> Send the PKR equivalent and enter the Transaction ID (TID) and sender number below.';
              else el.innerHTML = '<strong>Bank Transfer:</strong> Complete the bank transfer and paste your payment reference number below.';
            }

            async function submitDeposit() {
              var amt = parseFloat(document.getElementById('depAmount').value);
              var method = document.getElementById('depMethod').value;
              var ref = document.getElementById('depRef').value.trim();
              if (!amt || amt <= 0) { showToast('Please enter a valid deposit amount'); return; }
              if (!ref) { showToast('Please enter your transaction ID or reference'); return; }

              var btn = document.getElementById('depBtn');
              btn.disabled = true; btn.innerText = 'Submitting...';
              var res = await sb.from('deposit_requests').insert({
                user_id: currentUser.id, amount_usd: amt, method: method, tx_ref: ref, status: 'pending'
              });
              btn.disabled = false; btn.innerText = 'Submit Deposit Request';
              if (res.error) { showToast(res.error.message); return; }
              document.getElementById('depAmount').value = '';
              document.getElementById('depRef').value = '';
              showToast('Deposit request submitted for verification');
              await refreshWallet();
            }

            async function doTransfer() {
              var to = document.getElementById('txTo').value.trim();
              var amt = parseFloat(document.getElementById('txAmount').value);
              var pin = document.getElementById('txPin').value.trim();
              if (!to || !amt || amt <= 0 || !pin) { showToast('Please fill in all required fields'); return; }

              var btn = document.getElementById('txBtn');
              btn.disabled = true; btn.innerText = 'Sending...';
              var res = await sb.rpc('internal_transfer', { p_receiver: to, p_amount: amt, p_pin: pin });
              btn.disabled = false; btn.innerText = 'Send Instantly';
              if (res.error) { showToast(res.error.message); return; }
              if (!res.data || !res.data.ok) { showToast((res.data && res.data.error) || 'Transfer failed'); return; }

              document.getElementById('txTo').value = '';
              document.getElementById('txAmount').value = '';
              document.getElementById('txPin').value = '';
              showToast('Transfer completed successfully');
              await refreshWallet();
            }

            async function buyPkg(name, cost, disc) {
              var res = await sb.rpc('buy_package', { p_pkg_name: name, p_cost: cost, p_discount: disc });
              if (res.error) { showToast(res.error.message); return; }
              if (!res.data || !res.data.ok) { showToast((res.data && res.data.error) || 'Activation failed'); return; }
              showToast(name + ' activated successfully');
              await refreshWallet();
            }

            // INIT SUPABASE & LIVE CURRENCY RATES
            (async function init() {
              fetch('https://open.er-api.com/v6/latest/USD')
                .then(function(r) { return r.json(); })
                .then(function(d) {
                  if (d && d.rates) {
                    Object.keys(rates).forEach(function(k) { if (d.rates[k]) rates[k] = d.rates[k]; });
                    if (currentUser) renderWallet();
                  }
                }).catch(function() {});

              try {
                if (window.supabase && window.supabase.createClient) {
                  sb = window.supabase.createClient(SB_URL, SB_KEY);
                  var sess = await sb.auth.getSession();
                  if (sess.data && sess.data.session && sess.data.session.user) {
                    await onSignedIn(sess.data.session.user);
                  }
                }
              } catch (e) {
                setAuthError('Failed to initialize wallet connection. Please restart the app.');
              }
            })();
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    private fun getCryptoHtml(): String {
        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <style>
            * { box-sizing: border-box; margin: 0; padding: 0; font-family: system-ui, -apple-system, sans-serif; }
            body { background: #070A12; color: #F8FAFC; padding: 14px; }
            .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
            .title { font-size: 1.1rem; font-weight: 800; letter-spacing: 0.5px; color: #E2E8F0; }
            .badge { font-size: 0.7rem; font-weight: 700; color: #00F5D4; background: rgba(0,245,212,0.12); border: 1px solid rgba(0,245,212,0.35); padding: 4px 10px; border-radius: 99px; }
            .coins { display: flex; gap: 8px; overflow-x: auto; padding-bottom: 10px; margin-bottom: 10px; }
            .coin-btn { flex: 0 0 auto; background: #111827; border: 1px solid #1E293B; color: #94A3B8; padding: 8px 14px; border-radius: 12px; font-size: 0.85rem; font-weight: 700; cursor: pointer; }
            .coin-btn.active { background: linear-gradient(135deg, #00F5D422, #7C3AED33); border-color: #00F5D4; color: #FFFFFF; box-shadow: 0 0 12px rgba(0,245,212,0.25); }
            .price-card { background: #0F1624; border: 1px solid #1E293B; border-radius: 16px; padding: 14px; margin-bottom: 12px; display: flex; justify-content: space-between; align-items: center; }
            .price-val { font-size: 1.55rem; font-weight: 800; color: #FFFFFF; }
            .price-sub { font-size: 0.75rem; color: #64748B; margin-top: 2px; }
            .pct { font-size: 0.9rem; font-weight: 800; padding: 6px 12px; border-radius: 10px; }
            .pct.up { color: #00F5D4; background: rgba(0,245,212,0.14); }
            .pct.down { color: #FF006E; background: rgba(255,0,110,0.14); }
            .tf-bar { display: flex; gap: 8px; margin-bottom: 10px; }
            .tf-btn { flex: 1; background: #111827; border: 1px solid #1E293B; color: #94A3B8; padding: 6px 0; border-radius: 8px; font-size: 0.78rem; font-weight: 700; text-align: center; }
            .tf-btn.active { background: #1E293B; color: #00F5D4; border-color: #00F5D4; }
            .chart-box { background: radial-gradient(circle at top, #131C31 0%, #090D16 100%); border: 1px solid #1E293B; border-radius: 16px; padding: 10px; height: 340px; position: relative; }
            canvas { width: 100%; height: 100%; display: block; }
            .brand-tag { position: absolute; bottom: 8px; left: 12px; font-size: 0.68rem; color: rgba(148,163,184,0.45); font-weight: 700; letter-spacing: 1px; }
          </style>
        </head>
        <body>
          <div class="header">
            <div class="title">SELFBUY MARKET</div>
            <div class="badge">● LIVE USDT</div>
          </div>
          <div class="coins" id="coinBar">
            <button class="coin-btn active" onclick="selectCoin('BTCUSDT', 'Bitcoin', this)">BTC</button>
            <button class="coin-btn" onclick="selectCoin('ETHUSDT', 'Ethereum', this)">ETH</button>
            <button class="coin-btn" onclick="selectCoin('BNBUSDT', 'BNB', this)">BNB</button>
            <button class="coin-btn" onclick="selectCoin('SOLUSDT', 'Solana', this)">SOL</button>
            <button class="coin-btn" onclick="selectCoin('DOGEUSDT', 'Dogecoin', this)">DOGE</button>
          </div>
          <div class="price-card">
            <div>
              <div class="price-val" id="livePrice">Loading...</div>
              <div class="price-sub" id="coinName">Bitcoin / USDT</div>
            </div>
            <div class="pct up" id="livePct">0.00%</div>
          </div>
          <div class="tf-bar">
            <div class="tf-btn active" onclick="selectTf('1m', this)">1M</div>
            <div class="tf-btn" onclick="selectTf('5m', this)">5M</div>
            <div class="tf-btn" onclick="selectTf('15m', this)">15M</div>
            <div class="tf-btn" onclick="selectTf('1h', this)">1H</div>
          </div>
          <div class="chart-box">
            <canvas id="candleChart"></canvas>
            <div class="brand-tag">SELFBUY PRO ENGINE</div>
          </div>
          <script>
            var currentSymbol = 'BTCUSDT';
            var currentTf = '1m';
            var canvas = document.getElementById('candleChart');
            var ctx = canvas.getContext('2d');
            function selectCoin(sym, name, el) {
              currentSymbol = sym;
              document.getElementById('coinName').innerText = name + ' / USDT';
              var btns = document.querySelectorAll('.coin-btn');
              for (var i = 0; i < btns.length; i++) btns[i].classList.remove('active');
              el.classList.add('active'); loadData();
            }
            function selectTf(tf, el) {
              currentTf = tf;
              var btns = document.querySelectorAll('.tf-btn');
              for (var i = 0; i < btns.length; i++) btns[i].classList.remove('active');
              el.classList.add('active'); loadData();
            }
            function formatPrice(p) {
              var num = parseFloat(p);
              if (num < 1) return num.toFixed(5) + ' USDT';
              if (num < 100) return num.toFixed(3) + ' USDT';
              return num.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' USDT';
            }
            function drawCandles(klines) {
              var dpr = window.devicePixelRatio || 1;
              var rect = canvas.getBoundingClientRect();
              canvas.width = rect.width * dpr; canvas.height = rect.height * dpr;
              ctx.scale(dpr, dpr);
              var w = rect.width, h = rect.height;
              var padTop = 18, padBot = 24, padRight = 56, padLeft = 6;
              var chartW = w - padLeft - padRight, chartH = h - padTop - padBot;
              ctx.clearRect(0, 0, w, h);
              var minP = Infinity, maxP = -Infinity;
              for (var i = 0; i < klines.length; i++) {
                var hi = parseFloat(klines[i][2]), lo = parseFloat(klines[i][3]);
                if (hi > maxP) maxP = hi; if (lo < minP) minP = lo;
              }
              var range = maxP - minP || 1;
              minP -= range * 0.08; maxP += range * 0.08; range = maxP - minP;
              function yPos(price) { return padTop + ((maxP - price) / range) * chartH; }
              ctx.strokeStyle = 'rgba(148, 163, 184, 0.12)'; ctx.lineWidth = 1; ctx.setLineDash([3, 4]);
              ctx.fillStyle = '#64748B'; ctx.font = '10px system-ui';
              for (var g = 0; g <= 4; g++) {
                var gy = padTop + (chartH / 4) * g, gp = maxP - (range / 4) * g;
                ctx.beginPath(); ctx.moveTo(padLeft, gy); ctx.lineTo(w - padRight, gy); ctx.stroke();
                ctx.fillText(gp < 1 ? gp.toFixed(4) : gp.toFixed(1), w - padRight + 6, gy + 3);
              }
              ctx.setLineDash([]);
              var count = klines.length, step = chartW / count, bodyW = Math.max(3, step * 0.58);
              for (var i = 0; i < count; i++) {
                var o = parseFloat(klines[i][1]), hi = parseFloat(klines[i][2]), lo = parseFloat(klines[i][3]), c = parseFloat(klines[i][4]);
                var isUp = c >= o, x = padLeft + i * step + step / 2;
                var yHi = yPos(hi), yLo = yPos(lo), yO = yPos(o), yC = yPos(c);
                var color = isUp ? '#00F5D4' : '#FF006E';
                var glow = isUp ? 'rgba(0, 245, 212, 0.55)' : 'rgba(255, 0, 110, 0.55)';
                ctx.strokeStyle = color; ctx.lineWidth = 1.4;
                ctx.beginPath(); ctx.moveTo(x, yHi); ctx.lineTo(x, yLo); ctx.stroke();
                var topY = Math.min(yO, yC), bodyH = Math.max(2, Math.abs(yC - yO));
                ctx.save(); ctx.shadowColor = glow; ctx.shadowBlur = 8; ctx.fillStyle = color;
                ctx.beginPath();
                if (ctx.roundRect) ctx.roundRect(x - bodyW / 2, topY, bodyW, bodyH, 2);
                else ctx.rect(x - bodyW / 2, topY, bodyW, bodyH);
                ctx.fill(); ctx.restore();
              }
            }
            function loadData() {
              fetch('https://api.binance.com/api/v3/ticker/24hr?symbol=' + currentSymbol)
                .then(function(r) { return r.json(); })
                .then(function(d) {
                  document.getElementById('livePrice').innerText = formatPrice(d.lastPrice);
                  var pct = parseFloat(d.priceChangePercent), pctEl = document.getElementById('livePct');
                  pctEl.innerText = (pct >= 0 ? '+' : '') + pct.toFixed(2) + '%';
                  pctEl.className = 'pct ' + (pct >= 0 ? 'up' : 'down');
                }).catch(function() {});
              fetch('https://api.binance.com/api/v3/klines?symbol=' + currentSymbol + '&interval=' + currentTf + '&limit=36')
                .then(function(r) { return r.json(); })
                .then(function(klines) { if (Array.isArray(klines)) drawCandles(klines); })
                .catch(function() {});
            }
            loadData(); setInterval(loadData, 3000);
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    private fun generateTOTP(base32Secret: String): String? {
        return try {
            val key = decodeBase32(base32Secret) ?: return null
            val timeStep = System.currentTimeMillis() / 1000L / 30L
            val data = ByteBuffer.allocate(8).putLong(timeStep).array()
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            val hash = mac.doFinal(data)
            val offset = (hash[hash.size - 1] and 0x0F).toInt()
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
            val otp = binary % 1_000_000
            String.format("%06d", otp)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeBase32(input: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = input.uppercase().replace(" ", "").replace("-", "").replace("=", "")
        if (clean.isEmpty()) return null
        val out = ArrayList<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (c in clean) {
            val valIdx = alphabet.indexOf(c)
            if (valIdx < 0) return null
            buffer = (buffer shl 5) or valIdx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
