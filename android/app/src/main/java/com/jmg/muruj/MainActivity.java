package com.jmg.muruj;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * التطبيق = غلاف (WebView) بيفتح موقعك الحي وكل صفحاته جوه التطبيق (من غير ما يحوّلك لمتصفح).
 * أي تعديل في الموقع أو الباك إند بيظهر هنا فوراً، وتسجيل الدخول والبيانات هي نفسها.
 */
public class MainActivity extends AppCompatActivity {

    private WebView web;
    private FrameLayout root;
    private ProgressBar bar;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private WebChromeClient chrome;
    private ValueCallback<Uri[]> filePathCb;
    private ActivityResultLauncher<Intent> chooser;
    private String startUrl;
    private String host;
    private volatile String curUrl = "";
    private OutputStream dlOut;

    // بيتحقن في صفحات موقعك بس: تحميل الملفات اللي بتتولد في الصفحة (ZIP/CSV/صور) + المشاركة
    private static final String HOOK = """
        (function(){
          if (window.__appHook) return; window.__appHook = 1;
          var CH = 786432;
          function send(name, b) {
            return b.arrayBuffer().then(function(ab) {
              var buf = new Uint8Array(ab), i = 0, n = 0;
              do {
                var part = buf.subarray(i, i + CH), s = '';
                for (var k = 0; k < part.length; k += 8192)
                  s += String.fromCharCode.apply(null, part.subarray(k, k + 8192));
                i += CH;
                AndroidApp.saveChunk(name, b.type || 'application/octet-stream', n++, btoa(s), i >= buf.length);
              } while (i < buf.length);
            });
          }
          var c = HTMLAnchorElement.prototype.click;
          HTMLAnchorElement.prototype.click = function() {
            var h = this.href || '';
            if (this.download && (h.indexOf('blob:') === 0 || h.indexOf('data:') === 0)) {
              var nm = this.download;
              fetch(h).then(function(r){ return r.blob(); }).then(function(b){ return send(nm, b); });
              return;
            }
            return c.apply(this, arguments);
          };
          if (!navigator.share) navigator.share = function(d) {
            AndroidApp.share(d.title || '', d.text || '', d.url || '');
            return Promise.resolve();
          };
        })();
        """;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startUrl = getString(R.string.start_url);
        Uri su = Uri.parse(startUrl);
        host = su.getHost() == null ? "" : su.getHost();

