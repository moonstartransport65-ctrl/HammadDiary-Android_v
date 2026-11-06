package com.nanhe.qalamkar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final int FILE_CHOOSER = 1001;
    private static final int MIC_FIRST_RUN = 2001;
    private static final int MIC_WEBVIEW = 2002;

    private WebView webView;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private long lastInitAt = 0;
    private String pendingText = null;
    private String pendingLang = null;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingMicRequest;

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
            /** The page asks for the microphone (child's recording): pass it on to the phone's permission. */
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleWebPermission(request);
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingMicRequest == request) {
                    pendingMicRequest = null;
                }
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

        webView.post(new Runnable() {
            @Override
            public void run() {
                askMicFirstTime();
            }
        });
    }

    private boolean micGranted() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** First time the app is opened: explain, then ask the phone for the microphone permission. */
    private void askMicFirstTime() {
        if (micGranted()) {
            return;
        }
        SharedPreferences sp = getSharedPreferences("hammad_diary", MODE_PRIVATE);
        if (sp.getBoolean("mic_asked", false)) {
            return;
        }
        sp.edit().putBoolean("mic_asked", true).apply();
        new AlertDialog.Builder(this)
                .setTitle(R.string.mic_title)
                .setMessage(R.string.mic_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.mic_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (Build.VERSION.SDK_INT >= 23) {
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                                    MIC_FIRST_RUN);
                        }
                    }
                })
                .show();
    }

    private void handleWebPermission(PermissionRequest request) {
        boolean wantsMic = false;
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                wantsMic = true;
            }
        }
        if (!wantsMic) {
            request.deny();
            return;
        }
        if (micGranted()) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            return;
        }
        if (pendingMicRequest != null) {
            pendingMicRequest.deny();
        }
        pendingMicRequest = request;
        new AlertDialog.Builder(this)
                .setTitle(R.string.mic_title)
                .setMessage(R.string.mic_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.mic_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (Build.VERSION.SDK_INT >= 23) {
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                                    MIC_WEBVIEW);
                        }
                    }
                })
                .setNegativeButton(R.string.mic_no, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        PermissionRequest r = pendingMicRequest;
                        pendingMicRequest = null;
                        if (r != null) {
                            r.deny();
                        }
                    }
                })
                .show();
    }

    /** The phone no longer shows its own popup: take the person straight to this app's permission page. */
    private void offerSettings() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.mic_title)
                .setMessage(R.string.mic_settings_msg)
                .setPositiveButton(R.string.mic_settings_btn, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            i.setData(Uri.fromParts("package", getPackageName(), null));
                            startActivity(i);
                        } catch (Exception ignored) {
                        }
                    }
                })
                .setNegativeButton(R.string.mic_no, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_WEBVIEW) {
            PermissionRequest r = pendingMicRequest;
            pendingMicRequest = null;
            if (r != null) {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    r.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    r.deny();
                }
            }
        }
        boolean denied = grantResults.length == 0
                || grantResults[0] != PackageManager.PERMISSION_GRANTED;
        if (denied && requestCode == MIC_WEBVIEW && Build.VERSION.SDK_INT >= 23
                && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            offerSettings();
        }
    }

    /** Tells the page that a poem line finished (or failed): window.__poemTtsDone(id). */
    private void notifyPoemDone(final String id) {
        if (id == null || !id.startsWith("p") || webView == null) {
            return;
        }
        final String safe = id.replaceAll("[^A-Za-z0-9_]", "");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.evaluateJavascript(
                            "window.__poemTtsDone && window.__poemTtsDone('" + safe + "')", null);
                }
            }
        });
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
            tts.setPitch(1.05f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    notifyPoemDone(utteranceId);
                }

                @Override
                public void onError(String utteranceId) {
                    notifyPoemDone(utteranceId);
                }
            });
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
        /** Lets the page know this app build has the microphone popup (new MainActivity). */
        @JavascriptInterface
        public String version() {
            return "mic2";
        }

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

        /**
         * Poem line: speaks text in lang and calls window.__poemTtsDone(id) when finished.
         * Returns "ok", "notready" (engine still starting) or "nolang" (no voice for this language).
         */
        @JavascriptInterface
        public String speakId(final String text, final String lang, final String id) {
            if (!ttsReady) {
                if (SystemClock.elapsedRealtime() - lastInitAt > 3000) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            initTts();
                        }
                    });
                }
                return "notready";
            }
            final Locale loc = Locale.forLanguageTag(lang);
            try {
                if (tts.isLanguageAvailable(loc) < TextToSpeech.LANG_AVAILABLE) {
                    return "nolang";
                }
            } catch (Exception e) {
                return "notready";
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        tts.setLanguage(loc);
                        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
                        if (r == TextToSpeech.ERROR) {
                            notifyPoemDone(id);
                        }
                    } catch (Exception e) {
                        notifyPoemDone(id);
                    }
                }
            });
            return "ok";
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (tts != null) {
                            tts.stop();
                        }
                    } catch (Exception ignored) {
                    }
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
