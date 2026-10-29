package com.nanhe.qalamkar;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final int FILE_CHOOSER = 1001;

    private WebView webView;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private long lastInitAt = 0;
    private String pendingText = null;
    private String pendingLang = null;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int MIC_REQ = 1002;
    private PermissionRequest pendingPermission;

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == MIC_REQ && pendingPermission != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                pendingPermission.grant(pendingPermission.getResources());
            } else {
                pendingPermission.deny();
            }
            pendingPermission = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initTts();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // microphone for poem recordings
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED) {
                            pendingPermission = request;
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQ);
                        } else {
                            request.grant(request.getResources());
                        }
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new Bridge(), "AndroidTTS");
        webView.loadUrl("file:///android_asset/index.html");
    }

    /** (Re)starts the phone's text-to-speech engine. Safe to call again if it stopped. */
    private synchronized void initTts() {
        ttsReady = false;
        lastInitAt = SystemClock.elapsedRealtime();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
        }
        tts = new TextToSpeech(this, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setSpeechRate(0.8f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { notifyDone(id); }
                @Override public void onError(String id) { notifyDone(id); }
            });
            tts.setPitch(1.05f);
            ttsReady = true;
            if (pendingText != null) {
                String t = pendingText;
                String l = pendingLang;
                pendingText = null;
                pendingLang = null;
                say(t, l);
            }
        } else {
            ttsReady = false;
        }
    }

    /** Tells the poems page that a line finished, so it can highlight the next one. */
    private void notifyDone(final String id) {
        if (id == null || !id.startsWith("p")) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.evaluateJavascript("window.__poemTtsDone && window.__poemTtsDone('" + id + "')", null);
                }
            }
        });
    }

    private void sayId(String text, String lang, String id) {
        int result = tts.setLanguage(Locale.forLanguageTag(lang));
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (lang.startsWith("ur")) {
                int h = tts.setLanguage(Locale.forLanguageTag("hi-IN"));
                if (h == TextToSpeech.LANG_MISSING_DATA || h == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.ENGLISH);
            } else {
                tts.setLanguage(Locale.ENGLISH);
            }
        }
        Bundle b = new Bundle();
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, id);
        if (r == TextToSpeech.ERROR) notifyDone(id);
    }

    private void say(String text, String lang) {
        int result = tts.setLanguage(Locale.forLanguageTag(lang));
        if (result == TextToSpeech.LANG_MISSING_DATA
                || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.ENGLISH);
        }
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nq");
        if (r == TextToSpeech.ERROR) {
            // engine stopped working: restart it and say the text once it is back
            pendingText = text;
            pendingLang = lang;
            initTts();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the phone may have stopped the engine while the app was in the background
        if (!ttsReady && SystemClock.elapsedRealtime() - lastInitAt > 3000) {
            initTts();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    /** Called from the page: AndroidTTS.available() and AndroidTTS.speak(text, lang). */
    private class Bridge {
        @JavascriptInterface
        public boolean available() {
            if (!ttsReady && SystemClock.elapsedRealtime() - lastInitAt > 3000) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        initTts();
                    }
                });
            }
            return ttsReady;
        }

        /** Urdu voice if this phone has one, otherwise the English text in the English voice. */
        @JavascriptInterface
        public void speak2(final String urText, final String urLang,
                           final String enText, final String enLang) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!ttsReady) {
                        pendingText = enText;
                        pendingLang = enLang;
                        if (SystemClock.elapsedRealtime() - lastInitAt > 3000) {
                            initTts();
                        }
                        return;
                    }
                    boolean urdu = false;
                    try {
                        int a = tts.isLanguageAvailable(Locale.forLanguageTag(urLang));
                        urdu = a >= TextToSpeech.LANG_AVAILABLE;
                    } catch (Exception ignored) {
                    }
                    if (urdu) {
                        say(urText, urLang);
                    } else {
                        say(enText, enLang);
                    }
                }
            });
        }

        @JavascriptInterface
        public void speakId(final String text, final String lang, final String id) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!ttsReady) {
                        if (SystemClock.elapsedRealtime() - lastInitAt > 3000) initTts();
                        notifyDone(id);
                        return;
                    }
                    sayId(text, lang, id);
                }
            });
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void speak(final String text, final String lang) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!ttsReady) {
                        pendingText = text;
                        pendingLang = lang;
                        if (SystemClock.elapsedRealtime() - lastInitAt > 3000) {
                            initTts();
                        }
                        return;
                    }
                    say(text, lang);
                }
            });
        }
    }
}
