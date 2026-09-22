package kr.co.rateplanner

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kr.co.rateplanner.databinding.ActivityMainBinding
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val webs = HashMap<Tab, WebView>()      // 메뉴별 웹화면 (한 번 만들면 계속 유지)
    private val failed = HashSet<Tab>()             // 연결 실패한 화면
    private var current = Tab.MOBILE
    private var lastBack = 0L
    private var wide = false

    /** 앱 안에서는 신청서 페이지의 상단바(로그인 정보 · 로그아웃)와 무선/유선 탭을 숨긴다.
     *  → 같은 내용이 앱 사이드바에 있음. 서버 파일은 고치지 않는다. */
    private val hideCss = """
        (function(){
          function add(){
            if (document.getElementById('rp-app-css')) return;
            var s = document.createElement('style'); s.id = 'rp-app-css';
            s.textContent = '#topbar,.topbar,.modebar{display:none!important}';
            (document.head || document.documentElement).appendChild(s);
          }
          add();
          if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', add);
        })();
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 상태바 · 내비게이션바 · 키보드 영역만큼 안쪽 여백 (안드로이드 15 전체화면 대응)
        WindowCompat.getInsetsController(window, b.root).isAppearanceLightStatusBars = false
        b.root.setBackgroundColor(getColor(R.color.ink))
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)   // 로그인 유지

        listOf(b.navSide, b.navDrawer).forEach { nav ->
            nav.setNavigationItemSelectedListener { onMenu(it.itemId) }
            nav.getHeaderView(0).setBackgroundColor(getColor(R.color.ink))
        }
        b.edgeTab.setOnClickListener { b.drawer.openDrawer(GravityCompat.START) }

        // 당겨서 새로고침 : 웹화면이 맨 위일 때만
        b.refresh.setColorSchemeResources(R.color.brand)
        b.refresh.setOnChildScrollUpCallback { _, _ -> (webs[current]?.scrollY ?: 0) > 0 }
        b.refresh.setOnRefreshListener { reloadCurrent() }
        b.btnRetry.setOnClickListener { reloadCurrent() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        applyLayout()
        show(Tab.MOBILE)
    }

    /** 폴드 접기/펼치기 · 회전 : 화면을 새로 만들지 않고 사이드바 모양만 바꾼다 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    override fun onResume() { super.onResume(); webs.values.forEach { it.onResume() } }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()     // 로그인 쿠키 저장
        webs.values.forEach { it.onPause() }
    }

    override fun onDestroy() {
        webs.values.forEach { it.destroy() }
        super.onDestroy()
    }

    // ───────── 사이드바 ─────────

    private fun applyLayout() {
        wide = resources.configuration.screenWidthDp >= 600
        b.navSide.visibility = if (wide) View.VISIBLE else View.GONE
        b.edgeTab.visibility = if (wide) View.GONE else View.VISIBLE
        if (wide) b.drawer.closeDrawer(GravityCompat.START, false)
        b.drawer.setDrawerLockMode(
            if (wide) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED)
        syncChecked()
    }

    private fun syncChecked() {
        listOf(b.navSide, b.navDrawer).forEach { it.setCheckedItem(current.menuId) }
    }

    private fun onMenu(id: Int): Boolean {
        b.drawer.closeDrawer(GravityCompat.START)
        Tab.of(id)?.let { show(it); return true }
        when (id) {
            R.id.nav_forms -> clickInPage("btnForms", "통신사 서식지")
            R.id.nav_pay -> clickInPage("btnPay", "수납 요청서")
            R.id.nav_refresh -> reloadCurrent()
            R.id.nav_logout -> confirmLogout()
        }
        return false
    }

    /** 사이드바의 출력 메뉴 → 신청서 페이지 안의 같은 버튼을 누른다 */
    private fun clickInPage(buttonId: String, name: String) {
        val w = webs[current] ?: return
        w.evaluateJavascript(
            "(function(){var e=document.getElementById('$buttonId');if(e){e.click();return 'ok'}return 'none'})()"
        ) { r ->
            if (r?.contains("ok") != true)
                Toast.makeText(this, "이 화면에서는 $name 을(를) 열 수 없어요", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setMessage("로그아웃할까요?")
            .setPositiveButton("로그아웃") { _, _ ->
                // 로그인 쿠키를 지우고 처음 화면(로그인)으로
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    runOnUiThread {
                        webs.values.forEach { b.webHost.removeView(it); it.destroy() }
                        webs.clear(); failed.clear()
                        setHeader(null)
                        show(Tab.MOBILE)
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 사이드바 위쪽 : 로그인한 판매점 정보 (신청서 페이지의 window.LOGIN_SHOP) */
    private fun setHeader(info: JSONObject?) {
        listOf(b.navSide, b.navDrawer).forEach { nav ->
            val h = nav.getHeaderView(0)
            val shop = info?.optString("pShopName").orEmpty()
            val seller = info?.optString("pSellerName").orEmpty()
            val tel = info?.optString("pSellerTel").orEmpty()
            val user = info?.optString("userid").orEmpty()
            h.findViewById<TextView>(R.id.hShop).text = if (info == null) "로그인이 필요해요" else shop.ifEmpty { "판매점" }
            h.findViewById<TextView>(R.id.hSeller).text = listOf(seller, tel).filter { it.isNotEmpty() }.joinToString(" · ")
            h.findViewById<TextView>(R.id.hUser).text = if (user.isNotEmpty()) "$user 로 로그인됨" else ""
        }
    }

    private fun readLoginInfo(w: WebView) {
        w.evaluateJavascript("JSON.stringify(window.LOGIN_SHOP||null)") { r ->
            val info = try {
                // 결과는 JSON 문자열을 한 번 더 감싼 형태 ("{\"pShopName\":...}")
                val s = JSONObject("{\"v\":$r}").optString("v")
                if (s.isEmpty() || s == "null") null else JSONObject(s)
            } catch (e: Exception) { null }
            if (info != null || w.url?.contains("form.php") == true) setHeader(info)
        }
    }

    // ───────── 화면 전환 ─────────

    private fun show(tab: Tab) {
        current = tab
        webs.getOrPut(tab) { createWeb(tab) }
        webs.forEach { (t, w) -> w.visibility = if (t == tab) View.VISIBLE else View.GONE }
        b.offline.visibility = if (tab in failed) View.VISIBLE else View.GONE
        syncChecked()
        webs[tab]?.requestFocus()
    }

    private fun reloadCurrent() {
        val w = webs[current] ?: return
        failed.remove(current)
        b.offline.visibility = View.GONE
        if (w.url.isNullOrEmpty() || w.url == "about:blank") w.loadUrl(Server.base + current.path) else w.reload()
    }

    private fun onBack() {
        val w = webs[current]
        when {
            b.drawer.isDrawerOpen(GravityCompat.START) -> b.drawer.closeDrawer(GravityCompat.START)
            w != null && w.canGoBack() -> w.goBack()
            current != Tab.MOBILE -> show(Tab.MOBILE)
            System.currentTimeMillis() - lastBack < 2000 -> finish()
            else -> {
                lastBack = System.currentTimeMillis()
                Toast.makeText(this, R.string.exit_confirm, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ───────── 웹화면 ─────────

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun createWeb(tab: Tab): WebView {
        val w = WebView(this)
        w.layoutParams = FrameLayout.LayoutParams(-1, -1)
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true            // 판매점 정보 저장(localStorage)
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100                      // 기기 글자 크기 설정 때문에 화면이 깨지지 않게
            builtInZoomControls = true          // 태블릿에서 두 손가락 확대 허용
            displayZoomControls = false
            // 서버가 앱인지 알 수 있게 표시 (인쇄 연동 단계에서 사용)
            userAgentString = "$userAgentString RatePlanner/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        // 페이지가 그려지기 전에 상단바 숨김 스타일을 넣는다 (지원 안 되는 기기는 로딩 뒤에 넣음)
        val early = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (early) WebViewCompat.addDocumentStartJavaScript(w, hideCss, setOf(Uri.parse(Server.base).let { "${it.scheme}://${it.host}" }))

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url
                val host = Uri.parse(Server.base).host
                // 우리 서버 주소는 앱 안에서, 전화 · 문자 · 다른 사이트는 바깥 앱으로
                return if ((url.scheme == "https" || url.scheme == "http") && url.host == host) false
                else { openOutside(url); true }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (view == webs[current]) b.progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!early) view.evaluateJavascript(hideCss, null)
                b.refresh.isRefreshing = false
                if (view == webs[current]) b.progress.visibility = View.GONE
                CookieManager.getInstance().flush()
                readLoginInfo(view)
            }

            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) {
                    failed.add(tab)
                    b.refresh.isRefreshing = false
                    if (tab == current) b.offline.visibility = View.VISIBLE
                }
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                if (view == webs[current]) b.progress.setProgressCompat(p, true)
            }
        }

        // 서식지 PDF 등 내려받기 : 기기의 브라우저 · 뷰어로 넘긴다
        w.setDownloadListener { url, _, _, _, _ -> openOutside(Uri.parse(url)) }

        b.webHost.addView(w)
        w.loadUrl(Server.base + tab.path)
        return w
    }

    private fun openOutside(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "열 수 있는 앱이 없어요", Toast.LENGTH_SHORT).show()
        }
    }
}
