package com.newslive.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.Manifest;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.AudioAttributes;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PREF_NAME = "newslive_config";
    private static final String KEY_REMOTE_URL = "remote_config_url";
    private static final String KEY_AUTO_UPDATE = "auto_update_config";
    private static final String KEY_BUFFER_MIN = "buffer_min";
    private static final String KEY_BUFFER_MAX = "buffer_max";
    private static final String KEY_USE_WEB_MODE = "use_web_mode";
    private static final String KEY_WEB_SOURCE_URL = "web_source_url";
    private static final String KEY_WEB_SITES = "web_sites";
    private static final String KEY_CURRENT_SITE_INDEX = "current_site_index";
    private static final String KEY_LOCK_ORIENTATION = "lock_orientation";
    private static final String KEY_PLAYER_MODE_ENABLED = "player_mode_enabled";
    private static final String KEY_PLAYER_VIDEO_URLS = "player_video_urls";
    private static final int HTTP_PORT = 8765;
    
    private static final String DEFAULT_REMOTE_URL = "https://gitee.com/xujingrong/tv-live-config/raw/master/tv-live-source.json";
    private static final String DEFAULT_WEB_SOURCE_URL = "https://m-live.cctvnews.cctv.com/live/landscape.html?liveRoomNumber=16265686808730585228";
    
    private static final String[][] DEFAULT_WEB_SITES = {
        {"央视新闻直播", "https://m-live.cctvnews.cctv.com/live/landscape.html?liveRoomNumber=16265686808730585228"},
        {"央视直播大全", "https://tv.cctv.com/live/index.shtml"},
        {"央视频", "https://m.yangshipin.cn"},
        {"B站", "https://www.bilibili.com"},
        {"优酷", "https://www.youku.com"},
        {"爱奇艺", "https://www.iqiyi.com"},
        {"腾讯视频", "https://v.qq.com"},
        {"抖音", "https://www.douyin.com"},
        {"快手", "https://www.kuaishou.com"},
        {"西瓜视频", "https://www.ixigua.com"},
        {"芒果TV", "https://www.mgtv.com"},
        {"搜狐视频", "https://tv.sohu.com"},
        {"斗鱼直播", "https://www.douyu.com"},
        {"虎牙直播", "https://www.huya.com"},
        {"1905电影网", "https://www.1905.com"},
        {"哔哩哔哩番剧", "https://www.bilibili.com/anime"}
    };
    
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    private static final int WEBVIEW_LOAD_TIMEOUT = 25000;
    private static final int WEBVIEW_MAX_RETRY = 5;
    private static final int WEBVIEW_RETRY_DELAY = 2000;
    
    private List<String> streamUrls = new ArrayList<>();
    private List<String> streamNames = new ArrayList<>();
    private int currentUrlIndex = 0;
    private int bufferMinMs = 10000;
    private int bufferMaxMs = 60000;
    
    private ExoPlayer player;
    private PlayerView playerView;
    private FrameLayout playerContainer;
    private WebView webView;
    private ProgressBar progressBar;
    private ImageButton btnNextSource;
    private ImageButton btnPrevSource;
    private ImageButton btnNextMode;
    private ImageButton btnPrevMode;
    private ImageButton btnSwitchMode;
    private ImageButton btnLockOrientation;
    private ImageButton btnOrientation;
    private TextView tvSourceInfo;
    private TextView tvConfigInfo;
    private TextView tvNetworkInfo;
    private TextView tvHintInfo;
    private LinearLayout controlPanel;
    private Handler handler;
    private SimpleHttpServer httpServer;
    private SharedPreferences prefs;
    private String remoteConfigUrl = "";
    private boolean autoUpdateConfig = false;
    private boolean isPlaying = false;
    private boolean isControlVisible = true;
    private Runnable hideControlRunnable;

    // 右上角信息面板
    private TextView tvLunar;
    private TextView tvJieqi;
    private TextView tvSolarDate;
    private TextView tvTime;
    private TextView tvLocation;
    private TextView tvW0Label, tvW0Icon, tvW0Temp;
    private TextView tvW1Label, tvW1Icon, tvW1Temp;
    private TextView tvW2Label, tvW2Icon, tvW2Temp;
    private Handler clockHandler;
    private Runnable clockRunnable;
    private double lastLatitude = 0;
    private double lastLongitude = 0;
    private int lastComputedDay = -1;
    private int errorRetryCount = 0;
    private static final int MAX_RETRY_COUNT = 3;

    private boolean useWebMode = true;
    private boolean isStreamListEnabled = true;
    private String webSourceUrl = DEFAULT_WEB_SOURCE_URL;
    private String currentVideoUrl = "";
    private String currentVideoName = "";

    private List<String> playerVideoUrls = new ArrayList<>();
    private List<String> playerVideoNames = new ArrayList<>();
    
    private List<String> webSiteUrls = new ArrayList<>();
    private List<String> webSiteNames = new ArrayList<>();
    private int currentSiteIndex = 0;
    private boolean isOrientationLocked = false;
    
    private ConnectivityManager.NetworkCallback networkCallback;
    private ConnectivityManager connectivityManager;
    private boolean isNetworkAvailable = true;
    
    private ExecutorService executorService;
    
    private int webViewRetryCount = 0;
    private Runnable webViewTimeoutRunnable;
    private boolean isWebViewLoading = false;
    private long webViewLoadStartTime = 0;
    
    private int currentVideoWidth = 0;
    private int currentVideoHeight = 0;
    private boolean isPortraitVideo = false;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    
    private OrientationEventListener orientationEventListener;
    private int lastDeviceOrientation = Configuration.ORIENTATION_PORTRAIT;
    private boolean isAutoFullscreenEnabled = true;
    private String lastDetectedVideoUrl = "";
    private boolean isManualOrientationChange = false;
    private long lastManualOrientationTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        setContentView(R.layout.activity_main);

        handler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        executorService = Executors.newFixedThreadPool(4);
        
        playerView = findViewById(R.id.player_view);
        playerContainer = findViewById(R.id.player_container);
        webView = findViewById(R.id.web_view);
        progressBar = findViewById(R.id.progress_bar);
        btnNextSource = findViewById(R.id.btn_next_source);
        btnPrevSource = findViewById(R.id.btn_prev_source);
        btnNextMode = findViewById(R.id.btn_next_mode);
        btnPrevMode = findViewById(R.id.btn_prev_mode);
        btnSwitchMode = findViewById(R.id.btn_switch_mode);
        btnLockOrientation = findViewById(R.id.btn_lock_orientation);
        btnOrientation = findViewById(R.id.btn_orientation);
        tvSourceInfo = findViewById(R.id.tv_source_info);
        tvConfigInfo = findViewById(R.id.tv_config_info);
        tvNetworkInfo = findViewById(R.id.tv_network_info);
        tvHintInfo = findViewById(R.id.tv_hint_info);
        controlPanel = findViewById(R.id.control_panel);

        // 初始化右上角信息面板
        tvLunar = findViewById(R.id.tv_lunar);
        tvJieqi = findViewById(R.id.tv_jieqi);
        tvSolarDate = findViewById(R.id.tv_solar_date);
        tvTime = findViewById(R.id.tv_time);
        tvLocation = findViewById(R.id.tv_location);
        tvW0Label = findViewById(R.id.tv_w0_label);
        tvW0Icon = findViewById(R.id.tv_w0_icon);
        tvW0Temp = findViewById(R.id.tv_w0_temp);
        tvW1Label = findViewById(R.id.tv_w1_label);
        tvW1Icon = findViewById(R.id.tv_w1_icon);
        tvW1Temp = findViewById(R.id.tv_w1_temp);
        tvW2Label = findViewById(R.id.tv_w2_label);
        tvW2Icon = findViewById(R.id.tv_w2_icon);
        tvW2Temp = findViewById(R.id.tv_w2_temp);
        startClock();
        requestLocationAndWeather();

        btnNextSource.setOnClickListener(v -> {
            if (useWebMode) {
                switchToNextWebSite();
            } else {
                switchToNextSource();
            }
        });
        btnPrevSource.setOnClickListener(v -> {
            if (useWebMode) {
                switchToPrevWebSite();
            } else {
                switchToPrevSource();
            }
        });
        btnNextMode.setOnClickListener(v -> switchMode());
        btnPrevMode.setOnClickListener(v -> switchMode());
        btnSwitchMode.setOnClickListener(v -> switchMode());
        btnLockOrientation.setOnClickListener(v -> toggleOrientationLock());
        btnOrientation.setOnClickListener(v -> toggleScreenOrientation());
        
        updateLockButtonIcon();
        
        playerView.setOnClickListener(v -> toggleControlPanel());
        webView.setOnClickListener(v -> toggleControlPanel());
        
        initNetworkMonitor();
        initOrientationListener();
        loadSavedConfig();
        startHttpServer();
        updatePlayerModeButtons();

        // 默认进入网页模式
        useWebMode = true;
        prefs.edit().putBoolean(KEY_USE_WEB_MODE, true).apply();

        if (useWebMode) {
            initWebView();
            loadWebSource();
        } else {
            initPlayer();
            loadStreamFromConfig(currentUrlIndex);
        }
        
        if (autoUpdateConfig && !remoteConfigUrl.isEmpty()) {
            fetchRemoteConfig();
        }
    }
    
    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }

    private void initNetworkMonitor() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        
        NetworkRequest networkRequest = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();
        
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                isNetworkAvailable = true;
                runOnUiThread(() -> {
                    updateNetworkInfo();
                    onNetworkRestored();
                });
            }
            
            @Override
            public void onLost(Network network) {
                isNetworkAvailable = false;
                runOnUiThread(() -> {
                    updateNetworkInfo();
                    onNetworkLost();
                });
            }
            
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                runOnUiThread(() -> updateNetworkInfo());
            }
        };
        
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
        
        isNetworkAvailable = isNetworkConnected();
        updateNetworkInfo();
    }
    
    private boolean isNetworkConnected() {
        if (connectivityManager == null) return false;
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        // 只要具备INTERNET能力即认为已联网（不强制要求VALIDATED，
        // 因为电视开机后网络验证可能延迟完成，导致误判为无网络而无法播放）
        return capabilities != null &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    
    private void updateNetworkInfo() {
        String networkType = "无网络";
        int color = 0xFFE53935;
        
        if (isNetworkAvailable && connectivityManager != null) {
            Network network = connectivityManager.getActiveNetwork();
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            if (capabilities != null) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    networkType = "WiFi";
                    color = 0xFF43A047;
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    networkType = "移动网络";
                    color = 0xFFFFA726;
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    networkType = "有线网络";
                    color = 0xFF43A047;
                }
            }
        }
        
        if (tvNetworkInfo != null) {
            tvNetworkInfo.setText("网络: " + networkType);
            tvNetworkInfo.setTextColor(color);
        }
    }
    
    private void onNetworkLost() {
        Toast.makeText(this, "网络已断开", Toast.LENGTH_SHORT).show();
        
        if (player != null && player.isPlaying()) {
            player.setPlayWhenReady(false);
        }
        
        if (webView != null) {
            webView.pauseTimers();
            webView.onPause();
        }
    }
    
    private void onNetworkRestored() {
        Toast.makeText(this, "网络已恢复", Toast.LENGTH_SHORT).show();
        
        if (useWebMode) {
            if (webView != null) {
                webView.resumeTimers();
                webView.onResume();
            }
            if (currentVideoUrl.isEmpty()) {
                loadWebSource();
            }
        } else {
            if (player != null) {
                player.setPlayWhenReady(true);
            }
        }
    }
    
    private void initOrientationListener() {
        orientationEventListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return;
                
                int newOrientation;
                if (orientation >= 60 && orientation <= 300) {
                    newOrientation = Configuration.ORIENTATION_LANDSCAPE;
                } else {
                    newOrientation = Configuration.ORIENTATION_PORTRAIT;
                }
                
                if (newOrientation != lastDeviceOrientation) {
                    lastDeviceOrientation = newOrientation;
                    onDeviceOrientationChanged(newOrientation);
                }
            }
        };
        
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }
    
    private void onDeviceOrientationChanged(int orientation) {
        if (isOrientationLocked) return;
        
        if (isManualOrientationChange) {
            long elapsed = System.currentTimeMillis() - lastManualOrientationTime;
            if (elapsed < 3000) {
                return;
            }
            isManualOrientationChange = false;
        }
        
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            if (useWebMode && isAutoFullscreenEnabled) {
                if (!lastDetectedVideoUrl.isEmpty()) {
                    switchToPlayerMode(lastDetectedVideoUrl);
                } else {
                    tryExtractAndPlayVideo();
                }
            }
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            if (!useWebMode) {
                switchToWebMode();
            }
        }
    }
    
    private void switchToWebMode() {
        if (useWebMode) return;
        
        useWebMode = true;
        prefs.edit().putBoolean(KEY_USE_WEB_MODE, true).apply();
        
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        
        runOnUiThread(() -> {
            webView.setVisibility(View.VISIBLE);
            playerContainer.setVisibility(View.GONE);
            
            if (webView.getUrl() == null || webView.getUrl().isEmpty() || webView.getUrl().equals("about:blank")) {
                loadWebSource();
            } else {
                webView.onResume();
                webView.resumeTimers();
            }
            
            updateSourceInfo();
            Toast.makeText(this, "切换到网页模式", Toast.LENGTH_SHORT).show();
        });
    }
    
    private void tryExtractAndPlayVideo() {
        String js = "(function() {" +
            "try {" +
            "  var video = document.querySelector('video');" +
            "  if (video) {" +
            "    var src = video.src || video.currentSrc || '';" +
            "    if (src && src.indexOf('blob:') === -1) {" +
            "      return JSON.stringify({success: true, url: src, isPlaying: !video.paused});" +
            "    }" +
            "    if (video.querySelector('source')) {" +
            "      src = video.querySelector('source').src || '';" +
            "      if (src) return JSON.stringify({success: true, url: src, isPlaying: !video.paused});" +
            "    }" +
            "  }" +
            "  var iframes = document.querySelectorAll('iframe');" +
            "  for (var i = 0; i < iframes.length; i++) {" +
            "    var iframe = iframes[i];" +
            "    if (iframe.contentDocument) {" +
            "      var v = iframe.contentDocument.querySelector('video');" +
            "      if (v && (v.src || v.currentSrc)) {" +
            "        return JSON.stringify({success: true, url: v.src || v.currentSrc, isPlaying: !v.paused});" +
            "      }" +
            "    }" +
            "  }" +
            "} catch(e) {}" +
            "return JSON.stringify({success: false});" +
            "})();";
        
        webView.evaluateJavascript(js, result -> {
            try {
                if (result == null || result.equals("null")) return;
                String jsonStr = result.replace("\\\"", "\"").replaceAll("^\"|\"$", "");
                JSONObject json = new JSONObject(jsonStr);
                
                if (json.optBoolean("success", false)) {
                    String videoUrl = json.optString("url", "");
                    boolean isPlaying = json.optBoolean("isPlaying", false);
                    
                    if (!videoUrl.isEmpty() && !videoUrl.startsWith("blob:")) {
                        lastDetectedVideoUrl = videoUrl;
                        if (isPlaying || lastDeviceOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                            switchToPlayerMode(videoUrl);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
    
    private void switchToPlayerMode(String videoUrl) {
        if (videoUrl == null || videoUrl.isEmpty()) return;
        
        runOnUiThread(() -> {
            webView.setVisibility(View.GONE);
            playerContainer.setVisibility(View.VISIBLE);
            
            if (player == null) {
                initPlayer();
            }
            
            String pageName = "网页视频";
            if (webView.getUrl() != null) {
                String host = webView.getUrl();
                if (host.contains("cctv")) pageName = "央视视频";
                else if (host.contains("bilibili")) pageName = "B站视频";
                else if (host.contains("youku")) pageName = "优酷视频";
                else if (host.contains("iqiyi")) pageName = "爱奇艺视频";
                else if (host.contains("douyin")) pageName = "抖音视频";
                else if (host.contains("qq.com")) pageName = "腾讯视频";
            }
            
            playVideoUrl(videoUrl, pageName);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        });
    }

    private void loadSavedConfig() {
        remoteConfigUrl = prefs.getString(KEY_REMOTE_URL, DEFAULT_REMOTE_URL);
        autoUpdateConfig = prefs.getBoolean(KEY_AUTO_UPDATE, true);
        bufferMinMs = prefs.getInt(KEY_BUFFER_MIN, 10000);
        bufferMaxMs = prefs.getInt(KEY_BUFFER_MAX, 60000);
        useWebMode = prefs.getBoolean(KEY_USE_WEB_MODE, true);
        isStreamListEnabled = prefs.getBoolean(KEY_PLAYER_MODE_ENABLED, true);
        webSourceUrl = prefs.getString(KEY_WEB_SOURCE_URL, DEFAULT_WEB_SOURCE_URL);
        isOrientationLocked = prefs.getBoolean(KEY_LOCK_ORIENTATION, false);
        currentSiteIndex = prefs.getInt(KEY_CURRENT_SITE_INDEX, 0);

        loadWebSites();
        loadPlayerVideoUrls();
        
        String savedConfig = prefs.getString("saved_sources", "");
        if (!savedConfig.isEmpty()) {
            try {
                JSONObject config = new JSONObject(savedConfig);
                parseConfig(config);
            } catch (Exception e) {
                loadDefaultConfig();
            }
        } else {
            loadDefaultConfig();
        }
    }
    
    private void loadWebSites() {
        webSiteUrls.clear();
        webSiteNames.clear();
        
        String savedSites = prefs.getString(KEY_WEB_SITES, "");
        if (!savedSites.isEmpty()) {
            try {
                JSONArray sites = new JSONArray(savedSites);
                for (int i = 0; i < sites.length(); i++) {
                    JSONObject site = sites.getJSONObject(i);
                    webSiteNames.add(site.optString("name", "网站" + (i + 1)));
                    webSiteUrls.add(site.optString("url", ""));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        if (webSiteUrls.isEmpty()) {
            for (String[] site : DEFAULT_WEB_SITES) {
                webSiteNames.add(site[0]);
                webSiteUrls.add(site[1]);
            }
        }
        
        if (currentSiteIndex < 0 || currentSiteIndex >= webSiteUrls.size()) {
            currentSiteIndex = 0;
        }
        
        if (!webSiteUrls.isEmpty()) {
            webSourceUrl = webSiteUrls.get(currentSiteIndex);
        }
    }

    private void loadPlayerVideoUrls() {
        playerVideoUrls.clear();
        playerVideoNames.clear();

        String savedUrls = prefs.getString(KEY_PLAYER_VIDEO_URLS, "");
        if (!savedUrls.isEmpty()) {
            try {
                JSONArray urls = new JSONArray(savedUrls);
                for (int i = 0; i < urls.length(); i++) {
                    JSONObject item = urls.getJSONObject(i);
                    playerVideoNames.add(item.optString("name", "视频" + (i + 1)));
                    playerVideoUrls.add(item.optString("url", ""));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 如果没有配置，使用默认直播源
        if (playerVideoUrls.isEmpty() && !streamUrls.isEmpty()) {
            for (int i = 0; i < streamUrls.size(); i++) {
                playerVideoNames.add(streamNames.get(i));
                playerVideoUrls.add(streamUrls.get(i));
            }
        }
    }

    private void savePlayerVideoUrls() {
        try {
            JSONArray urls = new JSONArray();
            for (int i = 0; i < playerVideoUrls.size(); i++) {
                JSONObject item = new JSONObject();
                item.put("name", playerVideoNames.get(i));
                item.put("url", playerVideoUrls.get(i));
                urls.put(item);
            }
            prefs.edit().putString(KEY_PLAYER_VIDEO_URLS, urls.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updatePlayerModeButtons() {
        // 模式切换按钮始终显示
        if (btnSwitchMode != null) btnSwitchMode.setVisibility(View.VISIBLE);
        if (btnNextMode != null) btnNextMode.setVisibility(View.VISIBLE);
        if (btnPrevMode != null) btnPrevMode.setVisibility(View.VISIBLE);
    }

    // ==================== 右上角时钟 ====================
    private void startClock() {
        clockHandler = new Handler(Looper.getMainLooper());
        clockRunnable = new Runnable() {
            @Override
            public void run() {
                updateClock();
                clockHandler.postDelayed(this, 1000);
            }
        };
        clockHandler.post(clockRunnable);
    }

    private void updateClock() {
        if (tvTime == null) return;
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int minute = cal.get(java.util.Calendar.MINUTE);
            int second = cal.get(java.util.Calendar.SECOND);
            tvTime.setText(String.format("%02d:%02d:%02d", hour, minute, second));

            // 日期/农历/节气仅在日期变化时更新
            int todayDay = cal.get(java.util.Calendar.DAY_OF_YEAR);
            if (todayDay != lastComputedDay) {
                lastComputedDay = todayDay;
                updateDateInfo(cal);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateDateInfo(java.util.Calendar cal) {
        int year = cal.get(java.util.Calendar.YEAR);
        int month = cal.get(java.util.Calendar.MONTH) + 1;
        int day = cal.get(java.util.Calendar.DAY_OF_MONTH);
        int weekday = cal.get(java.util.Calendar.DAY_OF_WEEK);
        String[] weekNames = {"日", "一", "二", "三", "四", "五", "六"};

        android.util.Log.i("NewsLive", "updateDateInfo: " + year + "-" + month + "-" + day + " weekday=" + weekday);

        if (tvSolarDate != null) {
            tvSolarDate.setText(String.format("%d年%02d月%02d日 周%s", year, month, day, weekNames[weekday - 1]));
        }

        if (tvLunar != null) {
            try {
                int[] lunar = LunarCalendar.solarToLunar(year, month, day);
                android.util.Log.i("NewsLive", "lunar result: year=" + lunar[0] + " month=" + lunar[1] + " day=" + lunar[2] + " isLeap=" + lunar[3]);
                String lunarStr = LunarCalendar.formatLunar(lunar[0], lunar[1], lunar[2], lunar[3] == 1);
                android.util.Log.i("NewsLive", "lunar string: " + lunarStr);
                tvLunar.setText(lunarStr);
            } catch (Exception e) {
                android.util.Log.e("NewsLive", "lunar calc error", e);
                tvLunar.setText("农历计算错误");
            }
        }

        if (tvJieqi != null) {
            try {
                String jieqi = LunarCalendar.getJieqiInfo(year, month, day);
                android.util.Log.i("NewsLive", "jieqi: " + jieqi);
                tvJieqi.setText(jieqi);
                updateJieqiStyle(jieqi.startsWith("今日"));
            } catch (Exception e) {
                android.util.Log.e("NewsLive", "jieqi calc error", e);
                tvJieqi.setText("节气计算错误");
            }
        }
    }

    private void updateJieqiStyle(boolean isToday) {
        if (tvJieqi == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(999f);
        if (isToday) {
            bg.setColor(Color.argb(64, 255, 213, 79));
            bg.setStroke(2, Color.argb(128, 255, 213, 79));
            tvJieqi.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.argb(51, 129, 212, 250));
            bg.setStroke(2, Color.argb(128, 129, 212, 250));
            tvJieqi.setTextColor(Color.parseColor("#81D4FA"));
        }
        tvJieqi.setBackground(bg);
    }

    // ==================== 定位与天气 ====================
    private void requestLocationAndWeather() {
        // 电视无GPS，直接使用IP定位
        tvLocation.setText("📍 定位中...");
        fetchLocationByIP();
    }

    // IP定位：通过公网IP获取位置（电视无GPS）
    // 主用太平洋电脑网API（HTTP，国内速度快，返回中文），备用 ip-api.com + 逆地理编码
    private void fetchLocationByIP() {
        executorService.execute(() -> {
            try {
                // 太平洋电脑网IP定位，HTTPS
                URL url = new URL("https://whois.pconline.com.cn/ipJson.jsp?json=true");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                // pconline返回GBK编码
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "GBK"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                conn.disconnect();

                String resp = response.toString();
                android.util.Log.i("NewsLive", "pconline response: " + resp);
                JSONObject json = new JSONObject(resp);

                // pconline返回字段: pro, city, region, addr（不含坐标）
                String pro = json.optString("pro", "");   // 省
                String city = json.optString("city", ""); // 市
                String region = json.optString("region", ""); // 区

                android.util.Log.i("NewsLive", "pconline pro=" + pro + " city=" + city + " region=" + region);

                // 组合显示：省 + 市 + 区
                String name = "";
                if (!pro.isEmpty()) name = pro;
                if (!city.isEmpty() && !city.equals(pro)) name = name + " " + city;
                if (!region.isEmpty() && !region.equals(city)) name = name + " " + region;
                if (name.isEmpty()) name = json.optString("addr", "未知");

                final String finalName = name;
                runOnUiThread(() -> tvLocation.setText("📍 " + finalName));

                // pconline不返回坐标，直接用ip-api获取坐标（快速），地理编码仅作ip-api失败时的备用
                android.util.Log.i("NewsLive", "pconline OK, fetching coords from ip-api");
                fetchLocationByIPBackup(name);
            } catch (Exception e) {
                android.util.Log.e("NewsLive", "pconline failed", e);
                fetchLocationByIPBackup("");
            }
        });
    }

    // 地理编码：城市名转坐标（Open-Meteo Geocoding API，国内可访问，无需Key）
    private void geocodeAndFetchWeather(String cityName, String fallbackDisplayName) {
        executorService.execute(() -> {
            try {
                String urlStr = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(cityName, "UTF-8") + "&count=1&language=zh&format=json";
                android.util.Log.i("NewsLive", "geocoding: " + cityName);
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                conn.disconnect();

                String resp = response.toString();
                android.util.Log.i("NewsLive", "geocoding response: " + resp);
                JSONObject json = new JSONObject(resp);
                JSONArray results = json.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject first = results.getJSONObject(0);
                    double lat = first.getDouble("latitude");
                    double lon = first.getDouble("longitude");
                    android.util.Log.i("NewsLive", "geocoded: " + cityName + " -> lat=" + lat + " lon=" + lon);
                    lastLatitude = lat;
                    lastLongitude = lon;
                    fetchWeather(lat, lon);
                } else {
                    android.util.Log.w("NewsLive", "geocoding no results for: " + cityName);
                    fetchLocationByIPBackup(fallbackDisplayName);
                }
            } catch (Exception e) {
                android.util.Log.e("NewsLive", "geocoding failed", e);
                fetchLocationByIPBackup(fallbackDisplayName);
            }
        });
    }

    // IP定位备用：ip-api.com获取坐标
    // preferredCityName: pconline已获取的中文城市名，优先使用；为空时用逆地理编码获取中文名
    private void fetchLocationByIPBackup(String preferredCityName) {
        executorService.execute(() -> {
            try {
                URL url = new URL("http://ip-api.com/json/?lang=zh-CN&fields=status,country,regionName,city,lat,lon");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                conn.disconnect();

                String resp = response.toString();
                android.util.Log.i("NewsLive", "ip-api response: " + resp);
                JSONObject json = new JSONObject(resp);

                double lat = json.optDouble("lat", 0);
                double lon = json.optDouble("lon", 0);
                String city = json.optString("city", "");
                String region = json.optString("regionName", "");

                android.util.Log.i("NewsLive", "ip-api city=" + city + " region=" + region + " lat=" + lat + " lon=" + lon);

                if (lat != 0 && lon != 0) {
                    lastLatitude = lat;
                    lastLongitude = lon;
                    // 先获取天气（不依赖定位名）
                    fetchWeather(lat, lon);

                    if (preferredCityName != null && !preferredCityName.isEmpty()) {
                        // 优先使用pconline的中文城市名
                        String finalName = preferredCityName;
                        runOnUiThread(() -> tvLocation.setText("📍 " + finalName));
                    } else {
                        // 无中文名，用坐标逆地理编码获取中文名
                        android.util.Log.i("NewsLive", "reverse geocoding for Chinese name...");
                        fetchCityName(lat, lon, region);
                    }
                } else {
                    runOnUiThread(() -> tvLocation.setText("📍 定位失败"));
                }
            } catch (Exception e) {
                android.util.Log.e("NewsLive", "ip-api failed", e);
                // ip-api失败时，若有pconline中文名则尝试地理编码获取坐标
                if (preferredCityName != null && !preferredCityName.isEmpty()) {
                    String geocodeName = preferredCityName;
                    String[] parts = preferredCityName.split(" ");
                    if (parts.length > 0) geocodeName = parts[parts.length - 1];
                    if (geocodeName.endsWith("市")) geocodeName = geocodeName.substring(0, geocodeName.length() - 1);
                    if (geocodeName.endsWith("区")) geocodeName = geocodeName.substring(0, geocodeName.length() - 1);
                    android.util.Log.i("NewsLive", "ip-api failed, trying geocoding: " + geocodeName);
                    geocodeAndFetchWeather(geocodeName, preferredCityName);
                } else {
                    runOnUiThread(() -> tvLocation.setText("📍 定位失败"));
                }
            }
        });
    }

    // 逆地理编码：坐标转城市名（Nominatim / OpenStreetMap，无需Key，中国数据准确）
    // fallbackName: 逆地理编码全部失败时使用的兜底名称（如ip-api返回的中文省份）
    private void fetchCityName(double lat, double lon, String fallbackName) {
        executorService.execute(() -> {
            try {
                String urlStr = String.format(
                    "https://nominatim.openstreetmap.org/reverse?lat=%.6f&lon=%.6f&format=json&accept-language=zh&zoom=10",
                    lat, lon);
                android.util.Log.i("NewsLive", "nominatim: " + urlStr);
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "NewsLiveApp/1.0");
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                conn.disconnect();

                String resp = response.toString();
                android.util.Log.i("NewsLive", "nominatim response: " + resp);
                JSONObject json = new JSONObject(resp);
                JSONObject address = json.getJSONObject("address");

                // 优先级：市 > 县/区 > 州/省
                String city = address.optString("city", "");
                if (city.isEmpty()) city = address.optString("city_district", "");
                if (city.isEmpty()) city = address.optString("town", "");
                if (city.isEmpty()) city = address.optString("county", "");
                if (city.isEmpty()) city = address.optString("district", "");
                String state = address.optString("state", "");

                String name = "";
                if (!city.isEmpty() && !state.isEmpty()) {
                    name = state + " " + city;
                } else if (!city.isEmpty()) {
                    name = city;
                } else if (!state.isEmpty()) {
                    name = state;
                }
                if (name.isEmpty()) name = fallbackName != null ? fallbackName : String.format("%.4f,%.4f", lat, lon);

                String finalName = name;
                runOnUiThread(() -> tvLocation.setText("📍 " + finalName));
            } catch (Exception e) {
                android.util.Log.e("NewsLive", "nominatim failed", e);
                // 备用：BigDataCloud
                try {
                    android.util.Log.i("NewsLive", "trying bigdatacloud reverse geocoding...");
                    String urlStr = String.format(
                        "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.6f&longitude=%.6f&localityLanguage=zh",
                        lat, lon);
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    conn.disconnect();

                    String resp = response.toString();
                    android.util.Log.i("NewsLive", "bigdatacloud response: " + resp);
                    JSONObject json = new JSONObject(resp);
                    String city = json.optString("city", "");
                    String locality = json.optString("locality", "");
                    String subdivision = json.optString("principalSubdivision", "");

                    String name = "";
                    if (!city.isEmpty()) name = city;
                    else if (!locality.isEmpty()) name = locality;
                    else if (!subdivision.isEmpty()) name = subdivision;
                    if (name.isEmpty()) name = fallbackName != null ? fallbackName : String.format("%.4f,%.4f", lat, lon);

                    String finalName = name;
                    runOnUiThread(() -> tvLocation.setText("📍 " + finalName));
                } catch (Exception e2) {
                    android.util.Log.e("NewsLive", "bigdatacloud failed", e2);
                    // 全部逆地理编码失败，使用ip-api的中文省份名作为兜底
                    String fb = fallbackName != null && !fallbackName.isEmpty() ? fallbackName : "定位失败";
                    runOnUiThread(() -> tvLocation.setText("📍 " + fb));
                }
            }
        });
    }

    // 使用Open-Meteo免费API获取天气预报（无需API Key）
    private void fetchWeather(double lat, double lon) {
        executorService.execute(() -> {
            try {
                String weatherUrl = String.format(
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&daily=weathercode,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=3",
                    lat, lon);
                android.util.Log.i("NewsLive", "fetchWeather: lat=" + lat + " lon=" + lon);
                URL url = new URL(weatherUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                conn.disconnect();

                String resp = response.toString();
                android.util.Log.i("NewsLive", "weather response: " + resp);
                JSONObject json = new JSONObject(resp);
                JSONObject daily = json.getJSONObject("daily");
                JSONArray codes = daily.getJSONArray("weathercode");
                JSONArray maxTemps = daily.getJSONArray("temperature_2m_max");
                JSONArray minTemps = daily.getJSONArray("temperature_2m_min");

                final String[] labels = {"今天", "明天", "后天"};
                final String[] icons = new String[3];
                final String[] temps = new String[3];

                for (int i = 0; i < 3; i++) {
                    icons[i] = weatherCodeToIcon(codes.getInt(i));
                    double maxT = maxTemps.getDouble(i);
                    double minT = minTemps.getDouble(i);
                    temps[i] = String.format("%.0f°/%.0f°", maxT, minT);
                }
                android.util.Log.i("NewsLive", "weather OK: " + temps[0] + " " + temps[1] + " " + temps[2]);

                runOnUiThread(() -> {
                    tvW0Label.setText(labels[0]);
                    tvW0Icon.setText(icons[0]);
                    tvW0Temp.setText(temps[0]);
                    tvW1Label.setText(labels[1]);
                    tvW1Icon.setText(icons[1]);
                    tvW1Temp.setText(temps[1]);
                    tvW2Label.setText(labels[2]);
                    tvW2Icon.setText(icons[2]);
                    tvW2Temp.setText(temps[2]);
                });
            } catch (Exception e) {
                android.util.Log.e("NewsLive", "fetchWeather failed", e);
                runOnUiThread(() -> {
                    tvW0Icon.setText("🌤");
                    tvW0Temp.setText("获取失败");
                    tvW1Icon.setText("");
                    tvW1Temp.setText("");
                    tvW2Icon.setText("");
                    tvW2Temp.setText("");
                });
            }
        });
    }

    private String weatherCodeToIcon(int code) {
        if (code == 0) return "☀️";
        if (code <= 3) return "⛅";
        if (code <= 48) return "🌫️";
        if (code <= 67) return "🌧️";
        if (code <= 77) return "🌨️";
        if (code <= 82) return "🌦️";
        if (code <= 86) return "🌨️";
        if (code <= 99) return "⛈️";
        return "🌤️";
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 电视无GPS，权限结果不影响IP定位
    }


    private void switchToNextWebSite() {
        if (webSiteUrls.isEmpty()) return;
        stopAllPlayback();
        lastDetectedVideoUrl = "";
        currentSiteIndex = (currentSiteIndex + 1) % webSiteUrls.size();
        webSourceUrl = webSiteUrls.get(currentSiteIndex);
        prefs.edit().putInt(KEY_CURRENT_SITE_INDEX, currentSiteIndex).apply();

        // 无论网页模式还是播放器模式，都切换到网页模式加载新网址
        if (!useWebMode) {
            useWebMode = true;
            prefs.edit().putBoolean(KEY_USE_WEB_MODE, true).apply();
            webView.setVisibility(View.VISIBLE);
            playerContainer.setVisibility(View.GONE);
            if (player != null) {
                player.stop();
                player.setPlayWhenReady(false);
            }
            webView.onResume();
            webView.resumeTimers();
        }
        loadWebSource();
        updateSourceInfo();
        Toast.makeText(this, "切换到: " + webSiteNames.get(currentSiteIndex), Toast.LENGTH_SHORT).show();
    }

    private void switchToPrevWebSite() {
        if (webSiteUrls.isEmpty()) return;
        stopAllPlayback();
        lastDetectedVideoUrl = "";
        currentSiteIndex = (currentSiteIndex - 1 + webSiteUrls.size()) % webSiteUrls.size();
        webSourceUrl = webSiteUrls.get(currentSiteIndex);
        prefs.edit().putInt(KEY_CURRENT_SITE_INDEX, currentSiteIndex).apply();

        if (!useWebMode) {
            useWebMode = true;
            prefs.edit().putBoolean(KEY_USE_WEB_MODE, true).apply();
            webView.setVisibility(View.VISIBLE);
            playerContainer.setVisibility(View.GONE);
            if (player != null) {
                player.stop();
                player.setPlayWhenReady(false);
            }
            webView.onResume();
            webView.resumeTimers();
        }
        loadWebSource();
        updateSourceInfo();
        Toast.makeText(this, "切换到: " + webSiteNames.get(currentSiteIndex), Toast.LENGTH_SHORT).show();
    }
    
    private void stopAllPlayback() {
        if (player != null) {
            try {
                player.stop();
                player.setPlayWhenReady(false);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        if (webView != null) {
            try {
                webView.pauseTimers();
                webView.onPause();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        lastDetectedVideoUrl = "";
    }
    
    private void stopWebViewVideo() {
        if (webView != null) {
            try {
                webView.pauseTimers();
                webView.onPause();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        lastDetectedVideoUrl = "";
    }
    
    private void toggleOrientationLock() {
        isOrientationLocked = !isOrientationLocked;
        prefs.edit().putBoolean(KEY_LOCK_ORIENTATION, isOrientationLocked).apply();
        
        updateLockButtonIcon();
        
        if (isOrientationLocked) {
            Toast.makeText(this, "已锁定屏幕方向", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "已解锁屏幕方向", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void toggleScreenOrientation() {
        int currentOrientation = getResources().getConfiguration().orientation;
        
        isManualOrientationChange = true;
        lastManualOrientationTime = System.currentTimeMillis();
        
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            lastDeviceOrientation = Configuration.ORIENTATION_PORTRAIT;
            Toast.makeText(this, "竖屏模式", Toast.LENGTH_SHORT).show();
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            lastDeviceOrientation = Configuration.ORIENTATION_LANDSCAPE;
            Toast.makeText(this, "横屏模式", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void updateLockButtonIcon() {
        if (btnLockOrientation != null) {
            if (isOrientationLocked) {
                btnLockOrientation.setImageResource(android.R.drawable.ic_lock_lock);
            } else {
                btnLockOrientation.setImageResource(android.R.drawable.ic_lock_idle_lock);
            }
        }
    }

    private void loadDefaultConfig() {
        streamUrls.add("http://120.76.248.139/live/bfgd/4200000067.m3u8");
        streamNames.add("CCTV13新闻");
        streamUrls.add("http://120.76.248.139/live/bfgd/4200000484.m3u8");
        streamNames.add("CCTV1综合");
        streamUrls.add("http://120.76.248.139/live/bfgd/4200000061.m3u8");
        streamNames.add("CCTV2经济");
    }

    private void parseConfig(JSONObject config) {
        try {
            streamUrls.clear();
            streamNames.clear();
            JSONArray sources = config.getJSONArray("sources");
            for (int i = 0; i < sources.length(); i++) {
                JSONObject source = sources.getJSONObject(i);
                streamUrls.add(source.getString("url"));
                streamNames.add(source.optString("name", "源" + (i + 1)));
            }
            bufferMinMs = config.optInt("bufferMin", 10000);
            bufferMaxMs = config.optInt("bufferMax", 60000);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveConfigLocal() {
        try {
            JSONObject config = new JSONObject();
            JSONArray sources = new JSONArray();
            for (int i = 0; i < streamUrls.size(); i++) {
                JSONObject source = new JSONObject();
                source.put("name", streamNames.get(i));
                source.put("url", streamUrls.get(i));
                sources.put(source);
            }
            config.put("sources", sources);
            prefs.edit()
                .putString("saved_sources", config.toString())
                .putInt(KEY_BUFFER_MIN, bufferMinMs)
                .putInt(KEY_BUFFER_MAX, bufferMaxMs)
                .apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void fetchRemoteConfig() {
        executorService.execute(() -> {
            try {
                URL url = new URL(remoteConfigUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
                reader.close();
                
                JSONObject config = new JSONObject(result.toString());
                parseConfig(config);
                saveConfigLocal();
                
                runOnUiThread(() -> {
                    updateSourceInfo();
                    if (!useWebMode) {
                        loadStreamFromConfig(0);
                    }
                    Toast.makeText(this, "远程配置已更新", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> 
                    Toast.makeText(this, "获取远程配置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private void startHttpServer() {
        executorService.execute(() -> {
            try {
                ServerSocket serverSocket = new ServerSocket(HTTP_PORT);
                httpServer = new SimpleHttpServer(serverSocket);
                httpServer.start();
                runOnUiThread(() -> {
                    String ip = getLocalIpAddress();
                    tvConfigInfo.setText("配置: http://" + ip + ":" + HTTP_PORT);
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isUp() && !iface.isLoopback()) {
                    Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address) {
                            String ip = addr.getHostAddress();
                            if (!ip.startsWith("127.")) {
                                return ip;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "192.168.x.x";
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void initWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        settings.setBlockNetworkImage(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setPluginState(WebSettings.PluginState.ON);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        // 电视遥控器焦点导航支持
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setClickable(true);
        webView.setLongClickable(true);

        // 硬件加速渲染（软件渲染在电视上会导致视频画面抖动/闪烁）
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // 启用Cookie持久化，保留登录态
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        cookieManager.setAcceptFileSchemeCookies(true);
        // 从持久化存储加载cookie
        CookieManager.getInstance().flush();
        
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                isWebViewLoading = true;
                webViewLoadStartTime = System.currentTimeMillis();
                progressBar.setVisibility(View.VISIBLE);
                startWebViewTimeoutTimer();
            }
            
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isWebViewLoading = false;
                cancelWebViewTimeoutTimer();
                progressBar.setVisibility(View.GONE);
                webViewRetryCount = 0;
                // 持久化保存cookie（保留登录态）
                CookieManager.getInstance().flush();
                injectVideoDetectionScript();
                injectFocusStyle();
                extractAndPlayVideo();
                // 让WebView获取焦点，响应遥控器
                webView.requestFocus();
            }
            
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    handleWebViewError("页面加载错误: " + error.getDescription());
                }
            }
            
            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request.isForMainFrame()) {
                    handleWebViewError("HTTP错误: " + errorResponse.getStatusCode());
                }
            }
            
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false;
                }
                return true;
            }
        });
        
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
            
            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                if (title != null && title.contains("错误")) {
                    handleWebViewError("页面标题显示错误");
                }
            }
            
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // 尝试用ExoPlayer播放直链视频
                if (useWebMode) {
                    tryExtractAndPlayVideo();
                }

                // 同时支持WebView内全屏播放（适用于blob:视频）
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }

                customView = view;
                customViewCallback = callback;

                FrameLayout rootLayout = findViewById(R.id.root_layout);
                rootLayout.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                ));

                if (controlPanel != null) controlPanel.setVisibility(View.GONE);
                hideSystemUI();
            }

            @Override
            public void onHideCustomView() {
                if (customView != null) {
                    FrameLayout rootLayout = findViewById(R.id.root_layout);
                    rootLayout.removeView(customView);
                    customView = null;
                    if (customViewCallback != null) {
                        customViewCallback.onCustomViewHidden();
                        customViewCallback = null;
                    }
                    hideSystemUI();
                }
            }
        });
        
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void onVideoPlaying(String videoUrl) {
                if (videoUrl != null && !videoUrl.isEmpty() && !videoUrl.startsWith("blob:")) {
                    lastDetectedVideoUrl = videoUrl;
                    if (lastDeviceOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                        switchToPlayerMode(videoUrl);
                    }
                }
            }

            @android.webkit.JavascriptInterface
            public void onFullscreenRequested() {
                if (isAutoFullscreenEnabled && useWebMode) {
                    runOnUiThread(() -> {
                        tryExtractAndPlayVideo();
                    });
                }
            }
        }, "AndroidVideoBridge");
    }
    
    private void startWebViewTimeoutTimer() {
        cancelWebViewTimeoutTimer();
        webViewTimeoutRunnable = () -> {
            if (isWebViewLoading) {
                long elapsed = System.currentTimeMillis() - webViewLoadStartTime;
                if (elapsed > WEBVIEW_LOAD_TIMEOUT) {
                    handleWebViewTimeout();
                }
            }
        };
        handler.postDelayed(webViewTimeoutRunnable, WEBVIEW_LOAD_TIMEOUT);
    }
    
    private void cancelWebViewTimeoutTimer() {
        if (webViewTimeoutRunnable != null) {
            handler.removeCallbacks(webViewTimeoutRunnable);
            webViewTimeoutRunnable = null;
        }
    }
    
    private void handleWebViewTimeout() {
        if (webViewRetryCount < WEBVIEW_MAX_RETRY) {
            webViewRetryCount++;
            long delay = WEBVIEW_RETRY_DELAY * (1L << (webViewRetryCount - 1));
            Toast.makeText(this, "加载超时，正在重试(" + webViewRetryCount + "/" + WEBVIEW_MAX_RETRY + ")...", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::retryWebViewLoad, delay);
        } else {
            Toast.makeText(this, "加载失败，请检查网络或切换网站", Toast.LENGTH_LONG).show();
            progressBar.setVisibility(View.GONE);
            isWebViewLoading = false;
            showWebViewErrorPanel();
        }
    }

    private void handleWebViewError(String errorMsg) {
        cancelWebViewTimeoutTimer();

        if (webViewRetryCount < WEBVIEW_MAX_RETRY) {
            webViewRetryCount++;
            long delay = WEBVIEW_RETRY_DELAY * (1L << (webViewRetryCount - 1));
            Toast.makeText(this, errorMsg + "，正在重试(" + webViewRetryCount + "/" + WEBVIEW_MAX_RETRY + ")...", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::retryWebViewLoad, delay);
        } else {
            Toast.makeText(this, errorMsg + "，请检查网络或切换网站", Toast.LENGTH_LONG).show();
            progressBar.setVisibility(View.GONE);
            isWebViewLoading = false;
            showWebViewErrorPanel();
        }
    }

    private void retryWebViewLoad() {
        if (webView == null) return;

        isWebViewLoading = true;
        webViewLoadStartTime = System.currentTimeMillis();
        progressBar.setVisibility(View.VISIBLE);

        // 不清除缓存，避免cookie丢失导致需要登录的网站加载失败
        startWebViewTimeoutTimer();

        if (webSourceUrl != null && !webSourceUrl.isEmpty()) {
            webView.loadUrl(webSourceUrl);
        }
    }

    private void showWebViewErrorPanel() {
        if (tvHintInfo != null) {
            tvHintInfo.setText("网页加载失败，按\"上一个/下一个\"切换网站，或按菜单键打开配置");
            tvHintInfo.setVisibility(View.VISIBLE);
        }
    }

    private void injectFocusStyle() {
        String css = "(function(){" +
            "if (window.__focusStyleInjected) return;" +
            "window.__focusStyleInjected = true;" +
            "var style = document.createElement('style');" +
            "style.innerHTML = '" +
            "*:focus{outline:3px solid #FF9800 !important;outline-offset:2px !important;}" +
            "a:focus,button:focus,input:focus,select:focus,textarea:focus,[tabindex]:focus,[role=button]:focus{outline:3px solid #FF9800 !important;background-color:rgba(255,152,0,0.2) !important;}" +
            "video:focus{outline:4px solid #4CAF50 !important;}" +
            "';" +
            "document.head.appendChild(style);" +
            "})();";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(css, null);
        }
    }

    private void injectVideoDetectionScript() {
        String js = "(function() {" +
            "if (window.__videoDetectionInjected) return;" +
            "window.__videoDetectionInjected = true;" +
            "" +
            "function notifyVideo(video) {" +
            "  try {" +
            "    var src = video.src || video.currentSrc || '';" +
            "    if (video.querySelector('source')) {" +
            "      src = video.querySelector('source').src || src;" +
            "    }" +
            "    if (src && src.indexOf('blob:') === -1 && window.AndroidVideoBridge) {" +
            "      window.AndroidVideoBridge.onVideoPlaying(src);" +
            "    }" +
            "  } catch(e) {}" +
            "}" +
            "" +
            "function requestFullscreen(video) {" +
            "  try {" +
            "    if (video.requestFullscreen) video.requestFullscreen();" +
            "    else if (video.webkitRequestFullscreen) video.webkitRequestFullscreen();" +
            "    else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen();" +
            "    else if (video.msRequestFullscreen) video.msRequestFullscreen();" +
            "    else if (video.parentElement) {" +
            "      var p = video.parentElement;" +
            "      if (p.requestFullscreen) p.requestFullscreen();" +
            "      else if (p.webkitRequestFullscreen) p.webkitRequestFullscreen();" +
            "    }" +
            "  } catch(e) {}" +
            "}" +
            "" +
            "function tryAutoPlay(video) {" +
            "  try {" +
            "    video.muted = true;" +
            "    var p = video.play();" +
            "    if (p && p.catch) p.catch(function(){});" +
            "  } catch(e) {}" +
            "}" +
            "" +
            "function removeCover() {" +
            "  var covers = ['.prism-cover','.vjs-cover','.video-cover','.player-cover','.mask-layer','.bp-overlay','.bpx-player-cover','.bilibili-player-video-cover'];" +
            "  covers.forEach(function(sel){" +
            "    var el = document.querySelector(sel);" +
            "    if (el) el.style.display = 'none';" +
            "  });" +
            "}" +
            "" +
            "function clickPlayButton() {" +
            "  var btns = ['.custom-play-btn','.vjs-big-play-button','.video-play-btn','.player-play-btn','.bilibili-player-video-btn-start','.bpx-player-video-btn-start','[class*=play-btn]','[class*=playButton]'];" +
            "  for (var i = 0; i < btns.length; i++) {" +
            "    var btn = document.querySelector(btns[i]);" +
            "    if (btn) { btn.click(); return true; }" +
            "  }" +
            "  return false;" +
            "}" +
            "" +
            "function handleVideo(video) {" +
            "  if (video.__handled) return;" +
            "  video.__handled = true;" +
            "  video.addEventListener('play', function(e) {" +
            "    notifyVideo(video);" +
            "  }, true);" +
            "  video.addEventListener('playing', function(e) {" +
            "    notifyVideo(video);" +
            "  }, true);" +
            "  video.addEventListener('loadstart', function(e) {" +
            "    notifyVideo(video);" +
            "  }, true);" +
            "  video.addEventListener('webkitbeginfullscreen', function() {" +
            "    if (window.AndroidVideoBridge) window.AndroidVideoBridge.onFullscreenRequested();" +
            "  });" +
            "  video.addEventListener('fullscreenchange', function() {" +
            "    if (document.fullscreenElement && window.AndroidVideoBridge) window.AndroidVideoBridge.onFullscreenRequested();" +
            "  });" +
            "  if (video.readyState >= 2) notifyVideo(video);" +
            "}" +
            "" +
            "function checkVideos() {" +
            "  var videos = document.querySelectorAll('video');" +
            "  videos.forEach(handleVideo);" +
            "  var iframes = document.querySelectorAll('iframe');" +
            "  iframes.forEach(function(iframe) {" +
            "    try {" +
            "      if (iframe.contentDocument) {" +
            "        var innerVideos = iframe.contentDocument.querySelectorAll('video');" +
            "        innerVideos.forEach(handleVideo);" +
            "      }" +
            "    } catch(e) {}" +
            "  });" +
            "}" +
            "" +
            "checkVideos();" +
            "setInterval(function(){ checkVideos(); }, 800);" +
            "" +
            "var observer = new MutationObserver(function(mutations) {" +
            "  mutations.forEach(function(mutation) {" +
            "    mutation.addedNodes.forEach(function(node) {" +
            "      if (node.tagName === 'VIDEO') handleVideo(node);" +
            "      if (node.querySelectorAll) {" +
            "        var videos = node.querySelectorAll('video');" +
            "        videos.forEach(handleVideo);" +
            "      }" +
            "    });" +
            "  });" +
            "});" +
            "observer.observe(document.body, {childList: true, subtree: true});" +
            "" +
            "document.addEventListener('click', function(e) {" +
            "  var target = e.target;" +
            "  var found = false;" +
            "  while (target && !found) {" +
            "    if (target.tagName === 'VIDEO') found = true;" +
            "    if (target.className) {" +
            "      var c = target.className.toLowerCase();" +
            "      if (c.indexOf('play') !== -1 || c.indexOf('video') !== -1 || c.indexOf('fullscreen') !== -1) found = true;" +
            "    }" +
            "    if (target.getAttribute) {" +
            "      var role = target.getAttribute('role');" +
            "      if (role === 'button') found = true;" +
            "    }" +
            "    target = target.parentElement;" +
            "  }" +
            "  if (found) {" +
            "    setTimeout(checkVideos, 100);" +
            "    setTimeout(checkVideos, 500);" +
            "    setTimeout(checkVideos, 1000);" +
            "  }" +
            "}, true);" +
            "})();";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(js, null);
        } else {
            webView.loadUrl("javascript:" + js);
        }
    }
    
    private void extractAndPlayVideo() {
        extractAndPlayVideoWithRetry(0);
    }

    private void extractAndPlayVideoWithRetry(int retryCount) {
        final int maxRetries = 5;
        
        handler.postDelayed(() -> {
            String js = "(function() {" +
                "try {" +
                "  if (window.__playerConfig__ && window.__playerConfig__.source) {" +
                "    return JSON.stringify({success: true, url: window.__playerConfig__.source});" +
                "  }" +
                "  var video = document.querySelector('video');" +
                "  if (video && video.src && video.src.indexOf('blob:') === -1) {" +
                "    return JSON.stringify({success: true, url: video.src});" +
                "  }" +
                "  if (video && video.currentSrc && video.currentSrc.indexOf('blob:') === -1) {" +
                "    return JSON.stringify({success: true, url: video.currentSrc});" +
                "  }" +
                "  if (video && video.querySelector('source')) {" +
                "    var s = video.querySelector('source').src;" +
                "    if (s && s.indexOf('blob:') === -1) return JSON.stringify({success: true, url: s});" +
                "  }" +
                "} catch(e) {}" +
                "return JSON.stringify({success: false, retry: " + retryCount + "});" +
                "})();";

            webView.evaluateJavascript(js, result -> {
                try {
                    if (result == null || result.equals("null") || result.isEmpty()) {
                        if (retryCount < maxRetries) {
                            extractAndPlayVideoWithRetry(retryCount + 1);
                        } else {
                            autoClickPlayButton();
                        }
                        return;
                    }

                    String jsonStr = result.replace("\\\"", "\"").replaceAll("^\"|\"$", "");
                    JSONObject json = new JSONObject(jsonStr);

                    if (json.optBoolean("success", false)) {
                        String videoUrl = json.optString("url", "");
                        // 支持m3u8、mp4、flv等直链格式
                        boolean isDirectLink = videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") || videoUrl.contains(".flv") || videoUrl.contains(".ts");
                        if (!videoUrl.isEmpty() && isDirectLink) {
                            String pageName = "网页视频";
                            if (webView.getUrl() != null) {
                                String host = webView.getUrl();
                                if (host.contains("cctv")) pageName = "央视视频";
                                else if (host.contains("bilibili")) pageName = "B站视频";
                                else if (host.contains("youku")) pageName = "优酷视频";
                                else if (host.contains("iqiyi")) pageName = "爱奇艺视频";
                                else if (host.contains("douyin")) pageName = "抖音视频";
                                else if (host.contains("qq.com")) pageName = "腾讯视频";
                                else if (host.contains("mgtv")) pageName = "芒果TV视频";
                                else if (host.contains("sohu")) pageName = "搜狐视频";
                            }
                            final String finalPageName = pageName;
                            runOnUiThread(() -> {
                                webView.setVisibility(View.GONE);
                                playerContainer.setVisibility(View.VISIBLE);
                                if (player == null) {
                                    initPlayer();
                                }
                                playVideoUrl(videoUrl, finalPageName);
                            });
                        } else if (retryCount < maxRetries) {
                            extractAndPlayVideoWithRetry(retryCount + 1);
                        } else {
                            autoClickPlayButton();
                        }
                    } else {
                        if (retryCount < maxRetries) {
                            extractAndPlayVideoWithRetry(retryCount + 1);
                        } else {
                            autoClickPlayButton();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    if (retryCount < maxRetries) {
                        extractAndPlayVideoWithRetry(retryCount + 1);
                    } else {
                        autoClickPlayButton();
                    }
                }
            });
        }, 2000);
    }

    private void autoClickPlayButton() {
        String js = "(function() {" +
            // 移除播放覆盖层
            "var covers = ['.prism-cover','.vjs-cover','.video-cover','.player-cover','.mask-layer','.bp-overlay','.bpx-player-cover','.bilibili-player-video-cover','.video-mask','.ad-mask'];" +
            "covers.forEach(function(sel){" +
            "  var el = document.querySelector(sel);" +
            "  if (el) el.style.display = 'none';" +
            "});" +
            // 尝试点击各种播放按钮
            "var btns = ['.custom-play-btn','.vjs-big-play-button','.video-play-btn','.player-play-btn','.bilibili-player-video-btn-start','.bpx-player-video-btn-start','[class*=play-btn]','[class*=playButton]','[class*=big-play]'];" +
            "for (var i = 0; i < btns.length; i++) {" +
            "  var btn = document.querySelector(btns[i]);" +
            "  if (btn) { btn.click(); return 'clicked: ' + btns[i]; }" +
            "}" +
            // 尝试直接播放video
            "var video = document.querySelector('video');" +
            "if (video) {" +
            "  video.muted = true;" +
            "  var p = video.play();" +
            "  if (p && p.catch) p.catch(function(){});" +
            "  return 'video.play() called';" +
            "}" +
            // 尝试Aliplayer
            "if (window.Aliplayer && window.Aliplayer.instances && window.Aliplayer.instances.length > 0) {" +
            "  window.Aliplayer.instances[0].play();" +
            "  return 'Aliplayer.play() called';" +
            "}" +
            // 尝试videojs
            "if (window.videojs && window.videojs.players) {" +
            "  for (var id in window.videojs.players) {" +
            "    window.videojs.players[id].play();" +
            "    return 'videojs.play() called';" +
            "  }" +
            "}" +
            // 尝试jwplayer
            "if (window.jwplayer) {" +
            "  for (var i = 0; i < 10; i++) {" +
            "    try { var p = jwplayer(i); if (p && p.play) { p.play(); return 'jwplayer.play() called'; } } catch(e) {}" +
            "  }" +
            "}" +
            // 尝试TCPlayer
            "if (window.TCPlayer && window.TCPlayer.players) {" +
            "  for (var id in window.TCPlayer.players) {" +
            "    window.TCPlayer.players[id].play();" +
            "    return 'TCPlayer.play() called';" +
            "  }" +
            "}" +
            "return 'no play method found';" +
            "})();";
        webView.evaluateJavascript(js, result -> {
        });
    }

    private void playVideoUrl(String url, String name) {
        currentVideoUrl = url;
        currentVideoName = name;
        playVideoUrlWithRetry(url, name, 0, false);
    }

    private void playVideoUrlWithRetry(String url, String name, int retryCount, boolean isRefreshed) {
        if (player == null || url == null || url.isEmpty()) return;
        
        tvSourceInfo.setText(name + (isRefreshed ? " (已刷新)" : ""));
        progressBar.setVisibility(View.VISIBLE);
        
        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
        player.stop();
        player.setMediaItem(mediaItem);
        
        final int[] bufferingTime = {0};
        final boolean[] hasError = {false};
        final int[] seekRetryCount = {0};
        final int[] pauseRetryCount = {0};
        
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                currentVideoWidth = videoSize.width;
                currentVideoHeight = videoSize.height;
                updateVideoLayout(videoSize.width, videoSize.height);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                switch (playbackState) {
                    case Player.STATE_BUFFERING:
                        progressBar.setVisibility(View.VISIBLE);
                        bufferingTime[0] = 0;
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING) {
                                    bufferingTime[0]++;
                                    if (bufferingTime[0] > 45) {
                                        if (!isRefreshed && useWebMode && seekRetryCount[0] >= 2) {
                                            Toast.makeText(MainActivity.this, "缓冲超时，正在重新获取视频地址...", Toast.LENGTH_SHORT).show();
                                            refreshVideoFromWeb();
                                        } else if (seekRetryCount[0] < 3) {
                                            seekRetryCount[0]++;
                                            player.seekTo(Math.max(0, player.getCurrentPosition() - 1000));
                                            bufferingTime[0] = 0;
                                        }
                                    } else {
                                        handler.postDelayed(this, 1000);
                                    }
                                }
                            }
                        }, 1000);
                        break;
                    case Player.STATE_READY:
                        progressBar.setVisibility(View.GONE);
                        isPlaying = true;
                        hasError[0] = false;
                        seekRetryCount[0] = 0;
                        pauseRetryCount[0] = 0;
                        startHideControlTimer();
                        break;
                    case Player.STATE_ENDED:
                        progressBar.setVisibility(View.GONE);
                        break;
                    case Player.STATE_IDLE:
                        progressBar.setVisibility(View.GONE);
                        break;
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                progressBar.setVisibility(View.GONE);
                hasError[0] = true;

                int newRetryCount = retryCount + 1;
                if (newRetryCount <= 3) {
                    final int finalRetryCount = newRetryCount;
                    handler.postDelayed(() -> {
                        if (player != null) {
                            Toast.makeText(MainActivity.this, "自动恢复中(" + finalRetryCount + "/3)...", Toast.LENGTH_SHORT).show();
                            playVideoUrlWithRetry(url, name, finalRetryCount, isRefreshed);
                        }
                    }, 2000);
                } else if (!isRefreshed && useWebMode) {
                    Toast.makeText(MainActivity.this, "地址可能已过期，正在重新获取...", Toast.LENGTH_SHORT).show();
                    refreshVideoFromWeb();
                } else {
                    Toast.makeText(MainActivity.this, "播放错误，请尝试切换网站或检查网络: " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    pauseRetryCount[0] = 0;
                } else if (!hasError[0] && player != null && player.getPlaybackState() == Player.STATE_READY) {
                    pauseRetryCount[0]++;
                    if (pauseRetryCount[0] <= 5) {
                        handler.postDelayed(() -> {
                            if (player != null && !player.isPlaying() && player.getPlaybackState() == Player.STATE_READY && !hasError[0]) {
                                player.setPlayWhenReady(true);
                            }
                        }, 2000);
                    } else if (!isRefreshed && useWebMode) {
                        Toast.makeText(MainActivity.this, "视频源不稳定，正在重新获取...", Toast.LENGTH_SHORT).show();
                        refreshVideoFromWeb();
                    }
                }
            }
        });
        
        player.prepare();
        player.setPlayWhenReady(true);
    }
    
    private void updateVideoLayout(int videoWidth, int videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0) return;
        
        isPortraitVideo = videoHeight > videoWidth;
        
        if (isPortraitVideo) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
    }

    private void refreshVideoFromWeb() {
        webView.setVisibility(View.VISIBLE);
        playerContainer.setVisibility(View.GONE);
        tvSourceInfo.setText("正在重新获取视频地址...");
        progressBar.setVisibility(View.VISIBLE);
        
        webViewRetryCount = 0;
        
        if (webView.getUrl() == null || !webView.getUrl().equals(webSourceUrl)) {
            loadWebSource();
        } else {
            webView.reload();
        }
    }

    private void loadWebSource() {
        if (!isNetworkAvailable) {
            Toast.makeText(this, "网络不可用，请检查网络连接", Toast.LENGTH_LONG).show();
            return;
        }
        
        webView.setVisibility(View.VISIBLE);
        playerContainer.setVisibility(View.GONE);
        tvSourceInfo.setText(webSiteNames.isEmpty() ? "加载中..." : webSiteNames.get(currentSiteIndex) + "(加载中...)");
        progressBar.setVisibility(View.VISIBLE);
        
        isWebViewLoading = true;
        webViewLoadStartTime = System.currentTimeMillis();
        webViewRetryCount = 0;
        
        webView.resumeTimers();
        webView.onResume();
        webView.loadUrl(webSourceUrl);
        
        startWebViewTimeoutTimer();
    }

    private void initPlayer() {
        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true);
        
        int minBuffer = Math.max(bufferMinMs, 10000);
        int maxBuffer = Math.max(bufferMaxMs, 60000);
        
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, 1000, minBuffer)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(maxBuffer, true)
            .setTargetBufferBytes(-1)
            .build();
        
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(httpDataSourceFactory);
        mediaSourceFactory.setDataSourceFactory(httpDataSourceFactory);
        
        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build();
        
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build();
        player.setAudioAttributes(audioAttributes, false);
        
        playerView.setPlayer(player);
    }

    private void switchMode() {
        useWebMode = !useWebMode;
        prefs.edit().putBoolean(KEY_USE_WEB_MODE, useWebMode).apply();

        if (useWebMode) {
            if (player != null) {
                player.stop();
                player.setPlayWhenReady(false);
            }
            webView.setVisibility(View.VISIBLE);
            playerContainer.setVisibility(View.GONE);
            webView.onResume();
            webView.resumeTimers();
            webViewRetryCount = 0;
            if (webView.getUrl() == null || webView.getUrl().isEmpty() || webView.getUrl().equals("about:blank")) {
                loadWebSource();
            }
            updateSourceInfo();
            Toast.makeText(this, "切换到网页模式", Toast.LENGTH_SHORT).show();
        } else {
            cancelWebViewTimeoutTimer();
            webView.pauseTimers();
            webView.onPause();
            webView.setVisibility(View.GONE);
            playerContainer.setVisibility(View.VISIBLE);
            if (player == null) {
                initPlayer();
            }
            // 如果有嗅探到的视频地址，直接播放；否则从直播源列表加载
            if (!lastDetectedVideoUrl.isEmpty()) {
                playVideoUrl(lastDetectedVideoUrl, "网页视频");
            } else if (isStreamListEnabled && !streamUrls.isEmpty()) {
                loadStreamFromConfig(currentUrlIndex);
            } else {
                Toast.makeText(this, "无可用视频源，请先在网页中播放视频", Toast.LENGTH_LONG).show();
            }
            Toast.makeText(this, "切换到播放器模式", Toast.LENGTH_SHORT).show();
        }
    }

    private void switchToNextSource() {
        if (streamUrls.isEmpty()) return;
        currentUrlIndex = (currentUrlIndex + 1) % streamUrls.size();
        loadStreamFromConfig(currentUrlIndex);
    }

    private void switchToPrevSource() {
        if (streamUrls.isEmpty()) return;
        currentUrlIndex = (currentUrlIndex - 1 + streamUrls.size()) % streamUrls.size();
        loadStreamFromConfig(currentUrlIndex);
    }
    
    private void switchToNextChannel() {
        switchMode();
    }

    private void switchToPrevChannel() {
        switchMode();
    }

    private void loadStreamFromConfig(int index) {
        if (streamUrls.isEmpty()) {
            progressBar.setVisibility(View.GONE);
            Toast.makeText(this, "没有配置直播源", Toast.LENGTH_LONG).show();
            return;
        }
        
        if (!isNetworkAvailable) {
            Toast.makeText(this, "网络不可用，请检查网络连接", Toast.LENGTH_LONG).show();
            return;
        }

        if (index >= streamUrls.size()) {
            index = 0;
            currentUrlIndex = 0;
        }
        
        isPlaying = false;
        errorRetryCount = 0;
        showControlPanel();
        updateSourceInfo();
        
        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(streamUrls.get(index)));
        player.stop();
        player.setMediaItem(mediaItem);
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                currentVideoWidth = videoSize.width;
                currentVideoHeight = videoSize.height;
                updateVideoLayout(videoSize.width, videoSize.height);
            }
            
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                switch (playbackState) {
                    case Player.STATE_BUFFERING:
                        progressBar.setVisibility(View.VISIBLE);
                        break;
                    case Player.STATE_READY:
                        progressBar.setVisibility(View.GONE);
                        isPlaying = true;
                        errorRetryCount = 0;
                        startHideControlTimer();
                        break;
                    case Player.STATE_ENDED:
                    case Player.STATE_IDLE:
                        progressBar.setVisibility(View.GONE);
                        break;
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                progressBar.setVisibility(View.GONE);
                errorRetryCount++;
                if (errorRetryCount <= MAX_RETRY_COUNT) {
                    Toast.makeText(MainActivity.this, 
                        "播放错误，重试中(" + errorRetryCount + "/" + MAX_RETRY_COUNT + ")...", 
                        Toast.LENGTH_SHORT).show();
                    handler.postDelayed(() -> {
                        if (player != null) {
                            player.prepare();
                        }
                    }, 1000);
                } else {
                    Toast.makeText(MainActivity.this, 
                        "播放失败，请切换其他源或检查网络", 
                        Toast.LENGTH_LONG).show();
                }
            }
        });
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void showControlPanel() {
        if (controlPanel != null) {
            controlPanel.setVisibility(View.VISIBLE);
            isControlVisible = true;
        }
    }

    private void hideControlPanel() {
        if (controlPanel != null) {
            controlPanel.setVisibility(View.GONE);
            isControlVisible = false;
        }
    }

    private void togglePlayPause() {
        if (useWebMode) {
            toggleWebViewPlayPause();
        } else {
            if (player != null) {
                player.setPlayWhenReady(!player.isPlaying());
            }
        }
    }
    
    private void toggleWebViewPlayPause() {
        String js = "(function() {" +
            "var videos = document.querySelectorAll('video');" +
            "if (videos.length > 0) {" +
            "  var video = videos[0];" +
            "  if (video.paused) {" +
            "    video.play();" +
            "    return 'playing';" +
            "  } else {" +
            "    video.pause();" +
            "    return 'paused';" +
            "  }" +
            "}" +
            "return 'no video';" +
            "})()";
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(js, result -> {
                if (result != null && result.contains("playing")) {
                    Toast.makeText(this, "播放", Toast.LENGTH_SHORT).show();
                } else if (result != null && result.contains("paused")) {
                    Toast.makeText(this, "暂停", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
    
    private void toggleControlPanel() {
        if (isControlVisible) {
            hideControlPanel();
        } else {
            showControlPanel();
            startHideControlTimer();
        }
    }

    private void startHideControlTimer() {
        if (hideControlRunnable != null) {
            handler.removeCallbacks(hideControlRunnable);
        }
        hideControlRunnable = () -> {
            if (isPlaying) {
                hideControlPanel();
            }
        };
        handler.postDelayed(hideControlRunnable, 5000);
    }

    private void updateSourceInfo() {
        if (!streamUrls.isEmpty() && currentUrlIndex < streamNames.size()) {
            String name = streamNames.get(currentUrlIndex);
            tvSourceInfo.setText(name + " (" + (currentUrlIndex + 1) + "/" + streamUrls.size() + ")");
        } else if (!streamUrls.isEmpty()) {
            tvSourceInfo.setText("源 " + (currentUrlIndex + 1) + "/" + streamUrls.size() + ")");
        }
    }

    public void updateConfig(String jsonConfig) {
        try {
            JSONObject config = new JSONObject(jsonConfig);
            
            if (config.has("remoteUrl")) {
                remoteConfigUrl = config.optString("remoteUrl", "");
                prefs.edit().putString(KEY_REMOTE_URL, remoteConfigUrl).apply();
            }
            if (config.has("autoUpdate")) {
                autoUpdateConfig = config.optBoolean("autoUpdate", false);
                prefs.edit().putBoolean(KEY_AUTO_UPDATE, autoUpdateConfig).apply();
            }
            if (config.has("bufferMin")) {
                bufferMinMs = config.optInt("bufferMin", 5000);
            }
            if (config.has("bufferMax")) {
                bufferMaxMs = config.optInt("bufferMax", 30000);
            }
            if (config.has("useWebMode")) {
                useWebMode = config.optBoolean("useWebMode", true);
                prefs.edit().putBoolean(KEY_USE_WEB_MODE, useWebMode).apply();
            }
            if (config.has("playerModeEnabled")) {
                isStreamListEnabled = config.optBoolean("playerModeEnabled", true);
                prefs.edit().putBoolean(KEY_PLAYER_MODE_ENABLED, isStreamListEnabled).apply();
            }

            if (config.has("websites")) {
                JSONArray websites = config.getJSONArray("websites");
                webSiteNames.clear();
                webSiteUrls.clear();
                for (int i = 0; i < websites.length(); i++) {
                    JSONObject site = websites.getJSONObject(i);
                    webSiteNames.add(site.optString("name", "网站" + (i + 1)));
                    webSiteUrls.add(site.optString("url", ""));
                }
                saveWebSites();
                if (!webSiteUrls.isEmpty()) {
                    webSourceUrl = webSiteUrls.get(0);
                    currentSiteIndex = 0;
                }
            }

            if (config.has("playerVideoUrls")) {
                JSONArray urls = config.getJSONArray("playerVideoUrls");
                playerVideoUrls.clear();
                playerVideoNames.clear();
                for (int i = 0; i < urls.length(); i++) {
                    JSONObject item = urls.getJSONObject(i);
                    playerVideoNames.add(item.optString("name", "视频" + (i + 1)));
                    playerVideoUrls.add(item.optString("url", ""));
                }
                savePlayerVideoUrls();
            }

            if (config.has("sources")) {
                parseConfig(config);
                saveConfigLocal();
            }

            currentUrlIndex = 0;
            runOnUiThread(() -> {
                updatePlayerModeButtons();
                if (useWebMode) {
                    webViewRetryCount = 0;
                    loadWebSource();
                } else {
                    if (player == null) {
                        initPlayer();
                    }
                    loadStreamFromConfig(0);
                }
                Toast.makeText(this, "配置已更新", Toast.LENGTH_SHORT).show();
            });
        } catch (Exception e) {
            e.printStackTrace();
            runOnUiThread(() -> 
                Toast.makeText(this, "配置格式错误: " + e.getMessage(), Toast.LENGTH_LONG).show()
            );
        }
    }
    
    private void saveWebSites() {
        try {
            JSONArray sites = new JSONArray();
            for (int i = 0; i < webSiteUrls.size(); i++) {
                JSONObject site = new JSONObject();
                site.put("name", webSiteNames.get(i));
                site.put("url", webSiteUrls.get(i));
                sites.put(site);
            }
            prefs.edit().putString(KEY_WEB_SITES, sites.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (useWebMode && webView != null && webView.hasFocus()) {
                        // WebView模式下，确认键模拟点击当前焦点元素
                        return super.dispatchKeyEvent(event);
                    }
                    togglePlayPause();
                    return true;

                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (useWebMode && webView != null) {
                        // WebView模式下，方向键用于网页焦点导航
                        return super.dispatchKeyEvent(event);
                    }
                    switchMode();
                    return true;

                case KeyEvent.KEYCODE_DPAD_UP:
                    if (useWebMode && webView != null) {
                        return super.dispatchKeyEvent(event);
                    }
                    switchToPrevSource();
                    return true;

                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (useWebMode && webView != null) {
                        return super.dispatchKeyEvent(event);
                    }
                    switchToNextSource();
                    return true;

                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_SPACE:
                    togglePlayPause();
                    return true;

                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    // 频道+键切换网站
                    if (useWebMode) {
                        switchToNextWebSite();
                    } else {
                        switchToNextSource();
                    }
                    return true;

                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    // 频道-键切换网站
                    if (useWebMode) {
                        switchToPrevWebSite();
                    } else {
                        switchToPrevSource();
                    }
                    return true;

                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_INFO:
                    showControlPanel();
                    startHideControlTimer();
                    return true;

                case KeyEvent.KEYCODE_BACK:
                    // 先退出WebView全屏视图
                    if (customView != null) {
                        if (customViewCallback != null) {
                            customViewCallback.onCustomViewHidden();
                        }
                        FrameLayout rootLayout = findViewById(R.id.root_layout);
                        rootLayout.removeView(customView);
                        customView = null;
                        customViewCallback = null;
                        return true;
                    }
                    if (webView != null && webView.canGoBack() && useWebMode) {
                        webView.goBack();
                        return true;
                    }
                    break;

                case KeyEvent.KEYCODE_M:
                    switchMode();
                    return true;

                case KeyEvent.KEYCODE_L:
                    toggleOrientationLock();
                    return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null && !useWebMode) {
            player.setPlayWhenReady(true);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (useWebMode && player != null && !currentVideoUrl.isEmpty()) {
            webView.resumeTimers();
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (useWebMode) {
            webView.pauseTimers();
            webView.onPause();
        }
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    @Override
    protected void onDestroy() {
        cancelWebViewTimeoutTimer();

        // 停止时钟
        if (clockHandler != null && clockRunnable != null) {
            clockHandler.removeCallbacks(clockRunnable);
        }

        // 清理全屏视图
        if (customView != null) {
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
            }
            customView = null;
            customViewCallback = null;
        }

        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }
        
        if (networkCallback != null && connectivityManager != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        
        if (httpServer != null) {
            httpServer.stopServer();
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (webView != null) {
            webView.loadDataWithBaseURL(null, "", "text/html", "utf-8", null);
            webView.clearCache(true);
            webView.clearHistory();
            webView.destroy();
        }
        
        if (executorService != null) {
            executorService.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 先退出WebView全屏视图
        if (customView != null) {
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
            }
            FrameLayout rootLayout = findViewById(R.id.root_layout);
            rootLayout.removeView(customView);
            customView = null;
            customViewCallback = null;
            return;
        }

        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        hideSystemUI();
        if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            updateVideoLayout(currentVideoWidth, currentVideoHeight);
        }
    }

    private class SimpleHttpServer extends Thread {
        private ServerSocket serverSocket;
        private boolean running = true;

        public SimpleHttpServer(ServerSocket socket) {
            this.serverSocket = socket;
        }

        public void stopServer() {
            running = false;
            try {
                serverSocket.close();
            } catch (Exception e) {}
        }

        @Override
        public void run() {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    handleClient(client);
                } catch (Exception e) {
                    if (running) e.printStackTrace();
                }
            }
        }

        private void handleClient(Socket client) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String request = reader.readLine();
                
                if (request != null && request.startsWith("POST")) {
                    StringBuilder body = new StringBuilder();
                    String line;
                    int contentLength = 0;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("Content-Length:")) {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        }
                        if (line.isEmpty()) break;
                    }
                    
                    char[] bodyChars = new char[contentLength];
                    reader.read(bodyChars, 0, contentLength);
                    String configBody = new String(bodyChars);
                    
                    updateConfig(configBody);
                    
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\n\r\n{\"status\":\"ok\"}";
                    client.getOutputStream().write(response.getBytes());
                } else if (request != null && request.startsWith("GET") && request.contains("/proxy?url=")) {
                    String proxyUrl = java.net.URLDecoder.decode(request.split("url=")[1].split(" ")[0], "UTF-8");
                    String proxyResult = fetchUrlContent(proxyUrl);
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\n\r\n" + proxyResult;
                    client.getOutputStream().write(response.getBytes());
                } else {
                    String html = getHtmlPage();
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\n\r\n" + html;
                    client.getOutputStream().write(response.getBytes());
                }
                
                client.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        private String fetchUrlContent(String urlStr) {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder result = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
                reader.close();
                return result.toString();
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
            }
        }

        private String getHtmlPage() {
            StringBuilder sourcesJson = new StringBuilder("[");
            for (int i = 0; i < streamUrls.size(); i++) {
                if (i > 0) sourcesJson.append(",");
                sourcesJson.append("{\"name\":\"").append(streamNames.get(i))
                    .append("\",\"url\":\"").append(streamUrls.get(i)).append("\"}");
            }
            sourcesJson.append("]");

            StringBuilder webSitesJson = new StringBuilder("[");
            for (int i = 0; i < webSiteUrls.size(); i++) {
                if (i > 0) webSitesJson.append(",");
                webSitesJson.append("{\"name\":\"").append(webSiteNames.get(i))
                    .append("\",\"url\":\"").append(webSiteUrls.get(i)).append("\"}");
            }
            webSitesJson.append("]");

            StringBuilder playerVideoUrlsJson = new StringBuilder("[");
            for (int i = 0; i < playerVideoUrls.size(); i++) {
                if (i > 0) playerVideoUrlsJson.append(",");
                playerVideoUrlsJson.append("{\"name\":\"").append(playerVideoNames.get(i))
                    .append("\",\"url\":\"").append(playerVideoUrls.get(i)).append("\"}");
            }
            playerVideoUrlsJson.append("]");

            return "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>新闻直播配置</title>" +
                "<style>" +
                "body{font-family:Arial,sans-serif;max-width:600px;margin:0 auto;padding:20px;background:#f5f5f5}" +
                "h1{color:#333;text-align:center;margin-bottom:10px}" +
                ".tip{color:#666;font-size:12px;text-align:center;margin-bottom:15px}" +
                ".section{background:#fff;padding:15px;margin:10px 0;border-radius:8px;box-shadow:0 2px 4px rgba(0,0,0,0.1)}" +
                ".section-title{font-weight:bold;color:#333;margin-bottom:10px;padding-bottom:5px;border-bottom:1px solid #eee}" +
                ".source-item{background:#f9f9f9;padding:15px;margin:10px 0;border-radius:8px;border:1px solid #e0e0e0;position:relative}" +
                ".source-item.dragging{opacity:0.5;box-shadow:0 4px 8px rgba(0,0,0,0.2)}" +
                ".source-item.drag-over{border:2px dashed #2196F3}" +
                ".item-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}" +
                ".drag-handle{color:#999;font-size:20px;cursor:grab}" +
                ".item-index{background:#2196F3;color:#fff;padding:2px 8px;border-radius:4px;font-size:12px}" +
                "input[type=text],input[type=url],input[type=number]{width:100%;padding:10px;margin:5px 0;border:1px solid #ddd;border-radius:4px;box-sizing:border-box}" +
                "input[type=checkbox]{width:18px;height:18px;vertical-align:middle}" +
                ".btn-group{display:flex;gap:5px;margin-top:8px;flex-wrap:wrap}" +
                "button{padding:8px 16px;border:none;border-radius:4px;cursor:pointer;font-size:14px}" +
                ".btn-up,.btn-down{background:#9e9e9e;color:#fff}" +
                ".btn-del{background:#f44336;color:#fff}" +
                ".btn-add{background:#4CAF50;color:#fff;width:100%}" +
                ".btn-save{background:#2196F3;color:#fff;width:100%}" +
                ".btn-fetch{background:#FF9800;color:#fff}" +
                "label{display:flex;align-items:center;gap:8px;margin:8px 0}" +
                ".buffer-inputs{display:flex;gap:10px}" +
                ".buffer-inputs input{flex:1}" +
                ".web-mode-section{background:#E8F5E9;border:1px solid #4CAF50}" +
                ".player-mode-section{background:#FFF3E0;border:1px solid #FF9800}" +
                ".group-header{background:#37474F;color:#fff;padding:10px 15px;margin:20px 0 10px;border-radius:8px;font-weight:bold;font-size:16px}" +
                ".status-badge{display:inline-block;padding:2px 8px;border-radius:4px;font-size:11px;margin-left:8px}" +
                ".status-on{background:#4CAF50;color:#fff}" +
                ".status-off{background:#9e9e9e;color:#fff}" +
                ".network-status{padding:8px 12px;border-radius:4px;margin-bottom:10px;font-size:13px}" +
                ".network-ok{background:#E8F5E9;color:#2E7D32}" +
                ".network-error{background:#FFEBEE;color:#C62828}" +
                ".website-item{background:#E3F2FD;border:1px solid #2196F3}" +
                ".player-video-item{background:#FFF8E1;border:1px solid #FF9800}" +
                "</style></head><body>" +
                "<h1>📺 新闻直播配置</h1>" +
                "<div class='tip'>💡 网页浏览器模式 | 拖拽排序 | 频道+/-切换网站</div>" +
                "<div class='network-status " + (isNetworkAvailable ? "network-ok" : "network-error") + "'>" +
                "网络状态: " + (isNetworkAvailable ? "✅ 已连接" : "❌ 未连接") + "</div>" +
                "<div class='group-header'>🌐 网页浏览区</div>" +
                "<div class='section web-mode-section'>" +
                "<div class='section-title'>🌐 网页模式 <span class='status-badge " + (useWebMode ? "status-on" : "status-off") + "'>" + (useWebMode ? "已启用" : "已禁用") + "</span></div>" +
                "<label><input type='checkbox' id='useWebMode' " + (useWebMode ? "checked" : "") + "> 启用网页模式（默认开启）</label>" +
                "<div class='tip'>App启动时默认进入网页模式，可浏览各种视频网站</div>" +
                "</div>" +
                "<div class='section'>" +
                "<div class='section-title'>🌐 网页列表 (频道+/-键切换)</div>" +
                "<div id='websites'></div>" +
                "<button class='btn-add' onclick='addWebsite()'>+ 添加网页</button>" +
                "</div>" +
                "<div class='group-header'>🎬 播放器区</div>" +
                "<div class='section player-mode-section'>" +
                "<div class='section-title'>📋 直播源列表 <span class='status-badge " + (isStreamListEnabled ? "status-on" : "status-off") + "'>" + (isStreamListEnabled ? "已启用" : "已停用") + "</span></div>" +
                "<label><input type='checkbox' id='playerModeEnabled' " + (isStreamListEnabled ? "checked" : "") + "> 启用直播源列表</label>" +
                "<div class='tip'>关闭后不使用下方直播源列表，但网页嗅探的视频仍能用播放器播放</div>" +
                "</div>" +
                "<div class='section'>" +
                "<div class='section-title'>📺 直播源列表</div>" +
                "<div class='tip'>播放器模式下使用以下源（从远程配置获取）</div>" +
                "<div id='sources'></div>" +
                "<button class='btn-add' onclick='addSource()'>+ 添加直播源</button>" +
                "</div>" +
                "<div class='section'>" +
                "<div class='section-title'>🎬 播放器模式视频网址</div>" +
                "<div class='tip'>播放器模式下可播放以下视频网址（支持m3u8/mp4/flv直链）</div>" +
                "<div id='playerVideos'></div>" +
                "<button class='btn-add' onclick='addPlayerVideo()'>+ 添加视频网址</button>" +
                "</div>" +
                "<div class='group-header'>⚙️ 系统设置区</div>" +
                "<div class='section'>" +
                "<div class='section-title'>⏱️ 缓冲设置 (毫秒)</div>" +
                "<div class='buffer-inputs'>" +
                "<input type='number' id='bufferMin' placeholder='最小缓冲(默认10000)' value='" + bufferMinMs + "'>" +
                "<input type='number' id='bufferMax' placeholder='最大缓冲(默认60000)' value='" + bufferMaxMs + "'>" +
                "</div>" +
                "<div class='tip'>缓冲越大越流畅，建议最小10000，最大60000以上</div>" +
                "</div>" +
                "<div class='section'>" +
                "<div class='section-title'>⚙️ 远程配置</div>" +
                "<input type='url' id='remoteUrl' placeholder='远程配置URL' value='" + remoteConfigUrl + "'>" +
                "<label><input type='checkbox' id='autoUpdate' " + (autoUpdateConfig ? "checked" : "") + "> 启动时自动更新</label>" +
                "<div class='btn-group'><button class='btn-fetch' onclick='fetchRemote()'>📥 从URL获取</button></div>" +
                "</div>" +
                "<button class='btn-save' onclick='saveConfig()'>💾 保存配置</button>" +
                "<script>" +
                "var sources=" + sourcesJson.toString() + ";" +
                "var websites=" + webSitesJson.toString() + ";" +
                "var playerVideos=" + playerVideoUrlsJson.toString() + ";" +
                "var draggedItem=null;" +
                "function renderSources(){" +
                "  var html='';" +
                "  for(var i=0;i<sources.length;i++){" +
                "    html+='<div class=\"source-item\" draggable=\"true\" data-index=\"'+i+'\" data-type=\"source\" ondragstart=\"dragStart(event)\" ondragover=\"dragOver(event)\" ondrop=\"drop(event)\" ondragend=\"dragEnd(event)\">';" +
                "    html+='<div class=\"item-header\"><span class=\"drag-handle\">☰</span><span class=\"item-index\">'+(i+1)+'</span></div>';" +
                "    html+='<input type=\"text\" placeholder=\"名称\" value=\"'+sources[i].name+'\" onchange=\"sources['+i+'].name=this.value\">';" +
                "    html+='<input type=\"url\" placeholder=\"直播地址\" value=\"'+sources[i].url+'\" onchange=\"sources['+i+'].url=this.value\">';" +
                "    html+='<div class=\"btn-group\">';" +
                "    html+='<button class=\"btn-up\" onclick=\"moveSourceUp('+i+')\" '+(i===0?'disabled style=\"opacity:0.5\"':'')+'>↑</button>';" +
                "    html+='<button class=\"btn-down\" onclick=\"moveSourceDown('+i+')\" '+(i===sources.length-1?'disabled style=\"opacity:0.5\"':'')+'>↓</button>';" +
                "    html+='<button class=\"btn-del\" onclick=\"delSource('+i+')\">删除</button>';" +
                "    html+='</div></div>';" +
                "  }" +
                "  document.getElementById('sources').innerHTML=html;" +
                "}" +
                "function renderWebsites(){" +
                "  var html='';" +
                "  for(var i=0;i<websites.length;i++){" +
                "    html+='<div class=\"source-item website-item\" draggable=\"true\" data-index=\"'+i+'\" data-type=\"website\" ondragstart=\"dragStart(event)\" ondragover=\"dragOver(event)\" ondrop=\"drop(event)\" ondragend=\"dragEnd(event)\">';" +
                "    html+='<div class=\"item-header\"><span class=\"drag-handle\">☰</span><span class=\"item-index\" style=\"background:#2196F3\">'+(i+1)+'</span></div>';" +
                "    html+='<input type=\"text\" placeholder=\"网站名称\" value=\"'+websites[i].name+'\" onchange=\"websites['+i+'].name=this.value\">';" +
                "    html+='<input type=\"url\" placeholder=\"网站地址\" value=\"'+websites[i].url+'\" onchange=\"websites['+i+'].url=this.value\">';" +
                "    html+='<div class=\"btn-group\">';" +
                "    html+='<button class=\"btn-up\" onclick=\"moveWebsiteUp('+i+')\" '+(i===0?'disabled style=\"opacity:0.5\"':'')+'>↑</button>';" +
                "    html+='<button class=\"btn-down\" onclick=\"moveWebsiteDown('+i+')\" '+(i===websites.length-1?'disabled style=\"opacity:0.5\"':'')+'>↓</button>';" +
                "    html+='<button class=\"btn-del\" onclick=\"delWebsite('+i+')\">删除</button>';" +
                "    html+='</div></div>';" +
                "  }" +
                "  document.getElementById('websites').innerHTML=html;" +
                "}" +
                "function renderPlayerVideos(){" +
                "  var html='';" +
                "  for(var i=0;i<playerVideos.length;i++){" +
                "    html+='<div class=\"source-item player-video-item\" draggable=\"true\" data-index=\"'+i+'\" data-type=\"playerVideo\" ondragstart=\"dragStart(event)\" ondragover=\"dragOver(event)\" ondrop=\"drop(event)\" ondragend=\"dragEnd(event)\">';" +
                "    html+='<div class=\"item-header\"><span class=\"drag-handle\">☰</span><span class=\"item-index\" style=\"background:#FF9800\">'+(i+1)+'</span></div>';" +
                "    html+='<input type=\"text\" placeholder=\"视频名称\" value=\"'+playerVideos[i].name+'\" onchange=\"playerVideos['+i+'].name=this.value\">';" +
                "    html+='<input type=\"url\" placeholder=\"视频地址(m3u8/mp4/flv)\" value=\"'+playerVideos[i].url+'\" onchange=\"playerVideos['+i+'].url=this.value\">';" +
                "    html+='<div class=\"btn-group\">';" +
                "    html+='<button class=\"btn-up\" onclick=\"movePlayerVideoUp('+i+')\" '+(i===0?'disabled style=\"opacity:0.5\"':'')+'>↑</button>';" +
                "    html+='<button class=\"btn-down\" onclick=\"movePlayerVideoDown('+i+')\" '+(i===playerVideos.length-1?'disabled style=\"opacity:0.5\"':'')+'>↓</button>';" +
                "    html+='<button class=\"btn-del\" onclick=\"delPlayerVideo('+i+')\">删除</button>';" +
                "    html+='</div></div>';" +
                "  }" +
                "  document.getElementById('playerVideos').innerHTML=html;" +
                "}" +
                "function dragStart(e){draggedItem=e.target;e.target.classList.add('dragging');e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('type',e.target.dataset.type);}" +
                "function dragOver(e){e.preventDefault();var item=e.target.closest('.source-item');if(item&&item!==draggedItem)item.classList.add('drag-over');}" +
                "function drop(e){e.preventDefault();var item=e.target.closest('.source-item');if(item&&item!==draggedItem){var from=parseInt(draggedItem.dataset.index);var to=parseInt(item.dataset.index);var type=e.dataTransfer.getData('type');if(type==='source'){var t=sources[from];sources.splice(from,1);sources.splice(to,0,t);renderSources();}else if(type==='playerVideo'){var t=playerVideos[from];playerVideos.splice(from,1);playerVideos.splice(to,0,t);renderPlayerVideos();}else{var t=websites[from];websites.splice(from,1);websites.splice(to,0,t);renderWebsites();}}document.querySelectorAll('.source-item').forEach(el=>el.classList.remove('drag-over'));}" +
                "function dragEnd(e){e.target.classList.remove('dragging');document.querySelectorAll('.source-item').forEach(el=>el.classList.remove('drag-over'));}" +
                "function moveSourceUp(i){if(i>0){var t=sources[i];sources[i]=sources[i-1];sources[i-1]=t;renderSources();}}" +
                "function moveSourceDown(i){if(i<sources.length-1){var t=sources[i];sources[i+1]=sources[i];sources[i+1]=t;renderSources();}}" +
                "function delSource(i){if(confirm('确定删除？')){sources.splice(i,1);renderSources();}}" +
                "function addSource(){sources.push({name:'',url:''});renderSources();}" +
                "function moveWebsiteUp(i){if(i>0){var t=websites[i];websites[i]=websites[i-1];websites[i-1]=t;renderWebsites();}}" +
                "function moveWebsiteDown(i){if(i<websites.length-1){var t=websites[i];websites[i+1]=websites[i];websites[i+1]=t;renderWebsites();}}" +
                "function delWebsite(i){if(confirm('确定删除？')){websites.splice(i,1);renderWebsites();}}" +
                "function addWebsite(){websites.push({name:'',url:''});renderWebsites();}" +
                "function movePlayerVideoUp(i){if(i>0){var t=playerVideos[i];playerVideos[i]=playerVideos[i-1];playerVideos[i-1]=t;renderPlayerVideos();}}" +
                "function movePlayerVideoDown(i){if(i<playerVideos.length-1){var t=playerVideos[i];playerVideos[i+1]=playerVideos[i];playerVideos[i+1]=t;renderPlayerVideos();}}" +
                "function delPlayerVideo(i){if(confirm('确定删除？')){playerVideos.splice(i,1);renderPlayerVideos();}}" +
                "function addPlayerVideo(){playerVideos.push({name:'',url:''});renderPlayerVideos();}" +
                "function fetchRemote(){var url=document.getElementById('remoteUrl').value;if(!url){alert('请输入URL');return;}fetch('/proxy?url='+encodeURIComponent(url)).then(r=>r.json()).then(d=>{if(d.error){alert('获取失败:'+d.error);}else if(d.sources){sources=d.sources;renderSources();alert('获取成功');}else{alert('格式错误');}}).catch(e=>alert('获取失败:'+e));}" +
                "function saveConfig(){var d={sources:sources,websites:websites,playerVideoUrls:playerVideos,remoteUrl:document.getElementById('remoteUrl').value,autoUpdate:document.getElementById('autoUpdate').checked,bufferMin:parseInt(document.getElementById('bufferMin').value)||5000,bufferMax:parseInt(document.getElementById('bufferMax').value)||30000,useWebMode:document.getElementById('useWebMode').checked,playerModeEnabled:document.getElementById('playerModeEnabled').checked};fetch('',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(d)}).then(r=>r.json()).then(x=>alert('保存成功！')).catch(e=>alert('保存失败:'+e));}" +
                "renderSources();" +
                "renderWebsites();" +
                "renderPlayerVideos();" +
                "</script></body></html>";
        }
    }
}
