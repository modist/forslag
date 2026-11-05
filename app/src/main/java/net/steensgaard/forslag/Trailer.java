package net.steensgaard.forslag;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * A trailer full screen, in YouTube's own embedded player (the IFrame Player API) in a WebView over the app. The
 * remote is the app's: OK or Play/Pause pauses and plays, Left and Right go 10 seconds back or on, Back closes.
 * It closes by itself when the trailer ends. When YouTube won't play it here, it says so and stays until Back;
 * it never hands the trailer to another app.
 */
final class Trailer {
    /** The page's address: YouTube wants an origin and a Referer, and refuses to play in a page without them. */
    private static final String ORIGIN = "https://steensgaard.net";
    /** How long YouTube has to start the trailer before the player says it can't. */
    private static final long START_MS = 20_000;

    private final Activity activity;
    private final FrameLayout layer;
    private final TextView message;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable gaveUp = this::failed;
    private WebView web;
    private boolean started;

    Trailer(Activity activity, FrameLayout root) {
        this.activity = activity;
        layer = new FrameLayout(activity);
        layer.setBackgroundColor(Color.BLACK);
        layer.setVisibility(View.GONE);
        message = new TextView(activity);
        message.setTextColor(0xCCFFFFFF);
        message.setTextSize(18);
        message.setGravity(Gravity.CENTER);
        layer.addView(message, new FrameLayout.LayoutParams(-1, -1));
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
    }

    boolean showing() {
        return layer.getVisibility() == View.VISIBLE;
    }

    @SuppressLint("SetJavaScriptEnabled")
    void play(String youtubeId) {
        close();
        started = false;
        message.setText(R.string.loading);
        message.setVisibility(View.VISIBLE);
        web = new WebView(activity);
        web.setBackgroundColor(Color.BLACK);
        // The remote goes to the app, which tells the player; the page never takes the focus.
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // A tap on the YouTube logo or a title would leave the player; nothing leaves it.
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Forslag");
        layer.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
        layer.setVisibility(View.VISIBLE);
        web.loadDataWithBaseURL(ORIGIN + "/forslag/", page(youtubeId), "text/html", "utf-8", null);
        main.postDelayed(gaveUp, START_MS);
    }

    /** Stops the trailer and removes the player; its sound stops with it. */
    void close() {
        main.removeCallbacks(gaveUp);
        if (web != null) {
            web.loadUrl("about:blank");
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        layer.setVisibility(View.GONE);
    }

    /** The remote while the trailer shows. Back closes; the rest goes to the player. True when used. */
    boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) close();
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                js("toggle()");
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                js("player&&player.playVideo()");
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                js("player&&player.pauseVideo()");
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                js("seek(-10)");
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                js("seek(10)");
                break;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                close();
                break;
            default:
                // Volume and the like stay with the TV.
                return code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN
                        && code != KeyEvent.KEYCODE_VOLUME_MUTE;
        }
        return true;
    }

    private void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    private void failed() {
        if (web == null) return;
        web.setVisibility(View.INVISIBLE);
        js("player&&player.stopVideo()");
        message.setText(R.string.trailer_error);
        message.setVisibility(View.VISIBLE);
    }

    /** Called from the page, on a WebView thread. */
    private final class Bridge {
        @JavascriptInterface
        public void state(int s) {
            main.post(() -> {
                if (web == null) return;
                if (s == 1 && !started) {
                    // Playing: the player shows, the waiting is over.
                    started = true;
                    main.removeCallbacks(gaveUp);
                    message.setVisibility(View.GONE);
                } else if (s == 0) {
                    close();
                }
            });
        }

        @JavascriptInterface
        public void error(int code) {
            main.post(Trailer.this::failed);
        }
    }

    /** YouTube's IFrame player, full screen, without its own controls, starting at once with sound. */
    static String page(String id) {
        String safe = id.replaceAll("[^A-Za-z0-9_-]", "");
        return "<!doctype html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
                + "#p{position:absolute;inset:0;width:100%;height:100%}</style></head><body><div id=p></div>"
                + "<script>var player;"
                + "function onYouTubeIframeAPIReady(){player=new YT.Player('p',{width:'100%',height:'100%',"
                + "videoId:'" + safe + "',playerVars:{autoplay:1,controls:0,rel:0,playsinline:1,fs:0,"
                + "iv_load_policy:3,disablekb:1,origin:'" + ORIGIN + "'},events:{"
                + "onReady:function(e){e.target.playVideo()},"
                + "onStateChange:function(e){Forslag.state(e.data)},"
                + "onError:function(e){Forslag.error(e.data)}}})}"
                + "function toggle(){if(!player)return;"
                + "if(player.getPlayerState()==1)player.pauseVideo();else player.playVideo()}"
                + "function seek(s){if(player)player.seekTo(Math.max(0,player.getCurrentTime()+s),true)}"
                + "</script><script src=\"https://www.youtube.com/iframe_api\"></script></body></html>";
    }
}