        // يتظبط تلقائياً على أي شاشة: نوتش، شريط الحالة، أزرار التنقل، الكيبورد
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(getWindow(), root);
        wic.setAppearanceLightStatusBars(false);
        wic.setAppearanceLightNavigationBars(false);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(0xFFE8B553));
        bar.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Math.round(3 * getResources().getDisplayMetrics().density));
        bl.gravity = Gravity.TOP;
        root.addView(bar, bl);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);                 // تسجيل الدخول والكاش (localStorage / IndexedDB)
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);                           // حجم خط النظام ما يبوّظش التصميم
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);           // window.open بيفتح جوه نفس التطبيق
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " MurujApp");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);     // فيديوهات يوتيوب المدمجة

        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        // اختيار الصور والملفات من الجهاز
        chooser = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (filePathCb == null) return;
            Uri[] res = null;
            Intent d = r.getData();
            if (r.getResultCode() == RESULT_OK && d != null) {
                if (d.getClipData() != null) {
                    int n = d.getClipData().getItemCount();
                    res = new Uri[n];
                    for (int i = 0; i < n; i++) res[i] = d.getClipData().getItemAt(i).getUri();
                } else if (d.getData() != null) {
                    res = new Uri[]{d.getData()};
                }
            }
            filePathCb.onReceiveValue(res);
            filePathCb = null;
        });

        chrome = new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) {
                if (filePathCb != null) filePathCb.onReceiveValue(null);
                filePathCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                String type = "*/*";
                String[] acc = p.getAcceptTypes();
                if (acc != null && acc.length == 1 && acc[0] != null && acc[0].matches("[\\w.+-]+/[\\w.*+-]+")) type = acc[0];
                i.setType(type);
                if (p.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    chooser.launch(Intent.createChooser(i, null));
                } catch (Exception e) {
                    filePathCb = null;
                    return false;
                }
                return true;
            }

            // ملء الشاشة للفيديو (فوق كل حاجة وبدون شرايط النظام)
            @Override
            public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = v;
                customCb = cb;
                ((FrameLayout) getWindow().getDecorView()).addView(v, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.INVISIBLE);
                WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), root);
                c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsetsCompat.Type.systemBars());
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                ((FrameLayout) getWindow().getDecorView()).removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                if (customCb != null) customCb.onCustomViewHidden();
                WindowCompat.getInsetsController(getWindow(), root).show(WindowInsetsCompat.Type.systemBars());
            }
        };
        web.setWebChromeClient(chrome);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return route(r.getUrl());
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                curUrl = url == null ? "" : url;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                curUrl = url == null ? "" : url;
                if (isOwn(curUrl)) v.evaluateJavascript(HOOK, null);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) showOffline();
            }

            @SuppressLint("Override")
            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                recreate();   // لو المحرك وقع، نعيد فتح التطبيق بدل ما يقفل
                return true;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (url.startsWith("blob:") || url.startsWith("data:")) return; // بيتعامل معاها سكريبت التحميل
            try {
                DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
                rq.setMimeType(mime);
                rq.addRequestHeader("User-Agent", ua);
                rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime));
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(rq);
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, R.string.no_blob_download, Toast.LENGTH_SHORT).show();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (customView != null) {
                    chrome.onHideCustomView();
                } else if (web.canGoBack()) {
                    web.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(startUrl);
    }

    private boolean isOwn(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h != null && (h.equals(host) || h.endsWith("." + host));
        } catch (Exception e) {
            return false;
        }
    }

    /** true = التطبيق/النظام اتعامل معاه، false = يتحمّل جوه الـWebView (كل صفحات http/https بتفتح جوه التطبيق). */
    private boolean route(Uri u) {
        String sch = u.getScheme();
        if (sch == null) return false;
        if (sch.equals("http") || sch.equals("https")) {
            String h = u.getHost() == null ? "" : u.getHost();
            if (h.equals("wa.me") || h.endsWith("whatsapp.com")) return openWhatsApp(u);
            return false;
        }
        if (sch.equals("about") || sch.equals("blob") || sch.equals("data") || sch.equals("javascript")) return false;
        try {   // tel / mailto / sms / whatsapp / intent
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException ignored) { }
        return true;
    }

    private boolean openWhatsApp(Uri u) {
        for (String pkg : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, u);
                i.setPackage(pkg);
                startActivity(i);
                return true;
            } catch (ActivityNotFoundException ignored) { }
        }
        return false;   // واتساب مش متثبت: الصفحة بتفتح جوه التطبيق
    }

    private void showOffline() {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='background:#000;color:#f2efe8;font-family:sans-serif;display:flex;flex-direction:column;"
                + "align-items:center;justify-content:center;height:100vh;margin:0;text-align:center'>"
                + "<h3>" + getString(R.string.offline_title) + "</h3>"
                + "<a href='" + startUrl + "' style='margin-top:16px;padding:12px 28px;border-radius:30px;"
                + "background:#E8B553;color:#000;text-decoration:none;font-weight:bold'>"
                + getString(R.string.offline_retry) + "</a></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private void toast(int res) {
        runOnUiThread(() -> Toast.makeText(this, res, Toast.LENGTH_SHORT).show());
    }

    private void closeDl() {
        if (dlOut != null) {
            try { dlOut.close(); } catch (IOException ignored) { }
            dlOut = null;
        }
    }

    private OutputStream openDownload(String name, String mime) throws IOException {
        String safe = name == null || name.isEmpty() ? "file" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new IOException("insert failed");
            return getContentResolver().openOutputStream(u);
        }
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir != null) dir.mkdirs();
        return new FileOutputStream(new File(dir, safe));
    }

    /** جسر JavaScript ↔ أندرويد. بيشتغل مع صفحات موقعك بس. */
    private class Bridge {
        @JavascriptInterface
        public void share(String title, String text, String url) {
            if (!isOwn(curUrl)) return;
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            String body = ((text == null ? "" : text) + " " + (url == null ? "" : url)).trim();
            send.putExtra(Intent.EXTRA_TEXT, body);
            if (title != null && !title.isEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, title);
            runOnUiThread(() -> startActivity(Intent.createChooser(send, null)));
        }

        @JavascriptInterface
        public synchronized void saveChunk(String name, String mime, int idx, String b64, boolean last) {
            if (!isOwn(curUrl)) return;
            try {
                if (idx == 0) {
                    closeDl();
                    dlOut = openDownload(name, mime);
                }
                if (dlOut == null) return;
                dlOut.write(Base64.decode(b64, Base64.DEFAULT));
                if (last) {
                    closeDl();
                    toast(R.string.saved);
                }
            } catch (Exception e) {
                closeDl();
                toast(R.string.save_failed);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    protected void onDestroy() {
        closeDl();
        root.removeAllViews();
        web.destroy();
        super.onDestroy();
    }
}
