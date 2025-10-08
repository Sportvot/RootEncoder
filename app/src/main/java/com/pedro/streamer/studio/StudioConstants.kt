package com.pedro.streamer.studio

object StudioConstants {
    private const val IS_TEST = true

    private const val MAIN_WEBVIEW_URL_TEST = "https://studio-test.sportvot.com"
    private const val MAIN_WEBVIEW_URL_PROD = "https://studio.sportvot.com"
    val MAIN_WEBVIEW_URL: String
        get() = if (IS_TEST) MAIN_WEBVIEW_URL_TEST else MAIN_WEBVIEW_URL_PROD

    private const val SSO_URL_PROD =
        "https://accounts.sportvot.com/studio?redirect_url=$MAIN_WEBVIEW_URL_PROD"
    private const val SSO_URL_TEST =
        "https://accounts-test.sportvot.com/studio?redirect_url=$MAIN_WEBVIEW_URL_TEST"
    val SSO_URL: String
        get() = if (IS_TEST) SSO_URL_TEST else SSO_URL_PROD

    private const val TEST_URL = "https://interapis.test.sportvot.com"
    private const val PROD_URL = "https://interapis.sportvot.com"
    val BASE_URL: String
        get() = if (IS_TEST) TEST_URL else PROD_URL

    val SCORING_OVERLAY_URL = "$MAIN_WEBVIEW_URL/scoring-overlay-app"

    private const val TEST_OVERLAY = "https://template-engine-test.sportvot.com"
    private const val PROD_OVERLAY = "https://template-engine.sportvot.com"
    val OVERLAY_URL: String
        get() = if (IS_TEST) TEST_OVERLAY else PROD_OVERLAY

    private const val TEST_OTT = "https://ott-test.sportvot.com"
    private const val PROD_OTT = "https://sportvot.com"
    val OTT_URL: String
        get() = if (IS_TEST) TEST_OTT else PROD_OTT

    val REFRESH_ID_KEY = "REFRESH_ID"
    val REFRESH_TOKEN_KEY = "REFRESH_TOKEN"
    val MATCH_ID_KEY = "MATCH_ID"
}
