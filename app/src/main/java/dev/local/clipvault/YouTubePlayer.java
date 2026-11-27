package dev.local.clipvault;

import android.graphics.Color;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.nio.charset.StandardCharsets;

final class YouTubePlayer {
    private static final String VIDEO_HOST = "www.youtube-nocookie.com";
    private static final String VIDEO_ORIGIN = "https://" + VIDEO_HOST;

    private YouTubePlayer() {}

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    static WebView create(MainActivity activity, String videoId, Runnable onError) {
        String playerUrl = VIDEO_ORIGIN + "/embed/" + videoId;
        WebView player = new WebView(activity);
        player.setBackgroundColor(Color.BLACK);
        player.setContentDescription("YouTube 內嵌播放器");
        WebSettings settings = player.getSettings();
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(player, false);

        player.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() || !"https".equals(request.getUrl().getScheme())
                        || !VIDEO_HOST.equals(request.getUrl().getHost());
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError failure) {
                showPlayerError(request);
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                      WebResourceResponse failure) {
                showPlayerError(request);
            }

            private void showPlayerError(WebResourceRequest request) {
                if (request.isForMainFrame() || request.getUrl().toString().startsWith(playerUrl)) {
                    onError.run();
                }
            }
        });
        // Use the Android app ID as the embed Referer, per YouTube's WebView requirements.
        String html = "<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"referrer\" content=\"strict-origin-when-cross-origin\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                + "style-src 'unsafe-inline'; frame-src " + VIDEO_ORIGIN + "\">"
                + "<style>html,body,iframe{width:100%;height:100%;margin:0;border:0;background:#000}</style>"
                + "<iframe title=\"YouTube\" src=\"" + playerUrl + "?autoplay=1&amp;playsinline=1&amp;fs=0\" "
                + "allow=\"autoplay; encrypted-media\"></iframe>";
        player.loadDataWithBaseURL("https://" + activity.getPackageName() + "/", html, "text/html",
                StandardCharsets.UTF_8.name(), null);
        return player;
    }
}
