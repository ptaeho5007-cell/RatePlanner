# 웹페이지에서 부르는 자바스크립트 연결 함수는 이름을 바꾸지 않는다
-keepclassmembers class kr.co.rateplanner.** {
    @android.webkit.JavascriptInterface <methods>;
}
