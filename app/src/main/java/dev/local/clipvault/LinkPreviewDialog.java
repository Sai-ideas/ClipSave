package dev.local.clipvault;

import android.app.AlertDialog;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class LinkPreviewDialog {
    private static final ExecutorService FETCH = Executors.newSingleThreadExecutor();
    private static final String VIDEO_HOST = "www.youtube-nocookie.com";
    private static final String VIDEO_ORIGIN = "https://" + VIDEO_HOST;

    private final MainActivity activity;
    private final Vault.Entry entry;
    private final URI url;
    private final String videoId;
    private Future<?> pending;
    private AlertDialog dialog;
    private TextView status;
    private ImageView thumbnail;
    private Button load;

    LinkPreviewDialog(MainActivity activity, Vault.Entry entry) {
        this.activity = activity;
        this.entry = entry;
        url = URI.create(entry.url());
        videoId = LinkPreview.youtubeId(url);
    }

    void show() {
        LinearLayout body = activity.dialogBody();
        int ink = activity.getColor(R.color.vault_text);
        int muted = activity.getColor(R.color.vault_secondary_text);
        int accent = activity.getColor(R.color.vault_accent);
        int panel = activity.getColor(R.color.vault_surface_soft);
        body.addView(activity.text(url.getHost(), 17, ink, true));
        TextView address = activity.text(entry.url(), 14, muted, false);
        address.setMaxLines(3);
        address.setEllipsize(TextUtils.TruncateAt.END);
        if (!entry.sensitive()) address.setTextIsSelectable(true);
        body.addView(address, activity.margins(-1, -2, 4, 8));

        thumbnail = new ImageView(activity);
        thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumbnail.setAdjustViewBounds(true);
        thumbnail.setMaxHeight(activity.dp(200));
        thumbnail.setContentDescription("網址縮圖");
        thumbnail.setVisibility(View.GONE);
        body.addView(thumbnail, activity.margins(-1, -2, 4, 8));
        status = activity.text("", 16, ink, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        body.addView(status, activity.margins(-1, -2, 4, 8));

        if (entry.sensitive()) {
            body.addView(activity.text("載入預覽或播放時，會連線到該網站。", 13, muted, false),
                    activity.margins(-1, -2, 0, 8));
        }
        if (!videoId.isEmpty()) {
            body.addView(activity.button("播放 YouTube", accent, activity.getColor(R.color.vault_on_accent),
                    this::play), activity.margins(-1, -2, 4, 4));
        }
        load = activity.button("載入預覽", videoId.isEmpty() ? accent : panel,
                videoId.isEmpty() ? activity.getColor(R.color.vault_on_accent) : accent, this::load);
        body.addView(load, activity.margins(-1, -2, 4, 8));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        dialog = activity.show(new AlertDialog.Builder(activity).setTitle("網址預覽").setView(scroll)
                .setNegativeButton("關閉", null)
                .setPositiveButton(videoId.isEmpty() ? "在瀏覽器開啟" : "在 YouTube 開啟",
                        (ignored, which) -> activity.openLink(entry.url())),
                entry.sensitive(), () -> {
                    if (pending != null) pending.cancel(true);
                });
    }

    private void load() {
        if (!activity.canAct() || !dialog.isShowing()) return;
        showStatus("正在載入預覽…");
        load.setEnabled(false);
        pending = FETCH.submit(() -> {
            LinkPreview.Result result;
            try {
                result = LinkPreview.load(url);
            } catch (java.io.IOException | SecurityException error) {
                activity.runOnUiThread(() -> showResult(null));
                return;
            }
            activity.runOnUiThread(() -> showResult(result));
        });
    }

    private void showResult(LinkPreview.Result result) {
        if (!dialog.isShowing() || activity.isDestroyed()) return;
        if (result == null) {
            showStatus("無法載入網址預覽。");
            load.setText("重試");
            load.setEnabled(true);
            return;
        }
        boolean hasThumbnail = result.thumbnail() != null;
        if (hasThumbnail) {
            thumbnail.setImageBitmap(result.thumbnail());
            thumbnail.setVisibility(View.VISIBLE);
        }
        if (!result.title().isEmpty()) showStatus(result.title());
        else if (!hasThumbnail) showStatus("網站未提供預覽資訊。");
        else status.setVisibility(View.GONE);
        load.setVisibility(View.GONE);
    }

    private void showStatus(String value) {
        status.setText(value);
        status.setVisibility(View.VISIBLE);
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void play() {
        if (!activity.canAct() || !dialog.isShowing()) return;
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

        LinearLayout body = activity.dialogBody();
        body.addView(player, activity.margins(-1, activity.dp(240), 4, 8));
        TextView error = activity.text("無法載入播放器。可在 YouTube 開啟影片。",
                14, activity.getColor(R.color.vault_secondary_text), false);
        error.setVisibility(View.GONE);
        body.addView(error);
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
                    error.setVisibility(View.VISIBLE);
                }
            }
        });
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        activity.show(new AlertDialog.Builder(activity).setTitle("YouTube 影片").setView(scroll)
                .setNegativeButton("關閉", null)
                .setPositiveButton("在 YouTube 開啟",
                        (ignored, which) -> activity.openLink("https://www.youtube.com/watch?v=" + videoId)),
                entry.sensitive(), () -> {
                    body.removeView(player);
                    player.stopLoading();
                    player.destroy();
                });
        // Use the Android app ID as the embed Referer, per YouTube's WebView requirements.
        String html = "<!doctype html><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"referrer\" content=\"strict-origin-when-cross-origin\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                + "style-src 'unsafe-inline'; frame-src " + VIDEO_ORIGIN + "\">"
                + "<style>html,body,iframe{width:100%;height:100%;margin:0;border:0;background:#000}</style>"
                + "<iframe title=\"YouTube\" src=\"" + playerUrl + "?autoplay=1&amp;playsinline=1&amp;fs=0\" "
                + "allow=\"autoplay; encrypted-media; picture-in-picture\"></iframe>";
        player.loadDataWithBaseURL("https://" + activity.getPackageName() + "/", html, "text/html",
                StandardCharsets.UTF_8.name(), null);
    }
}
