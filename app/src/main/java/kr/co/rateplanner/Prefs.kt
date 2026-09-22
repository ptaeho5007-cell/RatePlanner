package kr.co.rateplanner

/** 서버 주소 (배포용 고정 : app/build.gradle.kts 의 BASE_URL) */
object Server {
    val base: String = BuildConfig.BASE_URL.let { if (it.endsWith("/")) it else "$it/" }
}

/** 사이드바 메뉴 하나 = 웹 주소 하나 */
enum class Tab(val menuId: Int, val path: String) {
    MOBILE(R.id.nav_mobile, "form.php"),                 // 무선 간편신청서
    WIRED(R.id.nav_wired, "form.php?mode=wired");        // 유선 신청서

    companion object {
        fun of(menuId: Int) = entries.firstOrNull { it.menuId == menuId }
    }
}
