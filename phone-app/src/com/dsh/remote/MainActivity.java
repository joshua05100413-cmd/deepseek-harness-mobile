package com.dsh.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * DSH mobile client. The Tailscale engine runs inside this process and the
 * tunnel is established by our own VpnService; the remote workbench is web
 * tech underneath, but the shell behaves and looks like a native app.
 */
public class MainActivity extends Activity {

    private static final String UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private static final int REQ_VPN = 1001;

    private static final int ST_NEED_LOGIN = 0;
    private static final int ST_NEED_VPN = 1;
    private static final int ST_TUNNEL = 2;
    private static final int ST_DISCOVER = 3;
    private static final int ST_CONNECTING = 4;
    private static final int ST_READY = 5;
    private static final int ST_ERROR = 6;

    private int BG, BAR, TEXT, DIM, ACCENT;

    private WebView web;
    private FrameLayout stack;
    private LinearLayout overlay, mainRoot;
    private FrameLayout loginLayer;
    private WebView loginWeb;
    private TextView statusTitle, statusSub, subtitleView;
    private Button actionBtn;
    private View dot;
    private ProgressBar topBar;
    private int state = ST_CONNECTING;
    private boolean pageEverLoaded = false;
    private volatile boolean pageLoadFailed = false;
    private boolean pageLoading = false;
    private boolean serviceStartRequested = false;
    private boolean discoveryRunning = false;
    private long lastDiscoveryFail = 0;
    private String currentUrl;
    private String appliedTheme;
    private long loadStart;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            poll();
            handler.postDelayed(this, pageEverLoaded ? 4000 : 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemePalette tp = ThemePalette.get(this);
        BG = tp.bg;
        BAR = tp.bar;
        TEXT = tp.text;
        DIM = tp.dim;
        ACCENT = tp.accent;
        appliedTheme = tp.id;
        // kill the black flash before content is up: the window itself follows the theme
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
        ThemePalette.applySystemBars(getWindow(), BG, BAR);
        currentUrl = Prefs.effectiveUrl(this);
        Tunnel.get(this).ensureEngine();
        LocalForwarder.start(Prefs.hostOf(currentUrl)); // loopback services ready as early as possible
        registerNetworkWatch();
        final boolean instant = savedInstanceState != null; // recreate() after theme change
        // warm-up: mount the web surface immediately; the splash floats above it
        // so the page truly loads behind the curtain.
        buildMain();
        final FrameLayout wrap = new FrameLayout(this);
        wrap.addView(mainRoot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View splash = null;
        if (!instant) {
            bootStartMs = System.currentTimeMillis();
            splash = buildSplashView();
            final View fs = splash;
            wrap.addView(fs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        setContentView(wrap);
        applyWindowPrefs();
        applyOrientationFor(false);
        handler.post(ticker);
        if (getIntent() != null && getIntent().getBooleanExtra("login", false)) {
            handler.postDelayed(new Runnable() { @Override public void run() { beginLogin(); } }, 500);
        }
        if (!instant) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    askMicPermission(); // voice input is core: ask the system up front
                }
            }, 700);
        }
    }

    // ------------------------------------------------------------- splash

    private BootAnimView bootView;

    private View buildSplashView() {
        bootView = new BootAnimView(this);
        return bootView;
    }

    private void removeBoot() {
        try {
            if (bootView == null || !(bootView.getParent() instanceof ViewGroup)) return;
            // never cut the choreography short: hold the curtain until the
            // full pull-out sequence (~5.6s) has played at least once
            long elapsed = System.currentTimeMillis() - bootStartMs;
            if (elapsed < 6500) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() { removeBoot(); }
                }, 6500 - elapsed);
                return;
            }
            final ViewGroup p = (ViewGroup) bootView.getParent();
            bootView.animate().alpha(0f).setDuration(450).withEndAction(new Runnable() {
                @Override public void run() {
                    try { p.removeView(bootView); } catch (Exception ignored) {}
                }
            }).start();
        } catch (Exception ignored) {}
    }

    // --------------------------------------------------------------- main

    private void buildMain() {
        mainRoot = new LinearLayout(this);
        mainRoot.setOrientation(LinearLayout.VERTICAL);
        mainRoot.setBackgroundColor(BG);
        mainRoot.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(BAR);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(3), dp(4), dp(3));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        titles.setPadding(dp(10), 0, 0, 0);
        LinearLayout switcher = new LinearLayout(this);
        switcher.setOrientation(LinearLayout.HORIZONTAL);
        switcher.setGravity(Gravity.CENTER_VERTICAL);
        pillDsh = modePill("DSHgo");
        pillZcode = modePill("ZCode go");
        pillDsh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchMode("dsh"); }
        });
        pillZcode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchMode("zcode"); }
        });
        switcher.addView(pillDsh);
        switcher.addView(pillZcode);
        titles.addView(switcher);
        appMode = Prefs.get(this).getString(Prefs.MODE, "dsh");
        stylePills();
        subtitleView = new TextView(this);
        subtitleView.setText("启动中…");
        subtitleView.setTextColor(DIM);
        subtitleView.setTextSize(11);
        titles.addView(subtitleView);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0xFFF5C542);
        dot.setBackground(d);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(9), dp(9));
        dl.setMargins(0, 0, dp(12), 0);
        dot.setLayoutParams(dl);
        bar.addView(dot);

        bar.addView(iconButton(R.drawable.ic_refresh, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (web != null && pageEverLoaded) web.reload();
                else { pageEverLoaded = false; poll(); }
            }
        }));
        globeBtn = iconButton(R.drawable.ic_globe, new View.OnClickListener() {
            @Override public void onClick(View v) { showSiteDialog(); }
        });
        bar.addView(globeBtn);
        bar.addView(iconButton(R.drawable.ic_settings, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        }));

        mainRoot.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        topBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        topBar.setMax(100);
        topBar.setVisibility(View.GONE);
        mainRoot.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        web = new WebView(this);
        setupWeb(web, false);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(BG);
        overlay.setPadding(dp(16), dp(16), dp(16), dp(16));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        overlay.addView(logo, new LinearLayout.LayoutParams(dp(92), dp(92)));

        statusTitle = new TextView(this);
        statusTitle.setTextColor(TEXT);
        statusTitle.setTextSize(19);
        statusTitle.setGravity(Gravity.CENTER);
        statusTitle.setPadding(0, dp(24), 0, dp(6));
        overlay.addView(statusTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusSub = new TextView(this);
        statusSub.setTextColor(DIM);
        statusSub.setTextSize(13.5f);
        statusSub.setGravity(Gravity.CENTER);
        overlay.addView(statusSub, new LinearLayout.LayoutParams(dp(290), ViewGroup.LayoutParams.WRAP_CONTENT));

        actionBtn = new Button(this);
        actionBtn.setTextColor(Color.WHITE);
        actionBtn.setTextSize(15);
        actionBtn.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(12));
        actionBtn.setBackground(bg);
        actionBtn.setPadding(dp(30), dp(6), dp(30), dp(6));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(22);
        overlay.addView(actionBtn, bl);
        actionBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onPrimaryAction(); }
        });

        // ---- login layer (Tailscale account, stays inside this app) ----
        loginLayer = new FrameLayout(this);
        loginLayer.setBackgroundColor(BG);
        loginLayer.setVisibility(View.GONE);
        LinearLayout loginTop = new LinearLayout(this);
        loginTop.setOrientation(LinearLayout.HORIZONTAL);
        loginTop.setBackgroundColor(BAR);
        loginTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView lt = new TextView(this);
        lt.setText("登录 Tailscale 账号");
        lt.setTextColor(TEXT);
        lt.setTextSize(16);
        lt.setPadding(dp(16), dp(12), 0, dp(12));
        loginTop.addView(lt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView lc = new TextView(this);
        lc.setText("✕");
        lc.setTextColor(DIM);
        lc.setTextSize(18);
        lc.setPadding(dp(18), dp(12), dp(18), dp(12));
        lc.setClickable(true);
        lc.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideLogin(); }
        });
        loginTop.addView(lc);
        LinearLayout loginWrap = new LinearLayout(this);
        loginWrap.setOrientation(LinearLayout.VERTICAL);
        loginWrap.addView(loginTop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        loginWeb = new WebView(this);
        setupWeb(loginWeb, true);
        loginWrap.addView(loginWeb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        loginLayer.addView(loginWrap, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout stack = new FrameLayout(this);
        this.stack = stack;
        stack.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stack.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stack.addView(loginLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mainRoot.addView(stack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private View iconButton(int res, View.OnClickListener click) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        // theme-aware glyph tint: fixed light vectors vanish on light themes
        iv.setColorFilter(TEXT, android.graphics.PorterDuff.Mode.SRC_IN);
        iv.setPadding(dp(13), dp(12), dp(13), dp(12));
        iv.setClickable(true);
        iv.setOnClickListener(click);
        iv.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                v.setAlpha(e.getAction() == MotionEvent.ACTION_DOWN ? 0.55f : 1f);
                return false;
            }
        });
        return iv;
    }

    // ------------------------------------------------------------ actions

    private void onPrimaryAction() {
        if (state == ST_NEED_LOGIN) {
            beginLogin();
            return;
        }
        if (state == ST_NEED_VPN) {
            requestVpn();
            return;
        }
        pageEverLoaded = false;
        poll();
    }

    // --------------------------------------------- multi-site aggregation

    /** Sites open in a floating window over the main UI — never replacing it. */
    private FrameLayout sitePopup;
    private WebView siteWeb;

    private void showSitePopup(String name, String url) {
        removeSitePopup();
        if (stack == null || url == null || url.isEmpty()) return;

        // tailnet sites need the tunnel: leave ZCode (tunnel-off) mode first
        boolean tailnet = url.contains(".ts.net");
        if (tailnet && "zcode".equals(appMode)) switchMode("dsh");

        sitePopup = new FrameLayout(this);
        sitePopup.setBackgroundColor(0x8A000000);
        sitePopup.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { removeSitePopup(); } // tap empty closes
        });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cd = new GradientDrawable();
        cd.setCornerRadius(dp(16));
        cd.setColor(BG);
        card.setBackground(cd);
        card.setClickable(true); // keep taps inside the card from falling through
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {}
        });

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(12), dp(8), dp(12));
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(TEXT);
        t.setTextSize(15);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView retry = new TextView(this);
        retry.setText("↻");
        retry.setTextColor(DIM);
        retry.setTextSize(18);
        retry.setPadding(dp(12), 0, dp(10), 0);
        retry.setClickable(true);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (siteWeb != null) siteWeb.reload();
            }
        });
        head.addView(retry);
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(DIM);
        close.setTextSize(17);
        close.setPadding(dp(10), 0, dp(8), 0);
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { removeSitePopup(); }
        });
        head.addView(close);
        card.addView(head);

        siteWeb = new WebView(this);
        setupWeb(siteWeb, true); // plain browsing: no workbench state coupling
        siteWeb.setWebChromeClient(new WebChromeClient());
        siteWeb.loadUrl(url);
        card.addView(siteWeb, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        lp.leftMargin = dp(18); lp.rightMargin = dp(18);
        lp.topMargin = dp(64); lp.bottomMargin = dp(64);
        sitePopup.addView(card, lp);
        stack.addView(sitePopup, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void removeSitePopup() {
        if (sitePopup != null && stack != null) {
            try {
                if (siteWeb != null) {
                    siteWeb.stopLoading();
                    siteWeb.loadUrl("about:blank");
                    siteWeb.destroy();
                    siteWeb = null;
                }
                stack.removeView(sitePopup);
            } catch (Exception ignored) {}
            sitePopup = null;
        }
    }

    private void showSiteDialog() {
        try {
            final org.json.JSONArray sites = Prefs.getSites(this);
            final java.util.ArrayList<String> names = new java.util.ArrayList<>();
            final java.util.ArrayList<String> urls = new java.util.ArrayList<>();
            for (int i = 0; i < sites.length(); i++) {
                org.json.JSONObject o = sites.optJSONObject(i);
                if (o != null) {
                    names.add(o.optString("name", "站点 " + (i + 1)));
                    urls.add(o.optString("url", ""));
                }
            }
            if (names.isEmpty()) {
                new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle("站点小窗")
                    .setMessage("还没有站点。到设置 → 站点管理里添加本地页面或 ZCode 链接。")
                    .setPositiveButton("去设置", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface d, int which) {
                            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
                return;
            }
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("站点小窗（弹出显示，不影响主界面）")
                .setItems(names.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        showSitePopup(names.get(which), urls.get(which));
                    }
                })
                .setNeutralButton("管理站点…", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "站点列表读取失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void switchSite(int idx) {
        try {
            org.json.JSONArray sites = Prefs.getSites(this);
            String url;
            if (idx <= 0) {
                url = Prefs.serverUrl(this);
                Prefs.get(this).edit().putString(Prefs.TARGET_MODE, "auto").apply();
                url = Prefs.effectiveUrl(this);
            } else {
                org.json.JSONObject o = sites.optJSONObject(idx - 1);
                url = o != null ? o.optString("url", Prefs.effectiveUrl(this)) : Prefs.effectiveUrl(this);
            }
            Prefs.get(this).edit().putInt(Prefs.ACTIVE_SITE, idx).apply();
            currentUrl = url;
            pageEverLoaded = false;
            pageLoading = false;
            state = ST_CONNECTING;
            statusTitle.setText(idx <= 0 ? "正在连接远程桌面" : "正在打开站点…");
            statusSub.setText(url);
            overlay.setVisibility(View.VISIBLE);
            setDot(0xFFF5C542);
            if (web != null) {
                web.stopLoading();
                web.loadUrl(url);
            }
            handler.post(ticker);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "切换失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------- DSHgo / ZCode go modes

    private String appMode = "dsh"; // dsh | zcode
    private TextView pillDsh, pillZcode;
    private View globeBtn;
    private boolean zcodeLoadStarted = false;

    /** Site mini-window is an optional power feature (default off). */
    private void applySiteSwitch() {
        if (globeBtn == null) return;
        boolean on = Prefs.get(this, Prefs.SITE_POPUP, false);
        globeBtn.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private TextView modePill(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setPadding(dp(11), dp(3), dp(11), dp(3));
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void stylePills() {
        if (pillDsh == null || pillZcode == null) return;
        boolean dsh = "dsh".equals(appMode);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(14));
        on.setColor(ACCENT);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(dp(14));
        off.setColor(0x00000000);
        pillDsh.setBackground(dsh ? on : off);
        pillDsh.setTextColor(dsh ? Color.WHITE : DIM);
        pillZcode.setBackground(dsh ? off : on);
        pillZcode.setTextColor(dsh ? DIM : Color.WHITE);
    }

    /**
     * Mode switch: DSHgo runs through the tunnel, ZCode go is direct https and
     * turns the tunnel OFF (no VPN permission theatre around a public site);
     * switching back turns it ON again automatically.
     */
    private void switchMode(String mode) {
        if (mode == null || mode.equals(appMode)) return;
        appMode = mode;
        Prefs.get(this).edit().putString(Prefs.MODE, mode).apply();
        stylePills();
        removeSitePopup();
        pageEverLoaded = false;
        pageLoading = false;
        if ("zcode".equals(mode)) {
            String url = Prefs.get(this).getString(Prefs.ZCODE_URL, "");
            currentUrl = url;
            if (url == null || url.isEmpty()) {
                stopTunnel(); // still drop the tunnel: user asked for ZCode mode
                state = ST_CONNECTING;
                statusTitle.setText("ZCode go");
                statusSub.setText("还没有 ZCode 远程链接：设置 → ZCode 远程链接 里填入");
                subtitleView.setText("ZCode 未配置");
                overlay.setVisibility(View.VISIBLE);
                actionBtn.setVisibility(View.GONE);
                setDot(0xFFF5C542);
                if (bootView != null) bootView.setStatus("ZCode go");
            } else {
                // Load FIRST (direct https works with or without the tunnel),
                // then drop the tunnel after the session is established — killing
                // it mid-handshake is what made the link "restart".
                subtitleView.setText("ZCode 直连中");
                if (bootView != null) bootView.setStatus("正在载入 ZCode…");
                if (web != null) {
                    zcodeLoadStarted = true; // poll() must not double-load this
                    pageLoading = true;
                    pageLoadFailed = false;
                    web.stopLoading();
                    web.loadUrl(url);
                }
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if ("zcode".equals(appMode)) stopTunnel();
                    }
                }, 3000);
            }
        } else {
            subtitleView.setText("隧道连接中");
            if (bootView != null) bootView.setStatus("正在建立加密隧道…");
            currentUrl = Prefs.effectiveUrl(this);
            zcodeLoadStarted = false;
            serviceStartRequested = false; // re-arm the tunnel
            handler.post(ticker);
        }
        handler.post(ticker);
    }

    private void stopTunnel() {
        try {
            Intent i = new Intent(this, TailscaleVpnService.class)
                .setAction(TailscaleVpnService.ACTION_STOP);
            startService(i);
        } catch (Exception ignored) {}
        serviceStartRequested = false;
    }

    private void beginLogin() {
        Tunnel.get(this).ensureEngine();
        // Wake the engine's control channel first (official flow: run the
        // service in foreground-only mode during interactive login).
        try {
            Intent i = new Intent(this, TailscaleVpnService.class)
                .setAction(TailscaleVpnService.ACTION_START_FOREGROUND_ONLY);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {}
        String url = Tunnel.get(this).startLogin();
        loginLayer.setVisibility(View.VISIBLE);
        statusSub.setText("请在登录页完成授权");
        if (url != null && !url.isEmpty()) {
            loginWeb.loadUrl(url);
        } else {
            statusTitle.setText("正在准备登录页…");
        }
    }

    private void hideLogin() {
        loginLayer.setVisibility(View.GONE);
        if (loginWeb != null) loginWeb.stopLoading();
    }

    private void requestVpn() {
        try {
            Intent i = VpnService.prepare(this);
            if (i != null) {
                startActivityForResult(i, REQ_VPN);
            } else {
                startTunnelService();
            }
        } catch (Exception e) {
            statusSub.setText("无法请求 VPN 权限：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            startTunnelService();
        }
    }

    private void startTunnelService() {
        Intent i = new Intent(this, TailscaleVpnService.class)
            .setAction(TailscaleVpnService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            serviceStartRequested = true;
            vpnRequestAt = System.currentTimeMillis();
        } catch (Exception e) {
            statusSub.setText("启动隧道失败：" + e.getMessage());
        }
    }

    private long vpnRequestAt = 0;

    /** System-level truth: is a VPN network actually established? */
    private boolean vpnNetworkUp() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network[] nets = cm.getAllNetworks();
            for (android.net.Network n : nets) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ---------------------------------------------------------------- web

    private void setupWeb(final WebView wv, final boolean isLogin) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        // The workbench loads local resources over http://127.0.0.1 (loopback).
        // Browsers whitelist loopback from mixed-content blocking; WebView must
        // be told explicitly, otherwise images say "file service unavailable".
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        applyWebPrefs(wv);

        wv.setBackgroundColor(BG);
        wv.setLongClickable(false);

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (isLogin) return false; // login flows anywhere
                Uri u = req.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    // ZCode mode: its SPA hops hosts during auth/session setup —
                    // keep every hop inside the WebView or the session "restarts".
                    if ("zcode".equals(appMode)) return false;
                    boolean sameHost = Prefs.hostOf(currentUrl).equals(u.getHost());
                    if (sameHost) return false;
                    if (Prefs.get(MainActivity.this, Prefs.EXT_LINKS, true)) {
                        openExternally(u.toString());
                        return true;
                    }
                    return false;
                }
                if ("about".equals(scheme)) return false;
                openExternally(u.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (isLogin) return;
                pageLoading = false;
                if (pageLoadFailed) {
                    // error pages also fire onPageFinished — never call that "ready"
                    setState(ST_ERROR);
                    return;
                }
                pageEverLoaded = true;
                setState(ST_READY);
                injectAdaptation();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, android.webkit.WebResourceError err) {
                if (!isLogin && req.isForMainFrame()) {
                    pageLoadFailed = true;
                    pageEverLoaded = false;
                    pageLoading = false;
                    setState(ST_ERROR);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel();
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                android.util.Log.i("DSHWeb", cm.messageLevel() + " " + cm.message() + " @" + cm.lineNumber());
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int p) {
                if (!isLogin) {
                    if (p < 100) {
                        topBar.setVisibility(View.VISIBLE);
                        topBar.setProgress(p);
                    } else {
                        topBar.setVisibility(View.GONE);
                    }
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                // Sites (e.g. ZCode) open sessions via window.open. Denying it
                // makes the page fall back to a full navigation — the visible
                // "session restart" bug. Capture the target URL and load it in
                // the same WebView instead.
                try {
                    WebView temp = new WebView(view.getContext());
                    temp.setWebViewClient(new WebViewClient() {
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                            final String u = req.getUrl().toString();
                            handler.post(new Runnable() {
                                @Override public void run() {
                                    if (web != null) web.loadUrl(u);
                                }
                            });
                            return true;
                        }
                    });
                    android.webkit.WebView.WebViewTransport transport =
                        (android.webkit.WebView.WebViewTransport) resultMsg.obj;
                    transport.setWebView(temp);
                    resultMsg.sendToTarget();
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (hasMicPermission()) {
                            request.grant(request.getResources());
                        } else {
                            request.deny();
                            askMicPermission();
                        }
                    }
                });
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                nativeDialog(message, null, result);
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                nativeDialog(message, result, null);
                return true;
            }
        });
    }

    private void applyWebPrefs(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setTextZoom(Prefs.fontPct(this));
        if (Prefs.get(this, Prefs.DESKTOP_UA, false)) {
            s.setUserAgentString(UA_DESKTOP);
        } else {
            // Use the exact Chrome-on-Android UA: the server may feature-detect
            // "WebView" and serve a reduced file service.
            s.setUserAgentString("Mozilla/5.0 (Linux; Android 14; SM-F7310) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
        }
    }

    private void applyWindowPrefs() {
        if (Prefs.get(this, Prefs.KEEP_SCREEN, true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (Prefs.get(this, Prefs.FULLSCREEN, false)) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void nativeDialog(String message, final JsResult confirm, final JsResult alertOnly) {
        AlertDialog.Builder b = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
        b.setMessage(message);
        b.setCancelable(false);
        if (confirm != null) {
            b.setPositiveButton(android.R.string.ok, (d, w) -> confirm.confirm());
            b.setNegativeButton(android.R.string.cancel, (d, w) -> confirm.cancel());
        } else {
            b.setPositiveButton(android.R.string.ok, (d, w) -> {
                if (alertOnly != null) alertOnly.confirm();
            });
        }
        b.show();
    }

    private void injectAdaptation() {
        // The DSH workbench adaptation (fetch cache, sidebar, dialogs…) targets
        // the workbench DOM only. ZCode is a foreign SPA — leave it untouched.
        if ("zcode".equals(appMode)) return;
        boolean autoSidebar = Prefs.get(this, Prefs.AUTO_SIDEBAR, true);
        String js =
            "(function(){" +
            " try{ window.onerror=function(m,s,l){ console.error('DSHJS-ERR '+m+' @'+l); }; }catch(e){}" +
            " try{ console.error('DSHFEAT worker='+typeof Worker+' sw='+(typeof navigator.serviceWorker)+' sab='+typeof SharedArrayBuffer+' wasm='+typeof WebAssembly+' offscr='+typeof OffscreenCanvas+' fs='+typeof (window.showOpenFilePicker)+' opfs='+(navigator.storage&&navigator.storage.getDirectory?1:0)+' indexeddb='+typeof indexedDB); }catch(e){}" +
            " try{ var of=window.fetch; window.fetch=function(){ var args=arguments; var u=(args[0]&&args[0].url)||args[0]; var key=null; var isGet=(!args[1]||!args[1].method||String(args[1].method).toUpperCase()==='GET'); if(isGet&&typeof u==='string'&&u.indexOf('/api/')>=0){ key='swr:'+u; } var run=function(n){ return of.apply(window,args).then(function(r){ if(!r.ok) console.error('DSHNET '+r.status+' '+String(u).slice(0,90)); if(key&&r.ok){ try{ r.clone().text().then(function(t){ if(t.length<300000) localStorage.setItem(key,t); }); }catch(e){} } return r; },function(e){ console.error('DSHNET fetch-FAIL '+String(u).slice(0,90)); if(key){ var c=null; try{ c=localStorage.getItem(key); }catch(x){} if(c){ console.error('DSHNET cache-hit (weak-net fallback)'); return Promise.resolve(new Response(c,{status:200,headers:{'content-type':'application/json'}})); } } if(n>0){ return new Promise(function(res){ setTimeout(res,500*(3-n)); }).then(function(){ return run(n-1); }); } throw e; }); }; return run(2); }; }catch(e){}" +
            " try{ var OW=window.WebSocket; window.WebSocket=function(u,p){ var s=new OW(u,p); s.addEventListener('error',function(){ console.error('DSHNET ws-FAIL '+u); }); return s; }; }catch(e){}" +
            " if(window.__dshAdapted) return; window.__dshAdapted=true;" +
            " var m=document.querySelector('meta[name=viewport]');" +
            " if(m) m.setAttribute('content','width=device-width, initial-scale=1, viewport-fit=cover');" +
            " var st=document.createElement('style');" +
            " st.textContent=[" +
            "  'html{-webkit-text-size-adjust:100%;}'," +
            "  'body{-webkit-tap-highlight-color:transparent;overscroll-behavior:contain;}'," +
            "  'input,textarea{font-size:16px !important;}'," +
            "  '*:not(input):not(textarea):not([contenteditable=\"true\"]){-webkit-user-select:none !important;user-select:none !important;}'," +
            "  '::-webkit-scrollbar{width:0;height:0;display:none;}'," +
            "  'textarea{border-radius:12px !important;}'," +
            "  'button:active,[role=\"button\"]:active,a:active{opacity:.65;transition:opacity .06s !important;}'," +
            "  'html{scroll-behavior:smooth;}'" +
            " ].join('\\n');" +
            " document.head.appendChild(st);" +
            " var as=document.querySelectorAll('a[target=\"_blank\"]');" +
            " for(var i=0;i<as.length;i++) as[i].removeAttribute('target');" +
            " function findToggle(){" +
            "  var bs=document.querySelectorAll('button,[role=\"button\"]');" +
            "  for(var i=0;i<bs.length;i++){" +
            "   var t=(bs[i].getAttribute('aria-label')||'')+(bs[i].getAttribute('title')||'')+(bs[i].textContent||'');" +
            "   if(t.indexOf('侧边栏')>=0&&t.indexOf('右侧')<0) return bs[i];" +
            "  } return null; }" +
            " function sidebarOpen(){" +
            "  var nav=document.querySelector('nav[aria-label=\"全局面板\"]');" +
            "  if(nav&&nav.parentElement) return (nav.parentElement.className+'').indexOf('collapsed')<0;" +
            "  return false; }" +
            " window.__dshToggleSidebar=function(){var b=findToggle(); if(b){b.click(); return true;} return false;};" +
            " window.__dshSidebarOpen=sidebarOpen;" +
            " window.__dshBackAction=function(){ if(sidebarOpen()){ var b=findToggle(); if(b){b.click(); return 1;} } return 0; };" +
            // ---------- Android drawer behavior: outside-tap closes everything ----------
            " function visiblePopouts(){" +
            "  var out=[];" +
            "  if(Date.now()-(window.__vpCacheT||0)<300&&window.__vpCache){ return window.__vpCache; }" +
            "  var els=document.querySelectorAll('[role=\"dialog\"],[role=\"menu\"],[class*=\"popover\"],[class*=\"Popover\"],[class*=\"drawer\"],[class*=\"Drawer\"],[class*=\"popup\"],[class*=\"Popup\"],[class*=\"overlay\"],[class*=\"Overlay\"],aside');" +
            "  for(var i=0;i<els.length;i++){" +
            "   var r=els[i].getBoundingClientRect();" +
            "   var cs=getComputedStyle(els[i]);" +
            "   if(r.width>60&&r.height>80&&r.left<window.innerWidth*0.75&&cs.visibility!=='hidden'&&cs.display!=='none'&&cs.opacity!=='0') out.push(els[i]);" +
            "  } window.__vpCache=out; window.__vpCacheT=Date.now(); return out; }" +
            " window.__popsAtTouchStart=0; window.__sidebarAtTouchStart=false; window.__lastTap=0;" +
            " document.addEventListener('touchstart',function(e){" +
            "  window.__lastTap=Date.now();" +
            "  window.__popsAtTouchStart=visiblePopouts().length;" +
            "  window.__sidebarAtTouchStart=sidebarOpen();" +
            "  markTouch(e);" +
            " },true);" +
            " function closeAllPopouts(x,y){" +
            "  var opts={bubbles:true,cancelable:true,clientX:x,clientY:y,view:window};" +
            "  ['pointerdown','mousedown','mouseup','click'].forEach(function(t){" +
            "   try{ document.dispatchEvent(new MouseEvent(t,opts)); }catch(err){}" +
            "  });" +
            "  try{ document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true})); }catch(err){}" +
            "  if(sidebarOpen()){ var b=findToggle(); if(b) b.click(); }" +
            "  setTimeout(function(){" +
            "   if(Date.now()-(window.__lastTap||0)<600) return;" +
            "   var still=visiblePopouts().length>0||sidebarOpen();" +
            "   if(still){ var el=document.elementFromPoint(x,y);" +
            "    if(el){ try{ el.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,clientX:x,clientY:y}));" +
            "     el.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,clientX:x,clientY:y})); el.click(); }catch(err){} }" +
            "    if(sidebarOpen()){ var b2=findToggle(); if(b2) b2.click(); } }" +
            "  },260);" +
            " }" +
            " document.addEventListener('touchend',function(e){ try{" +
            "  var popsBefore=window.__popsAtTouchStart||0;" +
            "  var sidebarBefore=window.__sidebarAtTouchStart;" +
            "  var x=(e.changedTouches&&e.changedTouches[0])?e.changedTouches[0].clientX:0;" +
            "  var y=(e.changedTouches&&e.changedTouches[0])?e.changedTouches[0].clientY:0;" +
            "  var t=e.target;" +
            "  try{ var fp=t; while(fp&&fp!==document.body){ var cs=getComputedStyle(fp);" +
            "   if(cs.position==='absolute'||cs.position==='fixed'){ return; } fp=fp.parentElement; } }catch(err){}" +
            "  if(sidebarBefore&&sidebarOpen()){" +
            "   var b=findToggle();" +
            "   if(b&&x>b.getBoundingClientRect().right+10){" +
            "    setTimeout(function(){ if(sidebarOpen()){ var bb=findToggle(); if(bb) bb.click(); } },50);" +
            "    return;" +
            "   }" +
            "  }" +
            "  if(popsBefore===0) return;" +
            "  var pops=visiblePopouts();" +
            "  if(pops.length===0) return;" +
            "  var inPop=false;" +
            "  for(var i=0;i<pops.length;i++){ if(pops[i]!==t&&pops[i].contains(t)) inPop=true; }" +
            "  if(inPop) return;" +
            "  try{ document.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,clientX:x,clientY:y})); }catch(err){}" +
            "  try{ document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',keyCode:27,bubbles:true})); }catch(err){}" +
            " }catch(err){} },true);" +
            // Minimal, surgical dialog fix: ONLY stack squeezed two-column rows
            // inside *_panel dialogs. Nothing else is touched.
            " function fixDialogs(){" +
            "  try{" +
            "   if(window.innerWidth>=760) return;" +
            "   var stackRow=function(el){" +
            "    try{" +
            "     var cs=getComputedStyle(el);" +
            "     if(cs.display==='flex'&&cs.flexDirection.indexOf('row')===0&&el.children.length>=2){" +
            "      var c0=el.children[0].getBoundingClientRect();" +
            "      if(c0.width>100&&c0.height>150){" +
            "       el.style.flexDirection='column';" +
            "       el.style.maxHeight='92vh'; el.style.overflowY='auto';" +
            "       for(var k=0;k<el.children.length;k++){ el.children[k].style.width='100%'; el.children[k].style.minWidth='0'; el.children[k].style.maxWidth='100%'; el.children[k].style.height='auto'; el.children[k].style.flex='0 0 auto'; }" +
            "       return true;" +
            "      }" +
            "     }" +
            "    }catch(err){}" +
            "    return false; };" +
            "   var els=document.querySelectorAll('[class*=\"_panel\"],[class*=\"_root\"],[role=\"dialog\"]');" +
            "   for(var i=0;i<els.length;i++){" +
            "    var p=els[i];" +
            "    if(p.querySelector&&p.querySelector('nav[aria-label=\"全局面板\"]')) continue;" +
            "    stackRow(p);" +
            "    var rows=p.getElementsByTagName('*');" +
            "    for(var j=0;j<rows.length;j++){ stackRow(rows[j]); }" +
            "   }" +
            "  }catch(e){}" +
            " }" +
            " try{ new MutationObserver(function(){ if(window.__fdT) return; window.__fdT=setTimeout(function(){ window.__fdT=null; fixDialogs(); },600); }).observe(document.body,{childList:true,subtree:true}); }catch(e){}" +
            " setTimeout(fixDialogs,1500);" +
            " try{ new MutationObserver(function(){ if(window.__rcT) return; window.__rcT=setTimeout(function(){ window.__rcT=null; try{ if(document.body&&/(连接异常|连接已断开|请重试|重新连接|网络错误)/.test(document.body.innerText||'')){ var now=Date.now(); if(now-(window.__lastRecover||0)>20000){ window.__lastRecover=now; var bs=document.querySelectorAll('button,[role=\"button\"]'); var hit=false; for(var i=0;i<bs.length;i++){ var t=(bs[i].textContent||''); if(/重试|重连|重新连接|刷新/.test(t)){ bs[i].click(); hit=true; break; } } if(!hit){ location.reload(); } } } }catch(e){} },800); }).observe(document.body,{childList:true,subtree:true,characterData:true}); }catch(e){}" +
            // connection state beacon for the native shell (visible status dot)
            " try{ setInterval(function(){ try{ var st='ok'; var txt=(document.body&&document.body.innerText)||'';" +
            "  if(/连接异常|已断开|连接失败|网络错误|无法连接/.test(txt)) st='bad';" +
            "  else if(/重连中|连接中|正在加载|加载中|重试/.test(txt)) st='weak';" +
            "  window.__connState=st; }catch(e){} },4000); }catch(e){}" +
            " function isEditable(t){ return t&&(t.tagName==='INPUT'||t.tagName==='TEXTAREA'||t.isContentEditable); }" +
            " function markTouch(e){" +
            "  var t=e.target;" +
            "  while(t){ if(isEditable(t)){ window.__lastInputTouch=Date.now(); return; } t=t.parentElement; }" +
            " }" +
            " document.addEventListener('touchstart',markTouch,true);" +
            " document.addEventListener('mousedown',markTouch,true);" +
            " var origFocus=HTMLElement.prototype.focus;" +
            " HTMLElement.prototype.focus=function(){" +
            "  if(isEditable(this) && Date.now()-(window.__lastInputTouch||0)>500) return;" +
            "  return origFocus.apply(this,arguments);" +
            " };" +
            " document.addEventListener('focusin',function(e){" +
            "  if(isEditable(e.target) && Date.now()-(window.__lastInputTouch||0)>500){ try{e.target.blur();}catch(x){} }" +
            " },true);" +
            (autoSidebar ?
            " try{" +
            "  if(window.innerWidth<760 && !sessionStorage.__dshSB){" +
            "   sessionStorage.__dshSB='1';" +
            "   setTimeout(function(){var b=findToggle(); if(b){var t=(b.getAttribute('aria-label')||'')+(b.textContent||'');" +
            "    if(t.indexOf('收起')>=0) b.click();}},600);" +
            "  } }catch(e){}" : "") +
            "})();";
        web.evaluateJavascript(js, null);
    }

    // ------------------------------------------------------- connection

    private String orientationMode() {
        return Prefs.get(this).getString(Prefs.ORIENTATION, "smart");
    }

    private void applyOrientationFor(boolean wideDialog) {
        String mode = orientationMode();
        if ("system".equals(mode)) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } else if ("landscape".equals(mode)) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            // portrait is the default way to use this on a phone (incl. "smart")
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    private boolean wideDialog = false;

    private void poll() {
        if (mainRoot == null) return;
        // safety: never trap the user behind the boot curtain for good
        if (bootView != null && bootStartMs > 0
            && System.currentTimeMillis() - bootStartMs > 30000) {
            bootRevealed = true;
            removeBoot();
        }
        // ZCode go mode: direct https, no tunnel logic at all. The ZCode page
        // owns its live session — poll() must NEVER reload it: its SPA navigates
        // with pushState, and any URL-based reload re-enters the whole session.
        // Only the very first load and failed-load recovery touch the WebView.
        if ("zcode".equals(appMode)) {
            String zu = Prefs.get(this).getString(Prefs.ZCODE_URL, "");
            if (zu == null || zu.isEmpty()) return;
            if (!zcodeLoadStarted) {
                zcodeLoadStarted = true;
                currentUrl = zu; // cold start: currentUrl still holds the DSH url
                pageLoading = true;
                pageLoadFailed = false;
                web.loadUrl(zu);
            } else if (pageLoadFailed && !pageLoading) {
                pageLoading = true;
                pageLoadFailed = false;
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!"zcode".equals(appMode) || web == null) return;
                        web.loadUrl(currentUrl);
                    }
                }, 3000);
            }
            return;
        }
        final Tunnel tunnel = Tunnel.get(this);

        // login page open: wait until the engine reports a logged-in profile
        if (loginLayer.getVisibility() == View.VISIBLE) {
            if (tunnel.isLoggedIn()) {
                hideLogin();
                statusTitle.setText("登录成功");
                statusSub.setText("正在建立加密隧道…");
                // drop the pre-login page state so the workbench loads fresh
                pageEverLoaded = false;
                pageLoading = false;
            } else {
                String url = tunnel.browseURL();
                if (url == null || url.isEmpty()) url = tunnel.authURL();
                if (url != null && !url.isEmpty() && loginWeb.getUrl() == null) {
                    loginWeb.loadUrl(url);
                }
                return;
            }
        }

        if (!tunnel.isEngineUp()) {
            tunnel.ensureEngine();
            statusTitle.setText("正在初始化");
            statusSub.setText("启动内置隧道内核…");
            setDot(0xFFF5C542);
            return;
        }

        if (tunnel.needsLogin()) {
            setState(ST_NEED_LOGIN);
            return;
        }

        if (!tunnel.isLoggedIn()) {
            // status not confirmed yet (weak network / engine still warming up):
            // this is "connecting", never "please log in" — mislabelling stalls
            // as 待登录 was the single worst confusion on campus networks.
            state = ST_TUNNEL;
            statusTitle.setText("正在连接远程桌面");
            statusSub.setText("正在确认隧道状态，网络较慢时会自动重试…");
            subtitleView.setText("连接中…");
            overlay.setVisibility(View.VISIBLE);
            actionBtn.setVisibility(View.GONE);
            setDot(0xFFF5C542);
            return;
        }

        if (VpnService.prepare(this) != null) {
            setState(ST_NEED_VPN);
            return;
        }

        // The engine can reach "Running" over the physical network long before
        // the tunnel device exists (that's exactly how DNS ended up broken).
        // So the VPN service is always started once authorized, and readiness
        // is judged by the system's VPN network, not by the engine state.
        if (!serviceStartRequested) startTunnelService();

        if (!vpnNetworkUp()) {
            if (System.currentTimeMillis() - vpnRequestAt > 12000) {
                serviceStartRequested = false; // self-heal: service died, re-request
            }
            state = ST_TUNNEL;
            statusTitle.setText("正在连接远程桌面");
            statusSub.setText("加密隧道建立中…");
            subtitleView.setText("隧道连接中");
            overlay.setVisibility(View.VISIBLE);
            actionBtn.setVisibility(View.GONE);
            setDot(0xFFF5C542);
            return;
        }

        if (!tunnel.isRunning()) {
            state = ST_TUNNEL;
            statusTitle.setText("正在连接远程桌面");
            statusSub.setText("加密隧道建立中…");
            subtitleView.setText("隧道连接中");
            overlay.setVisibility(View.VISIBLE);
            actionBtn.setVisibility(View.GONE);
            setDot(0xFFF5C542);
            return;
        }

        // tunnel is up: local services (inspector/file channel) get a loopback
        // forwarder so the page's ws://127.0.0.1:9230 works unchanged
        LocalForwarder.start(Prefs.hostOf(Prefs.effectiveUrl(this)));

        // tunnel is up: make sure we know the workbench endpoint
        if (Prefs.get(this, Prefs.AUTO_DISCOVER, true)
            && Prefs.get(this).getString(Prefs.DISCOVERED_URL, "").isEmpty()
            && Prefs.get(this).getString(Prefs.TARGET_MODE, "auto").equals("auto")) {
            if (!discoveryRunning && System.currentTimeMillis() - lastDiscoveryFail > 8000) {
                discoveryRunning = true;
                state = ST_DISCOVER;
                statusTitle.setText("正在连接远程桌面");
                statusSub.setText("已接入隧道，正在自动发现远程工作台…");
                subtitleView.setText("自动发现中");
                overlay.setVisibility(View.VISIBLE);
                actionBtn.setVisibility(View.GONE);
                setDot(0xFFF5C542);
                Discovery.find(tunnel, new Discovery.Callback() {
                    @Override public void onFound(String url) {
                        discoveryRunning = false;
                        Prefs.get(MainActivity.this).edit().putString(Prefs.DISCOVERED_URL, url).apply();
                        currentUrl = url;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                statusSub.setText("已发现远程工作台，正在载入…");
                            }
                        });
                    }

                    @Override public void onFailed(final String reason) {
                        discoveryRunning = false;
                        lastDiscoveryFail = System.currentTimeMillis();
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                statusSub.setText(reason + "，自动重试中…");
                            }
                        });
                    }
                });
            }
            return;
        }

        currentUrl = Prefs.effectiveUrl(this);
        if (pageEverLoaded && state == ST_READY) {
            // live connection quality: read the page's own state beacon
            web.evaluateJavascript("window.__connState||'ok'", new android.webkit.ValueCallback<String>() {
                @Override public void onReceiveValue(String v) {
                    if (subtitleView == null) return;
                    String s = String.valueOf(v).replace("\"", "").trim();
                    if ("bad".equals(s)) {
                        subtitleView.setText("连接异常 · 自动重连中");
                        setDot(0xFFFF5C5C);
                    } else if ("weak".equals(s)) {
                        subtitleView.setText("网络波动中");
                        setDot(0xFFF5C542);
                    } else {
                        subtitleView.setText("已连接");
                        setDot(0xFF3DDC84);
                    }
                }
            });
            // orientation intelligence: rotate when desktop-designed UI appears
            if ("smart".equals(orientationMode())) {
                web.evaluateJavascript("window.__dshDesktopWide?1:0", new android.webkit.ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        boolean wide = "1".equals(String.valueOf(v).trim());
                        if (wide != wideDialog) {
                            wideDialog = wide;
                            applyOrientationFor(wide);
                        }
                    }
                });
            }
            return;
        }
        if (pageLoading) {
            // watchdog: a load stuck too long gets restarted (slow relays, drops),
            // but never while the user is actively typing
            if (System.currentTimeMillis() - loadStart > 45000) {
                pageLoading = false;
                statusSub.setText("响应较慢，正在重连…");
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (!pageEverLoaded && !pageLoading
                            && System.currentTimeMillis() - (web != null ? 0 : 0) >= 0) {
                            pageLoading = true;
                            loadStart = System.currentTimeMillis();
                            web.reload();
                        }
                    }
                });
            }
            return;
        }

        // Tunnel + endpoint are known: hand straight to the WebView, whose own
        // error handling covers slow links and temporary unreachability.
        pageLoading = true;
        pageLoadFailed = false;
        loadStart = System.currentTimeMillis();
        statusTitle.setText("正在连接远程桌面");
        statusSub.setText("正在载入 " + Prefs.hostOf(currentUrl) + " …");
        subtitleView.setText("连接中…");
        overlay.setVisibility(View.VISIBLE);
        actionBtn.setVisibility(View.GONE);
        setDot(0xFFF5C542);
        web.loadUrl(currentUrl);
    }

    private boolean probe() {
        // kept for diagnostics; the load path no longer depends on it
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(currentUrl).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestMethod("GET");
            c.getResponseCode();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private boolean bootRevealed = false;
    private long bootStartMs;

    /** Boot curtain: let the full choreography play, then reveal the action UI. */
    private void revealBootAfterAnim() {
        if (bootRevealed) return;
        bootRevealed = true;
        handler.postDelayed(new Runnable() {
            @Override public void run() { removeBoot(); }
        }, 6200);
    }

    private void setState(int st) {
        state = st;
        if (bootView != null) {
            bootView.setStatus(st == ST_NEED_LOGIN ? "请登录 Tailscale 账号"
                : st == ST_NEED_VPN ? "正在开启加密隧道…"
                : st == ST_TUNNEL ? "正在建立加密隧道…"
                : st == ST_DISCOVER ? "正在发现远程工作台…"
                : st == ST_READY ? "已连接"
                : st == ST_ERROR ? "连接中断，自动重连中…"
                : "正在载入 DSH…");
        }
        if (st == ST_READY) {
            bootRevealed = true;
            removeBoot();
            overlay.setVisibility(View.GONE);
            setDot(0xFF3DDC84);
            subtitleView.setText("已连接");
        } else if (st == ST_NEED_LOGIN) {
            revealBootAfterAnim();
            overlay.setVisibility(View.VISIBLE);
            statusTitle.setText("欢迎使用 DSHgo");
            statusSub.setText("首次使用请登录 Tailscale 账号（仅此一次，之后自动连接）");
            subtitleView.setText("待登录");
            actionBtn.setVisibility(View.VISIBLE);
            actionBtn.setText("登录账号");
            setDot(0xFFF5C542);
        } else if (st == ST_NEED_VPN) {
            revealBootAfterAnim();
            overlay.setVisibility(View.VISIBLE);
            statusTitle.setText("开启加密隧道");
            statusSub.setText("需要授权 VPN 权限（系统会弹一次确认，之后全自动）");
            subtitleView.setText("待授权");
            actionBtn.setVisibility(View.VISIBLE);
            actionBtn.setText("开启隧道");
            setDot(0xFFF5C542);
        } else if (st == ST_ERROR) {
            revealBootAfterAnim();
            overlay.setVisibility(View.VISIBLE);
            statusTitle.setText("连接中断");
            statusSub.setText(Prefs.get(this, Prefs.AUTO_RECONNECT, true)
                ? "将自动重连，也可以点下方按钮立即重连"
                : "点下方按钮重新连接");
            subtitleView.setText("已断开");
            actionBtn.setVisibility(View.VISIBLE);
            actionBtn.setText("立即重连");
            setDot(0xFFFF5C5C);
            if (Prefs.get(this, Prefs.AUTO_RECONNECT, true)) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (state == ST_ERROR) {
                            pageEverLoaded = false;
                            poll();
                        }
                    }
                }, 5000);
            }
        }
    }

    private void setDot(int color) {
        ((GradientDrawable) dot.getBackground()).setColor(color);
    }

    private void openExternally(String uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------- network change recovery (stability)

    private android.net.ConnectivityManager.NetworkCallback netCb;

    private void registerNetworkWatch() {
        try {
            android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCb = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network n) {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (mainRoot == null) return;
                            if (!pageEverLoaded) {
                                pageLoading = false; // allow an immediate reconnect
                                poll();
                            } else if (subtitleView != null && !"已连接".equals(subtitleView.getText())) {
                                subtitleView.setText("已连接"); // restore after a network switch
                            }
                        }
                    });
                }

                @Override public void onLost(android.net.Network n) {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (mainRoot != null && pageEverLoaded) {
                                subtitleView.setText("网络切换中…");
                            }
                        }
                    });
                }
            };
            cm.registerNetworkCallback(
                new android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(), netCb);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------- microphone (voice input)

    private static final int REQ_MIC = 1002;

    private boolean hasMicPermission() {
        return Build.VERSION.SDK_INT < 23
            || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void askMicPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !hasMicPermission()) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            // voice input is a core feature: reflect the grant in the status line
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (statusSub != null) statusSub.setText("麦克风已就绪，语音输入可用");
            }
        }
    }

    private Bitmap circle(Bitmap src) {
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = Math.min(src.getWidth(), src.getHeight()) / 2f;
        canvas.drawCircle(src.getWidth() / 2f, src.getHeight() / 2f, r, paint);
        return out;
    }

    // ----------------------------------------------------------- events

    @Override
    protected void onResume() {
        super.onResume();
        String t = Prefs.get(this).getString(Prefs.THEME, "midnight");
        if (appliedTheme != null && !appliedTheme.equals(t)) {
            recreate(); // theme changed in settings: rebuild every surface
            return;
        }
        if (web != null) {
            web.onResume();
            applyWebPrefs(web);
        }
        applyWindowPrefs();
        applySiteSwitch();
        String url = Prefs.effectiveUrl(this);
        if (mainRoot != null && "dsh".equals(appMode) && !url.equals(currentUrl) && pageEverLoaded) {
            currentUrl = url;
            pageLoadFailed = false;
            web.loadUrl(currentUrl);
        }
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (web != null) web.destroy();
        if (loginWeb != null) loginWeb.destroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (loginLayer != null && loginLayer.getVisibility() == View.VISIBLE) {
                if (loginWeb.canGoBack()) {
                    loginWeb.goBack();
                } else {
                    hideLogin();
                }
                return true;
            }
            if (web != null && pageEverLoaded) {
                // Android drawer pattern: the first back closes the sidebar
                web.evaluateJavascript("(window.__dshBackAction&&window.__dshBackAction())||0",
                    new android.webkit.ValueCallback<String>() {
                        @Override public void onReceiveValue(String v) {
                            if (!"1".equals(String.valueOf(v).trim())) {
                                handleBackFallback();
                            }
                        }
                    });
                return true;
            }
            handleBackFallback();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void handleBackFallback() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (web != null && web.canGoBack()) {
                    web.goBack();
                } else if (Prefs.get(MainActivity.this, Prefs.BACK_SUSPEND, true)) {
                    moveTaskToBack(true);
                } else {
                    finish();
                }
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
