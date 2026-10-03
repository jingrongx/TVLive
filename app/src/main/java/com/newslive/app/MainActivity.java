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
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
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
import android.widget.ListView;
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
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
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
    private static final String KEY_WEB_SITES_VERSION = "web_sites_version";
    private static final int CURRENT_WEB_SITES_VERSION = 6; // 版本号递增以触发配置刷新
    private static final String KEY_CURRENT_SITE_INDEX = "current_site_index";
    private static final String KEY_LOCK_ORIENTATION = "lock_orientation";
    private static final String KEY_PLAYER_MODE_ENABLED = "player_mode_enabled";
    private static final String KEY_BANNER_VISIBLE = "banner_visible";
    private static final String KEY_BANNER_FONT_SIZE = "banner_font_size";
    private static final String KEY_BANNER_HEIGHT = "banner_height";
    private static final String KEY_MANUAL_LOCATION = "manual_location"; // 手动配置地区（空=自动IP定位）
    private static final int HTTP_PORT = 8765;
    
    private static final String DEFAULT_REMOTE_URL = "https://gitee.com/xujingrong/tv-live-config/releases/download/live/tv_live_config.json";
    /** 旧版 gitee raw 地址（raw 下载受限），loadSavedConfig 时自动迁移到 release 直链 */
    private static final String LEGACY_REMOTE_URL = "https://gitee.com/xujingrong/tv-live-config/raw/master/tv-live-source.json";
    private static final String DEFAULT_WEB_SOURCE_URL = "https://m-live.cctvnews.cctv.com/live/landscape.html?liveRoomNumber=16265686808730585228";
    
    // [名称, URL, 启用标记] "1"=启用 "0"=停用，启动时加载到 webSiteNames/webSiteUrls/webSiteEnabled
    private static final String[][] DEFAULT_WEB_SITES = {
        {"央视新闻直播", "https://m-live.cctvnews.cctv.com/live/landscape.html?liveRoomNumber=16265686808730585228", "1"},
        {"CCTV13新闻", "https://tv.cctv.com/live/cctv13/m/index.shtml", "0"},
        {"央视直播大全", "https://tv.cctv.com/live/index.shtml", "0"},
        {"CCTV1综合", "https://tv.cctv.com/live/cctv1/m/index.shtml", "1"},
        {"CCTV2财经", "https://tv.cctv.com/live/cctv2/m/index.shtml", "1"},
        {"CCTV3综艺", "https://tv.cctv.com/live/cctv3/m/index.shtml", "0"},
        {"CCTV4中文国际", "https://tv.cctv.com/live/cctv4/m/index.shtml", "1"},
        {"CCTV5体育", "https://tv.cctv.com/live/cctv5/m/index.shtml", "1"},
        {"CCTV5+体育赛事", "https://tv.cctv.com/live/cctv5plus/m/index.shtml", "0"},
        {"CCTV6电影", "https://tv.cctv.com/live/cctv6/m/index.shtml", "0"},
        {"CCTV7国防军事", "https://tv.cctv.com/live/cctv7/m/index.shtml", "1"},
        {"CCTV8电视剧", "https://tv.cctv.com/live/cctv8/m/index.shtml", "0"},
        {"央视频", "https://m.yangshipin.cn", "0"},
        {"B站", "https://www.bilibili.com", "0"},
        {"优酷", "https://www.youku.com", "0"},
        {"爱奇艺", "https://www.iqiyi.com", "0"},
        {"腾讯视频", "https://v.qq.com", "0"},
        {"抖音", "https://www.douyin.com", "0"},
        {"快手", "https://www.kuaishou.com", "0"},
        {"西瓜视频", "https://www.ixigua.com", "0"},
        {"芒果TV", "https://www.mgtv.com", "0"},
        {"搜狐视频", "https://tv.sohu.com", "0"},
        {"斗鱼直播", "https://www.douyu.com", "0"},
        {"虎牙直播", "https://www.huya.com", "0"},
        {"1905电影网", "https://www.1905.com", "0"},
        {"哔哩哔哩番剧", "https://www.bilibili.com/anime", "0"}
    };
    
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    private static final int WEBVIEW_LOAD_TIMEOUT = 45000; // 低端电视加载页面慢，25秒易误判超时反复重载
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
    private ImageButton btnSwitchMode;
    private ImageButton btnLockOrientation;
    private ImageButton btnOrientation;
    private ImageButton btnChannelMenu;
    private ImageButton btnSettings;
    private ImageButton btnExitApp;
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
    // 直播源自动优选（App 内置检测引擎）
    private SourceScanner activeScanner = null;
    private volatile String scanStatusJson = "{\"running\":false}";
    // 频道菜单数据：分组与测速（与 streamNames/streamUrls 平行）
    private final java.util.List<String> streamGroups = new java.util.ArrayList<>();
    private final java.util.List<Integer> streamSpeeds = new java.util.ArrayList<>();
    private boolean isPlaying = false;
    private boolean isControlVisible = true;
    private Runnable hideControlRunnable;

    // 右上角信息面板
    private TextView tvLunar;
    private TextView tvJieqi;
    private TextView tvShichen;
    private TextView tvDateTime;
    private TextView tvLocation;
    private TextView tvW0Label, tvW0Icon, tvW0Temp;
    private TextView tvW1Label, tvW1Icon, tvW1Temp;
    private TextView tvW2Label, tvW2Icon, tvW2Temp;
    private Handler clockHandler;
    private Runnable clockRunnable;
    private Handler weatherRefreshHandler;
    private Runnable weatherRefreshRunnable;
    private double lastLatitude = 0;
    private double lastLongitude = 0;
    private int lastComputedDay = -1;
    private int lastShichenMinute = -1;
    private String manualLocation = ""; // 手动配置地区（如"广东省深圳市南山区"），非空则不自动定位
    private int errorRetryCount = 0;
    private static final int MAX_RETRY_COUNT = 3;

    private boolean useWebMode = true;
    private boolean isStreamListEnabled = true;
    // 顶部信息横幅设置
    private android.view.View infoOverlay;
    private boolean bannerVisible = true;
    private int bannerFontSize = 16;   // 基准字号 sp
    private int bannerHeight = 28;      // 横幅高度 dp
    private String webSourceUrl = DEFAULT_WEB_SOURCE_URL;
    private String currentVideoUrl = "";
    private String currentVideoName = "";

    private List<String> webSiteUrls = new ArrayList<>();
    private List<String> webSiteNames = new ArrayList<>();
    private List<Boolean> webSiteEnabled = new ArrayList<>();
    private int currentSiteIndex = 0;
    private boolean isOrientationLocked = false;
    
    private ConnectivityManager.NetworkCallback networkCallback;
    private ConnectivityManager connectivityManager;
    private boolean isNetworkAvailable = true;
    private boolean wasNetworkLostWhilePaused = false;
    private long pausedAt = 0;
    
    private ExecutorService executorService;
    
    private int webViewRetryCount = 0;
    private Runnable webViewTimeoutRunnable;
    private boolean isWebViewLoading = false;
    private long webViewLoadStartTime = 0;
    
    private int currentVideoWidth = 0;
    private int currentVideoHeight = 0;
    private boolean isPortraitVideo = false;

    private View customView;
    private FrameLayout customViewContainer;
    private WebChromeClient.CustomViewCallback customViewCallback;
    
    private OrientationEventListener orientationEventListener;
    private int lastDeviceOrientation = Configuration.ORIENTATION_PORTRAIT;
    private boolean isAutoFullscreenEnabled = true;
    private String lastDetectedVideoUrl = "";
    private long lastSniffTime = 0;
    private int sniffRefreshCount = 0;
    private static final int MAX_SNIFF_REFRESH = 2;
    // 嗅探到候选地址后，等待视频真正播放才切换的标志位
    private boolean pendingAutoSwitch = false;
    private String candidateVideoUrl = "";
    private Runnable pendingSwitchTimeoutRunnable = null;
    private boolean isManualOrientationChange = false;
    private long lastManualOrientationTime = 0;

    // WebView视频卡顿检测
    private Runnable webVideoStallRunnable;
    private double lastWebVideoTime = -1;
    private int webVideoStallCount = 0;
    private static final int WEB_STALL_THRESHOLD = 20; // 停滞20秒判定卡顿
    private static final int WEB_STALL_CHECK_INTERVAL = 5000; // 每5秒检查一次
    private static final int STALL_REFRESH_COOLDOWN_MS = 30000; // 刷新冷却30秒，避免连续刷新
    private long lastStallRefreshTime = 0;
    private boolean isWebVideoFullscreenRequested = false;
    private int fullscreenRetryCount = 0; // 网页视频未就绪时的全屏CSS重试次数
    // 刷新后兜底定时器：如果视频长时间未恢复播放，再次刷新
    private Runnable webRefreshFallbackRunnable;
    private static final int WEB_REFRESH_FALLBACK_DELAY_MS = 60000; // 60秒后视频仍未恢复则再次刷新
    private int webRefreshFallbackCount = 0; // 兜底刷新次数，超过上限停止刷新避免无限循环
    private static final int MAX_WEB_REFRESH_FALLBACK = 2;

    // WebView渲染进程恢复与看门狗自续
    private boolean stallCheckPending = false;      // 上一次卡顿查询是否仍未回调
    private int stallHungCount = 0;                 // 渲染进程连续无响应次数
    private int webRecreateCount = 0;               // WebView重建次数（防循环重建）
    private long lastWebRecreateTime = 0;

    // 所有重试耗尽后的深度恢复（静默重取地址），保证不会"一卡到底"
    private Runnable giveUpRecoveryRunnable;
    private int giveUpRecoveryCount = 0;
    private static final int MAX_GIVE_UP_RECOVERY = 8;
    private static final long GIVE_UP_RECOVERY_DELAY_MS = 25000;

    // 换台遮罩：切换频道期间盖住网页加载过程，视频就绪后才显示画面（原生电视换台体验）
    private android.view.View switchOverlay;
    private TextView tvSwitchChannel;
    private TextView tvSwitchHint;
    private Runnable overlayFallbackRunnable;

    // 竖屏播放时视频下方黑边区的"全屏播放"浮层（横屏自动隐藏）
    private android.view.View portraitPlayOverlay;
    private TextView btnFullscreenPlay;

    // 控制面板按钮行（触屏/电视双模式适配）
    private LinearLayout controlButtonsRow;
    private Boolean isTvDevice;

    // 触屏单击检测（手机上点屏幕唤出控制面板）
    private float touchDownX = 0;
    private float touchDownY = 0;
    private long touchDownTime = 0;
    private long lastTouchToggleTime = 0;
    private long lastBackPressTime = 0; // 返回键双击退出计时
    private boolean swipeConsumed = false; // 本次手势已触发滑动换台/切模式

    // 顶部横幅多行结构（竖屏三行：农历行/日期行/定位天气行；横屏单行，额外行隐藏）
    private LinearLayout bannerRow1;
    private LinearLayout bannerRow2;
    private LinearLayout bannerRowTime;
    private LinearLayout bannerLocationWeather;

    // 数据源层key透明重写：等效网页播放器的原生生菜机制——
    // 播放列表403时用静默刷新harvest的最新签名地址透明替换，直播窗口连续，播放器完全无感
    private boolean keyRewriteCapable = false;
    private long lastKeyHarvestTime = 0;
    private static final long KEY_HARVEST_INTERVAL_MS = 150000;
    private static final long CANDIDATE_REWRITE_MAX_AGE_MS = 210000; // key签发后约3.5分钟失效

    // ExoPlayer监听器引用：切换视频源前先移除旧监听器，避免监听器泄漏导致
    // 多个缓冲看门狗并存、用旧URL重新prepare等引起的异常卡顿和刷新
    private Player.Listener currentPlayerListener;

    // 双播放器无缝续播：预载播放器在新实例上缓冲新流，就绪后瞬时接管，
    // 全程旧画面持续播放不冻结（用于auth_key轮换/403恢复的静默切换）
    private ExoPlayer pendingPlayer;
    private Player.Listener pendingPlayerListener;
    private Runnable pendingTimeoutRunnable;
    private static final long PENDING_SWITCH_TIMEOUT_MS = 15000;
    // 预载播放器的哑渲染表面：让解码器提前初始化、视频分辨率提前上报，
    // 换入瞬间PlayerView已知新流宽高比 → 旧画面不会被短暂拉伸
    private android.graphics.SurfaceTexture pendingDummyTexture;
    private android.view.Surface pendingDummySurface;

    // CCTV直播流auth_key有CDN配额（约3.5分钟），需要主动轮换/预取新key避免断流
    private Runnable streamRotateRunnable;
    private static final long STREAM_ROTATE_INTERVAL_MS = 60000; // 60秒检查一次
    private static final long PREFETCH_AFTER_MS = 150000; // 播放超过2.5分钟仍无新key时，提前静默刷新网页预取
    private long currentPlayStartTime = 0; // 当前URL开始播放的时间
    // 各清晰度变体最近嗅探到的候选地址：网页播放器约每60秒轮换一次auth_key
    private final java.util.LinkedHashMap<String, String> candidateByVariant = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<String, Long> candidateTimeByVariant = new java.util.LinkedHashMap<>();
    // 最近失败的auth_key：403后该key会持续失败，5分钟内不再选择
    private final java.util.LinkedHashMap<String, Long> recentlyFailedKeys = new java.util.LinkedHashMap<>();
    private static final long FAILED_KEY_TTL_MS = 300000;
    private static final long CANDIDATE_MAX_AGE_MS = 120000; // mbd变体约60秒刷新一次，120秒窗口保证命中且key仍有效
    // 静默网页刷新标志：403恢复无候选地址时，后台悄悄reload网页拿新key，全程不显示网页/进度条
    private boolean silentRefreshPending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 初始化日志落盘（自动保存+轮转清理）
        LogUtil.init(this);
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
        btnSwitchMode = findViewById(R.id.btn_switch_mode);
        btnLockOrientation = findViewById(R.id.btn_lock_orientation);
        btnOrientation = findViewById(R.id.btn_orientation);
        btnChannelMenu = findViewById(R.id.btn_channel_menu);
        btnSettings = findViewById(R.id.btn_settings);
        btnExitApp = findViewById(R.id.btn_exit_app);
        tvSourceInfo = findViewById(R.id.tv_source_info);
        tvConfigInfo = findViewById(R.id.tv_config_info);
        tvNetworkInfo = findViewById(R.id.tv_network_info);
        tvHintInfo = findViewById(R.id.tv_hint_info);
        controlPanel = findViewById(R.id.control_panel);
        switchOverlay = findViewById(R.id.switch_overlay);
        tvSwitchChannel = findViewById(R.id.tv_switch_channel);
        tvSwitchHint = findViewById(R.id.tv_switch_hint);
        portraitPlayOverlay = findViewById(R.id.portrait_play_overlay);
        btnFullscreenPlay = findViewById(R.id.btn_fullscreen_play);
        controlButtonsRow = findViewById(R.id.control_buttons_row);
        bannerRow1 = findViewById(R.id.banner_row1);
        bannerRow2 = findViewById(R.id.banner_row2);
        bannerRowTime = findViewById(R.id.banner_row_time);
        bannerLocationWeather = findViewById(R.id.banner_location_weather);

        // 初始化右上角信息面板
        infoOverlay = findViewById(R.id.info_overlay);
        tvLunar = findViewById(R.id.tv_lunar);
        tvJieqi = findViewById(R.id.tv_jieqi);
        tvShichen = findViewById(R.id.tv_shichen);
        tvDateTime = findViewById(R.id.tv_date_time);
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
        startWeatherRefresh();

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
        btnSwitchMode.setOnClickListener(v -> switchMode());
        btnLockOrientation.setOnClickListener(v -> toggleOrientationLock());
        btnOrientation.setOnClickListener(v -> toggleScreenOrientation());
        btnChannelMenu.setOnClickListener(v -> showChannelMenu());
        btnSettings.setOnClickListener(v -> showSettingsDialog());
        btnExitApp.setOnClickListener(v -> confirmExit());

        // 电视/触屏双模式适配：
        // 1) 按钮的focusableInTouchMode是为电视遥控器焦点导航设计的，
        //    在触屏手机上会导致"第一次点击=抢焦点、第二次才触发"，必须关闭
        boolean tv = isTelevisionDevice();
        ImageButton[] allButtons = {btnPrevSource, btnNextSource,
            btnSwitchMode, btnOrientation, btnLockOrientation,
            btnChannelMenu, btnSettings, btnExitApp};
        for (ImageButton b : allButtons) {
            if (b != null) b.setFocusableInTouchMode(tv);
        }
        // 2) 操作提示按设备区分
        if (!tv && tvHintInfo != null) {
            tvHintInfo.setText("点屏幕显示/隐藏面板 | 上/下滑切换频道 | ⇄按钮切换模式 | 双击返回键退出");
        }
        // 3) 控制面板窄屏自适应（手机竖屏按钮不超屏）
        applyControlPanelLayout();
        // 4) 竖屏全屏播放按钮
        if (btnFullscreenPlay != null) {
            // 圆角半透明背景
            GradientDrawable fsBg = new GradientDrawable();
            fsBg.setCornerRadius(24 * getResources().getDisplayMetrics().density);
            fsBg.setColor(0xB3000000);
            btnFullscreenPlay.setBackground(fsBg);
            btnFullscreenPlay.setOnClickListener(v -> {
                Toast.makeText(this, "切换到横屏全屏", Toast.LENGTH_SHORT).show();
                toggleScreenOrientation();
            });
        }

        updateLockButtonIcon();
        
        // 手机触屏唤出控制面板统一由dispatchTouchEvent处理：
        // 网页/视频元素会吞掉触摸事件，View.OnClickListener在WebView上不可靠
        
        initNetworkMonitor();
        initOrientationListener();
        loadSavedConfig();
        applyBannerStyle();
        // 横幅尺寸变化（旋转/首帧布局/文字变化）时重新自适应字号
        if (infoOverlay != null) {
            infoOverlay.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or2, ob) -> scheduleBannerAutoFit());
        }
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

    /**
     * 触屏单击唤出/隐藏控制面板（手机端）。
     * 在Activity层分发触摸事件：网页里的video元素会吞掉触摸，View.OnClickListener收不到；
     * 这里只"旁观"不消费事件，触摸仍正常传给WebView/播放器，不影响网页交互。
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                touchDownX = ev.getX();
                touchDownY = ev.getY();
                touchDownTime = SystemClock.uptimeMillis();
                // 手势起点在控制面板/竖屏全屏按钮上时不触发滑动换台（避免误触按钮区）
                swipeConsumed = (controlPanel != null && controlPanel.getVisibility() == View.VISIBLE
                        && isPointInsideView(ev.getRawX(), ev.getRawY(), controlPanel))
                    || (portraitPlayOverlay != null && portraitPlayOverlay.getVisibility() == View.VISIBLE
                        && isPointInsideView(ev.getRawX(), ev.getRawY(), portraitPlayOverlay));
                break;
            case android.view.MotionEvent.ACTION_MOVE: {
                // 滑动手势（触屏设备）：上滑=下一个频道，下滑=上一个频道。
                // 左右滑动不做任何操作（模式切换统一由眼睛按钮），避免误触发
                if (swipeConsumed) break;
                float gdx = ev.getX() - touchDownX;
                float gdy = ev.getY() - touchDownY;
                float threshold = 60 * getResources().getDisplayMetrics().density;
                if (Math.abs(gdy) >= threshold && Math.abs(gdy) > 2 * Math.abs(gdx)) {
                    swipeConsumed = true;
                    swipeChannel(gdy > 0); // 上滑(负)→下一个，下滑(正)→上一个
                }
                break;
            }
            case android.view.MotionEvent.ACTION_UP: {
                long dt = SystemClock.uptimeMillis() - touchDownTime;
                float dx = ev.getX() - touchDownX;
                float dy = ev.getY() - touchDownY;
                // 判定放宽到500ms/30dp：快速点击和轻微滑动偏移都算单击，减少"点了没反应"
                float slop = 30 * getResources().getDisplayMetrics().density;
                boolean isTap = !swipeConsumed && dt < 500 && (dx * dx + dy * dy) <= slop * slop;
                boolean onControlPanel = controlPanel != null
                    && controlPanel.getVisibility() == View.VISIBLE
                    && isPointInsideView(ev.getRawX(), ev.getRawY(), controlPanel);
                boolean onPortraitOverlay = portraitPlayOverlay != null
                    && portraitPlayOverlay.getVisibility() == View.VISIBLE
                    && isPointInsideView(ev.getRawX(), ev.getRawY(), portraitPlayOverlay);
                // 350ms防抖：避免异常双事件导致面板刚显示又被隐藏（"触控失灵"的主因）
                long nowUp = SystemClock.uptimeMillis();
                if (isTap && !onControlPanel && !onPortraitOverlay && nowUp - lastTouchToggleTime > 350) {
                    lastTouchToggleTime = nowUp;
                    toggleControlPanel();
                }
                break;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 滑动换台（对应遥控器上/下键）：网页模式切网站，播放器模式切直播源 */
    private void swipeChannel(boolean up) {
        if (useWebMode) {
            if (up) switchToPrevWebSite(); else switchToNextWebSite();
        } else {
            if (up) switchToPrevSource(); else switchToNextSource();
        }
    }

    /** 判断屏幕坐标是否落在View区域内（用于排除控制面板自身的点击） */
    private boolean isPointInsideView(float rawX, float rawY, View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return rawX >= loc[0] && rawX <= loc[0] + v.getWidth()
            && rawY >= loc[1] && rawY <= loc[1] + v.getHeight();
    }

    /** 是否电视设备（无加速度计、遥控器焦点导航） */
    private boolean isTelevisionDevice() {
        if (isTvDevice == null) {
            android.content.pm.PackageManager pm = getPackageManager();
            isTvDevice = pm != null && (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
                || pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION));
        }
        return isTvDevice;
    }

    /** 控制面板窄屏自适应：手机竖屏时缩小按钮与间距，保证一排放得下（配置地址行始终显示） */
    private void applyControlPanelLayout() {
        if (controlButtonsRow == null) return;
        float density = getResources().getDisplayMetrics().density;
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        boolean narrow = screenWidthDp < 480;
        int count = controlButtonsRow.getChildCount();
        if (count == 0) return;
        // 可用宽度 = 屏宽 - 行左右padding(16dp×2)；按钮间 marginEnd 窄屏4dp / 宽屏12dp
        int marginDp = narrow ? 4 : 12;
        int availDp = screenWidthDp - 32;
        int btnDp = narrow
            ? Math.max(40, (availDp - marginDp * (count - 1)) / count)
            : 72;
        int btnPx = (int) (btnDp * density + 0.5f);
        int padPx = (int) ((narrow ? 6 : 16) * density + 0.5f);
        for (int i = 0; i < count; i++) {
            View cell = controlButtonsRow.getChildAt(i);
            // 单元格 = 垂直LinearLayout（按钮 + 汉字说明标签）
            ViewGroup.LayoutParams clp = cell.getLayoutParams();
            if (clp != null && clp.width != btnPx) {
                clp.width = btnPx;
                clp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                cell.setLayoutParams(clp);
            }
            if (cell instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) cell;
                for (int j = 0; j < vg.getChildCount(); j++) {
                    View child = vg.getChildAt(j);
                    if (child instanceof ImageButton) {
                        ViewGroup.LayoutParams ilp = child.getLayoutParams();
                        ilp.width = btnPx;
                        ilp.height = btnPx;
                        child.setLayoutParams(ilp);
                        ((ImageButton) child).setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
                        child.setPadding(padPx, padPx, padPx, padPx);
                    } else if (child instanceof TextView) {
                        // 说明文字窄屏略微缩小
                        ((TextView) child).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, narrow ? 9f : 11f);
                    }
                }
            }
            ViewGroup.MarginLayoutParams mlp = null;
            if (cell.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                mlp = (ViewGroup.MarginLayoutParams) cell.getLayoutParams();
                int mPx = (int) (marginDp * density + 0.5f);
                if (mlp.rightMargin != mPx) {
                    mlp.rightMargin = mPx;
                    cell.setLayoutParams(mlp);
                }
            }
        }
    }

    /** 竖屏播放时在视频下方黑边区显示"全屏播放"浮层；横屏/控制面板打开/换台中自动隐藏 */
    private void updatePortraitPlayOverlay() {
        if (portraitPlayOverlay == null) return;
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        // 注意：不依赖isPlaying标志（个别路径下可能未被置回true导致按钮"消失"），
        // 播放器容器可见即显示——竖屏+播放器+面板收起+非换台 = 显示全屏按钮
        boolean show = portrait
            && playerContainer != null && playerContainer.getVisibility() == View.VISIBLE
            && (controlPanel == null || controlPanel.getVisibility() != View.VISIBLE)
            && (switchOverlay == null || switchOverlay.getVisibility() != View.VISIBLE);
        portraitPlayOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            // 动态放到视频下方黑边区：视频底边 = 屏高/2 + 屏宽×9/32，黑边高度 = 屏高 - 屏宽×9/16
            View root = portraitPlayOverlay.getRootView();
            float density = getResources().getDisplayMetrics().density;
            int blackBand = Math.max(0, root.getHeight() - Math.round(root.getWidth() * 9f / 16f));
            int mb = Math.max((int) (70 * density), (int) (blackBand * 0.30f));
            FrameLayout.LayoutParams lp = null;
            if (portraitPlayOverlay.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                lp = (FrameLayout.LayoutParams) portraitPlayOverlay.getLayoutParams();
            }
            if (lp != null && Math.abs(lp.bottomMargin - mb) > density * 8) {
                lp.bottomMargin = mb;
                portraitPlayOverlay.setLayoutParams(lp);
            }
        }
    }

    // ==================== 换台遮罩（原生电视换台体验） ====================

    /** 显示换台遮罩：黑色背景+频道名+加载中，盖住网页加载过程 */
    private void showSwitchOverlay(String channelName) {
        if (switchOverlay == null) return;
        if (tvSwitchChannel != null && channelName != null && !channelName.isEmpty()) {
            tvSwitchChannel.setText(channelName);
        }
        if (tvSwitchHint != null) tvSwitchHint.setText("正在加载，请稍候...");
        switchOverlay.setVisibility(View.VISIBLE);
        scheduleOverlayFallbackHide();
    }

    /** 只更新遮罩提示文字（保留频道名） */
    private void showSwitchOverlayHint(String hint) {
        if (tvSwitchHint != null && hint != null) tvSwitchHint.setText(hint);
    }

    /** 遮罩上显示错误提示 */
    private void showSwitchOverlayError(String hint) {
        if (switchOverlay == null) return;
        switchOverlay.setVisibility(View.VISIBLE);
        showSwitchOverlayHint(hint);
        scheduleOverlayFallbackHide();
    }

    /** 收起换台遮罩 */
    private void hideSwitchOverlay() {
        if (overlayFallbackRunnable != null) {
            handler.removeCallbacks(overlayFallbackRunnable);
            overlayFallbackRunnable = null;
        }
        if (switchOverlay != null) switchOverlay.setVisibility(View.GONE);
    }

    /** 兜底：部分页面视频不会自动播放/桥回调不触发，30秒后强制露出页面并显示控制面板，避免遮罩永远盖住内容 */
    private void scheduleOverlayFallbackHide() {
        if (overlayFallbackRunnable != null) {
            handler.removeCallbacks(overlayFallbackRunnable);
        }
        overlayFallbackRunnable = () -> {
            overlayFallbackRunnable = null;
            if (switchOverlay != null && switchOverlay.getVisibility() == View.VISIBLE) {
                LogUtil.w("NewsLive", "Switch overlay fallback reveal after 30s");
                hideSwitchOverlay();
                showControlPanel();
            }
        };
        handler.postDelayed(overlayFallbackRunnable, 30000);
    }

    /** 递归设置View及其所有子View背景为纯黑，消除视频全屏时的白色边框 */
    private void applyBlackBackgroundRecursive(View view) {
        if (view == null) return;
        try {
            view.setBackgroundColor(0xFF000000);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    applyBlackBackgroundRecursive(group.getChildAt(i));
                }
            }
        } catch (Exception e) {
            LogUtil.w("NewsLive", "applyBlackBackgroundRecursive: " + e.getMessage());
        }
    }

    /** 统一清理WebView全屏视图（customView及其黑色容器） */
    private void cleanupCustomView() {
        if (customView != null) {
            FrameLayout rootLayout = findViewById(R.id.root_layout);
            if (customViewContainer != null) {
                customViewContainer.removeView(customView);
                rootLayout.removeView(customViewContainer);
                customViewContainer = null;
            } else {
                rootLayout.removeView(customView);
            }
            customView = null;
        }
        if (customViewCallback != null) {
            try {
                customViewCallback.onCustomViewHidden();
            } catch (Exception e) {
                LogUtil.w("NewsLive", "cleanupCustomView callback: " + e.getMessage());
            }
            customViewCallback = null;
        }
    }

    private void initNetworkMonitor() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        
        NetworkRequest networkRequest = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();
        
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> {
                    boolean wasAvailable = isNetworkAvailable;
                    // 重新检测当前活动网络：onAvailable可能来自次要网络
                    isNetworkAvailable = isNetworkConnected();
                    updateNetworkInfo();
                    if (!wasAvailable && isNetworkAvailable) {
                        onNetworkRestored();
                    }
                });
            }

            @Override
            public void onLost(Network network) {
                runOnUiThread(() -> {
                    // 关键修复：onLost会对任意一条被监测网络触发（如闲置蜂窝断开），
                    // 不能直接全局置为无网络——必须重新检测当前活动网络，
                    // 否则WiFi正常看播时也会误报"无网络"甚至误暂停播放
                    isNetworkAvailable = isNetworkConnected();
                    updateNetworkInfo();
                    if (!isNetworkAvailable) {
                        onNetworkLost();
                    }
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
                } else {
                    // 有活动网络但类型未识别（如正在验证）：不误报"无网络"
                    networkType = "已连接";
                    color = 0xFF43A047;
                }
            } else {
                networkType = "已连接";
                color = 0xFF43A047;
            }
        }

        if (tvNetworkInfo != null) {
            tvNetworkInfo.setText("网络: " + networkType);
            tvNetworkInfo.setTextColor(color);
        }
    }
    
    private void onNetworkLost() {
        Toast.makeText(this, "网络已断开", Toast.LENGTH_SHORT).show();
        wasNetworkLostWhilePaused = true;
        
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
            // 竖屏：只跟随旋转画面，不再切换模式
            // （旧代码的switchToWebMode条件在播放器模式下恒不成立，竖屏看视频本就该保持画面）
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
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
    private void checkVideoPlayingAndSwitch(String sniffedUrl) {
        if (sniffedUrl == null || sniffedUrl.isEmpty()) return;
        // 只在真正隐藏（GONE，即播放器模式）时跳过；INVISIBLE是静默刷新的合法状态
        if (webView == null || webView.getVisibility() == View.GONE) {
            return;
        }
        // 播放器已在播放时不处理，避免干扰
        if (playerContainer != null && playerContainer.getVisibility() == View.VISIBLE) {
            return;
        }
        // 从 video.currentSrc 获取真实地址（视频元素实际使用的地址，比嗅探的更准确）
        // 并检查播放状态：只有视频真正在播放，地址才确定有效
        String js = "(function() {" +
            "try {" +
            "  var video = document.querySelector('video');" +
            "  if (video) {" +
            "    var src = video.currentSrc || video.src || '';" +
            "    if (video.querySelector('source')) {" +
            "      src = video.querySelector('source').src || src;" +
            "    }" +
            "    return JSON.stringify({" +
            "      src: src || ''," +
            "      paused: video.paused," +
            "      currentTime: video.currentTime," +
            "      readyState: video.readyState" +
            "    });" +
            "  }" +
            "  return JSON.stringify({src: '', paused: true});" +
            "} catch(e) { return JSON.stringify({src: '', error: e.message}); }" +
            "})();";
        webView.evaluateJavascript(js, result -> {
            String realUrl = "";
            boolean isPlaying = false;
            try {
                if (result != null && !result.equals("null")) {
                    String jsonStr = result.replace("\\\"", "\"").replaceAll("^\"|\"$", "");
                    JSONObject json = new JSONObject(jsonStr);
                    realUrl = json.optString("src", "");
                    isPlaying = !json.optBoolean("paused", true)
                        && json.optDouble("currentTime", 0) > 0;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            // 优先使用 currentSrc（视频实际使用的地址），为空或blob时回退到嗅探地址
            String finalUrl = (realUrl != null && !realUrl.isEmpty() && !realUrl.startsWith("blob:"))
                ? realUrl : sniffedUrl;
            LogUtil.i("NewsLive", "Video check: isPlaying=" + isPlaying
                + " realUrl=" + realUrl + " sniffedUrl=" + sniffedUrl);

            if (isPlaying) {
                // 视频在播放，地址确定有效，立即切换
                pendingAutoSwitch = false;
                candidateVideoUrl = "";
                lastDetectedVideoUrl = finalUrl;
                switchToPlayerMode(finalUrl);
            } else {
                // 视频未播放，地址可能只是预加载地址，不可信
                // 触发自动播放，并注册一次性 playing 事件监听器，等真正播放后再用 currentSrc 切换
                candidateVideoUrl = sniffedUrl;
                pendingAutoSwitch = true;
                autoClickPlayButton();
                // 注入一次性 playing 监听器，视频开始播放时回调 onVideoPlaying
                String listenerJs = "(function(){" +
                    "if (window.__pendingSwitchListener) return;" +
                    "window.__pendingSwitchListener = true;" +
                    "function attach(video) {" +
                    "  if (!video || video.__pendingListener) return;" +
                    "  video.__pendingListener = true;" +
                    "  var onPlay = function() {" +
                    "    var src = video.currentSrc || video.src || '';" +
                    "    if (video.querySelector('source')) {" +
                    "      src = video.querySelector('source').src || src;" +
                    "    }" +
                    "    if (src && src.indexOf('blob:') === -1 && window.AndroidVideoBridge) {" +
                    "      window.AndroidVideoBridge.onVideoPlaying(src);" +
                    "    }" +
                    "    video.removeEventListener('playing', onPlay, true);" +
                    "    video.removeEventListener('play', onPlay, true);" +
                    "    window.__pendingSwitchListener = false;" +
                    "  };" +
                    "  video.addEventListener('playing', onPlay, true);" +
                    "  video.addEventListener('play', onPlay, true);" +
                    "}" +
                    "var videos = document.querySelectorAll('video');" +
                    "videos.forEach(attach);" +
                    "})();";
                webView.evaluateJavascript(listenerJs, null);

                // 8秒超时：仍未播放则放弃切换，保持 WebView（地址可能无效）
                if (pendingSwitchTimeoutRunnable != null) {
                    handler.removeCallbacks(pendingSwitchTimeoutRunnable);
                }
                pendingSwitchTimeoutRunnable = () -> {
                    if (pendingAutoSwitch && webView != null
                        && webView.getVisibility() == View.VISIBLE) {
                        LogUtil.w("NewsLive",
                            "Video not playing after 8s, keep WebView (url may be invalid): "
                                + candidateVideoUrl);
                    }
                    pendingAutoSwitch = false;
                    candidateVideoUrl = "";
                };
                handler.postDelayed(pendingSwitchTimeoutRunnable, 8000);
            }
        });
    }

    private void switchToPlayerMode(String videoUrl) {
        if (videoUrl == null || videoUrl.isEmpty()) return;
        // 播放器模式下拒绝网页嗅探/JS桥触发的切换（页面已暂停理论上不会触发，防御历史回调）
        if (!useWebMode) {
            LogUtil.w("NewsLive", "switchToPlayerMode skipped: player mode active");
            return;
        }

        // 已在用同一地址播放（含缓冲中）：嗅探与静默刷新双路径同时拿到同一地址时避免重复prepare导致画面重启
        if (player != null && playerContainer != null
                && playerContainer.getVisibility() == View.VISIBLE
                && videoUrl.equals(currentVideoUrl)
                && (player.isPlaying() || player.getPlaybackState() == Player.STATE_BUFFERING)) {
            LogUtil.i("NewsLive", "switchToPlayerMode: already playing same url, skip");
            // 标记/兜底定时器清理：防止silentRefreshPending残留导致后续页面被误静音
            silentRefreshPending = false;
            candidateVideoUrl = "";
            pendingAutoSwitch = false;
            cancelWebRefreshFallback();
            webRefreshFallbackCount = 0;
            return;
        }

        // CCTV的流含cdrm(DRM加密)，ExoPlayer无法解密；kcdnvip域名的流也常解码失败。
        // 这些流交给WebView自带播放器播放（网页有解密逻辑），不切换到ExoPlayer，避免黑屏。
        // 注意：cctvnews.cctv.com（央视新闻直播）的流可以被ExoPlayer正常播放，不在此列。
        String lowerUrl = videoUrl.toLowerCase();
        boolean isCctvStream = lowerUrl.contains("cdrm")
            || lowerUrl.contains("kcdnvip")
            || lowerUrl.contains("cctv.cn")
            || (lowerUrl.contains("cctv") && lowerUrl.contains(".m3u8") && !lowerUrl.contains("cctvnews"));
        if (isCctvStream) {
            LogUtil.i("NewsLive", "skip ExoPlayer for CCTV/DRM stream, keep WebView: " + videoUrl);
            // 确保WebView可见并触发自动播放
            if (webView != null) {
                webView.setVisibility(View.VISIBLE);
                webView.onResume();
                webView.resumeTimers();
                playerContainer.setVisibility(View.GONE);
            }
            autoClickPlayButton();
            isWebVideoFullscreenRequested = false;
            // WebView视频播放后设置isPlaying、隐藏控制面板、触发全屏、启动卡顿检测
            // 检查逻辑支持iframe内的视频（央视新闻直播等页面可能将video放在iframe中）
            handler.postDelayed(() -> {
                checkWebVideoPlayingAndFullscreen(0);
            }, 2000);
            return;
        }

        LogUtil.i("NewsLive", "switchToPlayerMode: " + videoUrl);
        final boolean wasSilentRefresh = silentRefreshPending;
        runOnUiThread(() -> {
            sniffRefreshCount = 0;
            // 暂停WebView的所有活动和播放
            if (webView != null) {
                webView.onPause();
                webView.pauseTimers();
                webView.loadUrl("about:blank");
            }
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
            
            if (wasSilentRefresh) {
                silentRefreshPending = false;
                playVideoUrlSilent(videoUrl, pageName);
            } else {
                playVideoUrl(videoUrl, pageName);
            }
            // 不再强制横屏：方向跟随设备旋转，竖屏持机时视频以黑边居中显示，横过来即全屏
        });
    }

    private void loadSavedConfig() {
        remoteConfigUrl = prefs.getString(KEY_REMOTE_URL, DEFAULT_REMOTE_URL);
        // 旧版 gitee raw 地址下载受限，静默迁移到 release 直链
        if (LEGACY_REMOTE_URL.equals(remoteConfigUrl)) {
            remoteConfigUrl = DEFAULT_REMOTE_URL;
            prefs.edit().putString(KEY_REMOTE_URL, remoteConfigUrl).apply();
        }
        autoUpdateConfig = prefs.getBoolean(KEY_AUTO_UPDATE, false);
        bufferMinMs = prefs.getInt(KEY_BUFFER_MIN, 10000);
        bufferMaxMs = prefs.getInt(KEY_BUFFER_MAX, 60000);
        useWebMode = prefs.getBoolean(KEY_USE_WEB_MODE, true);
        isStreamListEnabled = prefs.getBoolean(KEY_PLAYER_MODE_ENABLED, true);
        bannerVisible = prefs.getBoolean(KEY_BANNER_VISIBLE, true);
        bannerFontSize = prefs.getInt(KEY_BANNER_FONT_SIZE, 16);
        bannerHeight = prefs.getInt(KEY_BANNER_HEIGHT, 28);
        bannerAutoFit = prefs.getBoolean(KEY_BANNER_AUTO_FIT, true);
        webSourceUrl = prefs.getString(KEY_WEB_SOURCE_URL, DEFAULT_WEB_SOURCE_URL);
        isOrientationLocked = prefs.getBoolean(KEY_LOCK_ORIENTATION, false);
        currentSiteIndex = prefs.getInt(KEY_CURRENT_SITE_INDEX, 0);

        loadWebSites();

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
        webSiteEnabled.clear();

        // 检查配置版本号，版本号不匹配时清除旧配置并加载新默认配置
        int savedVersion = prefs.getInt(KEY_WEB_SITES_VERSION, 0);
        boolean needRefresh = (savedVersion < CURRENT_WEB_SITES_VERSION);

        String savedSites = prefs.getString(KEY_WEB_SITES, "");
        if (needRefresh || savedSites.isEmpty()) {
            LogUtil.i("NewsLive", "loadWebSites: refreshing to defaults, savedVersion=" + savedVersion + " currentVersion=" + CURRENT_WEB_SITES_VERSION);
            prefs.edit().remove(KEY_WEB_SITES).putInt(KEY_WEB_SITES_VERSION, CURRENT_WEB_SITES_VERSION).apply();
            currentSiteIndex = 0;
            prefs.edit().putInt(KEY_CURRENT_SITE_INDEX, 0).apply();
            savedSites = "";
        }

        if (!savedSites.isEmpty()) {
            try {
                JSONArray sites = new JSONArray(savedSites);
                for (int i = 0; i < sites.length(); i++) {
                    JSONObject site = sites.getJSONObject(i);
                    webSiteNames.add(site.optString("name", "网站" + (i + 1)));
                    webSiteUrls.add(site.optString("url", ""));
                    webSiteEnabled.add(site.optBoolean("enabled", true));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (webSiteUrls.isEmpty()) {
            for (String[] site : DEFAULT_WEB_SITES) {
                webSiteNames.add(site[0]);
                webSiteUrls.add(site[1]);
                webSiteEnabled.add(!"0".equals(site[2]));
            }
        }

        if (currentSiteIndex < 0 || currentSiteIndex >= webSiteUrls.size()) {
            currentSiteIndex = 0;
        }
        // 确保当前源是启用的，否则跳到第一个启用的源
        if (currentSiteIndex < webSiteEnabled.size() && !webSiteEnabled.get(currentSiteIndex)) {
            for (int i = 0; i < webSiteEnabled.size(); i++) {
                if (webSiteEnabled.get(i)) { currentSiteIndex = i; break; }
            }
        }

        if (!webSiteUrls.isEmpty()) {
            webSourceUrl = webSiteUrls.get(currentSiteIndex);
        }
    }

    private void updatePlayerModeButtons() {
        // 模式切换只保留一个按钮（网页↔播放器）
        if (btnSwitchMode != null) btnSwitchMode.setVisibility(View.VISIBLE);
    }

    // ==================== 顶部横幅多行排版（竖屏独立布局） ====================

    /** 竖屏三行：农历行 / 日期行 / 定位天气行（各占一行，字号保持正常大小）；
     *  横屏单行：日期居中、定位天气在行尾，额外行隐藏 */
    private void relayoutBannerForOrientation() {
        if (bannerRow1 == null || bannerRow2 == null || bannerRowTime == null
                || bannerLocationWeather == null || tvDateTime == null) return;
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        // 日期行独立成行时去掉左右边距（原12dp是给横屏单行准备的），保证与上下两行左对齐
        applyDateTimeMargins(portrait ? 0 : 12);
        // 日期文字对齐：竖屏独立行靠左（原gravity=center会显得居中悬浮），横屏恢复居中
        tvDateTime.setGravity(portrait
            ? Gravity.START | Gravity.CENTER_VERTICAL
            : Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        if (portrait) {
            if (tvDateTime.getParent() != bannerRowTime) {
                bannerRow1.removeView(tvDateTime);
                bannerRowTime.addView(tvDateTime);
            }
            if (bannerLocationWeather.getParent() != bannerRow2) {
                bannerRow1.removeView(bannerLocationWeather);
                bannerRow2.addView(bannerLocationWeather);
            }
            bannerRowTime.setVisibility(View.VISIBLE);
            bannerRow2.setVisibility(View.VISIBLE);
        } else {
            if (tvDateTime.getParent() != bannerRow1) {
                ViewGroup p = (ViewGroup) tvDateTime.getParent();
                if (p != null) p.removeView(tvDateTime);
                bannerRow1.addView(tvDateTime, 1); // 左栏之后
            }
            if (bannerLocationWeather.getParent() != bannerRow1) {
                ViewGroup p = (ViewGroup) bannerLocationWeather.getParent();
                if (p != null) p.removeView(bannerLocationWeather);
                bannerRow1.addView(bannerLocationWeather);
            }
            bannerRowTime.setVisibility(View.GONE);
            bannerRow2.setVisibility(View.GONE);
        }
    }

    /** 设置日期TextView的左右边距（横屏单行时12dp与两侧留白，竖屏独立行时0对齐最左） */
    private void applyDateTimeMargins(int marginDp) {
        if (tvDateTime == null) return;
        ViewGroup.LayoutParams lp = tvDateTime.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            float density = getResources().getDisplayMetrics().density;
            int mPx = (int) (marginDp * density + 0.5f);
            if (mlp.leftMargin != mPx || mlp.rightMargin != mPx) {
                mlp.leftMargin = mPx;
                mlp.rightMargin = mPx;
                tvDateTime.setLayoutParams(mlp);
            }
        }
    }

    /**
     * 仅音频流处理：清掉上一视频频道残留的冻结画面。
     * KeepContentOnPlayerReset为无缝换流保帧设计，但音频流永远不会渲染新帧，
     * 冻结帧会一直挂着，看起来像"画面还在播"。检测到无视频轨时清空表面转黑屏。
     */
    private void clearSurfaceIfAudioOnly() {
        if (playerView == null || player == null) return;
        try {
            androidx.media3.common.Format videoFormat = player.getVideoFormat();
            androidx.media3.common.VideoSize vs = player.getVideoSize();
            boolean audioOnly = (videoFormat == null)
                || (vs != null && vs.width == 0 && vs.height == 0);
            if (!audioOnly) return;
            // 临时关闭保帧 → 重绑播放器清空表面 → 恢复保帧（供下次视频流无缝切换）
            playerView.setKeepContentOnPlayerReset(false);
            playerView.setPlayer(null);
            playerView.setPlayer(player);
            playerView.setKeepContentOnPlayerReset(true);
            LogUtil.i("NewsLive", "audio-only stream: cleared stale video frame");
        } catch (Exception e) {
            LogUtil.w("NewsLive", "clearSurfaceIfAudioOnly: " + e.getMessage());
        }
    }

    // ==================== 右上角时钟 ====================
    // 应用顶部信息横幅样式：可见性、字号、高度
    private void applyBannerStyle() {
        LogUtil.i("NewsLive", "applyBannerStyle: visible=" + bannerVisible + " fontSize=" + bannerFontSize + " height=" + bannerHeight);
        if (infoOverlay == null) return;
        infoOverlay.setVisibility(bannerVisible ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!bannerVisible) return;
        relayoutBannerForOrientation();
        // 高度（dp→px）：竖屏三行
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        float density = getResources().getDisplayMetrics().density;
        int heightPx = (int) (bannerHeight * (portrait ? 3 : 1) * density + 0.5f);
        android.view.ViewGroup.LayoutParams lp = infoOverlay.getLayoutParams();
        if (lp != null && lp.height != heightPx) {
            lp.height = heightPx;
            infoOverlay.setLayoutParams(lp);
        }
        // 所有横幅文本强制单行，杜绝换行
        List<TextView> bannerTexts = new ArrayList<>();
        collectBannerTextViews((ViewGroup) infoOverlay, bannerTexts);
        for (TextView tv : bannerTexts) {
            tv.setSingleLine(true);
        }
        applyBannerFontSizes();
        // 自适应：文字总宽超出横幅宽度时自动缩小字号（手机窄屏不再换行/截断）
        scheduleBannerAutoFit();
    }

    /** 按当前自适应缩放比例设置横幅各文字字号 */
    private void applyBannerFontSizes() {
        // 字号：基准 = bannerFontSize × 自适应比例
        // 主文字=基准；节气/温度=基准-1；标签=基准-2；图标=基准+2
        float base = bannerFontSize * bannerFitScale;
        float sub = Math.max(base - 1, 7);
        float label = Math.max(base - 2, 7);
        float icon = base + 2;
        if (tvLunar != null) tvLunar.setTextSize(base);
        if (tvShichen != null) tvShichen.setTextSize(base);
        if (tvDateTime != null) tvDateTime.setTextSize(base);
        if (tvLocation != null) tvLocation.setTextSize(base);
        if (tvJieqi != null) tvJieqi.setTextSize(sub);
        for (TextView tv : new TextView[]{tvW0Label, tvW1Label, tvW2Label}) {
            if (tv != null) tv.setTextSize(label);
        }
        for (TextView tv : new TextView[]{tvW0Icon, tvW1Icon, tvW2Icon}) {
            if (tv != null) tv.setTextSize(icon);
        }
        for (TextView tv : new TextView[]{tvW0Temp, tvW1Temp, tvW2Temp}) {
            if (tv != null) tv.setTextSize(sub);
        }
    }

    // ==================== 横幅字号自适应（单行不换行） ====================
    private float bannerFitScale = 1f;       // 当前自适应缩放比例（0.35~1.5）
    private boolean bannerFitScheduled = false;
    private boolean bannerAutoFit = true;    // 自动调节字号（尽量放大不截断），默认开启
    private static final float BANNER_MAX_SCALE = 1.5f; // 自动放大上限（16sp基准→24sp封顶）
    private static final String KEY_BANNER_AUTO_FIT = "banner_auto_fit";

    private void scheduleBannerAutoFit() {
        if (handler == null || infoOverlay == null || !bannerVisible) return;
        if (bannerFitScheduled) return;
        bannerFitScheduled = true;
        handler.postDelayed(() -> {
            bannerFitScheduled = false;
            autoFitBannerTexts();
        }, 120);
    }

    /**
     * 横幅自适应总控：竖屏三行 / 横屏单行。
     * 自动模式（默认）：以"不截断为前提尽量放大"求解公共字号比例——公共scale取各行
     * 允许值的最小值，一轮比例求解+硬校验，多行之间不博弈不抖动；
     * 手动模式：尊重用户设定字号，只缩不涨保证不截断。
     */
    private void autoFitBannerTexts() {
        if (infoOverlay == null || !bannerVisible) return;
        if (!(infoOverlay instanceof ViewGroup)) return;
        List<ViewGroup> rows = new ArrayList<>();
        if (bannerRow1 != null && bannerRowTime != null && bannerRow2 != null
                && bannerRowTime.getVisibility() == View.VISIBLE) {
            rows.add(bannerRow1);
            rows.add(bannerRowTime);
            rows.add(bannerRow2);
        } else {
            // 横屏单行：注意必须用 banner_row1 计算（它带12dp行内padding），
            // 用外层infoOverlay会高估可用宽度导致日期被截断（v1.0.6回归）
            rows.add(bannerRow1 != null ? bannerRow1 : (ViewGroup) infoOverlay);
        }
        if (bannerAutoFit) {
            // 第一轮：求各行在当前scale下的"允许scale"，取最小作为公共scale
            float allowed = BANNER_MAX_SCALE;
            boolean measurable = false;
            for (ViewGroup row : rows) {
                int avail = row.getWidth() - row.getPaddingStart() - row.getPaddingEnd();
                if (avail <= 0) {
                    scheduleBannerAutoFit(); // 尚未完成布局，稍后重试
                    return;
                }
                List<TextView> texts = new ArrayList<>();
                collectBannerTextViews(row, texts);
                int total = measureBannerTotal(texts) + sumBannerExtras(row);
                if (total <= 0) continue;
                measurable = true;
                allowed = Math.min(allowed, bannerFitScale * ((float) avail / total) * 0.97f);
            }
            if (!measurable) return;
            allowed = Math.max(0.35f, Math.min(BANNER_MAX_SCALE, allowed));
            // 接近目标(±0.5%)则保持稳定，避免每秒微调抖动
            if (Math.abs(allowed - bannerFitScale) >= 0.005f) {
                bannerFitScale = allowed;
                applyBannerFontSizes();
                // 硬校验：线性近似若有偏差导致任一行仍溢出，整体再缩一档
                for (ViewGroup row : rows) {
                    int avail = row.getWidth() - row.getPaddingStart() - row.getPaddingEnd();
                    List<TextView> texts = new ArrayList<>();
                    collectBannerTextViews(row, texts);
                    int total = measureBannerTotal(texts) + sumBannerExtras(row);
                    if (total > avail) {
                        bannerFitScale = Math.max(0.35f,
                            bannerFitScale * ((float) avail / total) * 0.97f);
                        applyBannerFontSizes();
                    }
                }
            }
        } else {
            for (ViewGroup row : rows) {
                fitBannerRow(row);
            }
        }
    }

    /** 手动模式的单行收敛：超出则按比例缩小并重新测量（只缩不涨，尊重手动字号） */
    private void fitBannerRow(ViewGroup row) {
        int avail = row.getWidth() - row.getPaddingStart() - row.getPaddingEnd();
        if (avail <= 0) {
            scheduleBannerAutoFit(); // 尚未完成布局，稍后重试
            return;
        }
        List<TextView> texts = new ArrayList<>();
        collectBannerTextViews(row, texts);
        int extras = sumBannerExtras(row);
        // 收敛循环：缩到放下为止（最多8轮）
        for (int i = 0; i < 8; i++) {
            int total = measureBannerTotal(texts) + extras;
            if (total <= avail) break;
            float newScale = bannerFitScale * ((float) avail / total);
            newScale = Math.max(0.35f, Math.min(bannerFitScale, newScale)); // 循环内只缩不涨
            if (bannerFitScale - newScale < 0.01f) break; // 已到下限或步长过小
            bannerFitScale = newScale;
            applyBannerFontSizes();
        }
    }

    /** 测量横幅全部文本的理想总宽 */
    private int measureBannerTotal(List<TextView> texts) {
        int desired = 0;
        for (TextView tv : texts) {
            tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            // 渲染余量：emoji（📍定位符/天气图标）等fallback字体的实际绘制宽度
            // 常大于TextView测量宽度，低估会让放大后的文字溢出槽位被相邻视图遮挡
            // （如"上顿渡"的"渡"字被遮一半）。每个文本加4%+1px的修正。
            desired += (int) Math.ceil(tv.getMeasuredWidth() * 1.04) + 1;
        }
        return desired;
    }

    /** 递归收集View树中的所有TextView */
    private void collectBannerTextViews(ViewGroup group, List<TextView> out) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                out.add((TextView) child);
            } else if (child instanceof ViewGroup) {
                collectBannerTextViews((ViewGroup) child, out);
            }
        }
    }

    /** 统计横幅内非文本开销：所有子View水平边距 + 非文本子View宽度（分隔线等） */
    private int sumBannerExtras(ViewGroup group) {
        int sum = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            ViewGroup.LayoutParams clp = child.getLayoutParams();
            if (clp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) clp;
                sum += mlp.leftMargin + mlp.rightMargin;
            }
            if (child instanceof ViewGroup) {
                sum += sumBannerExtras((ViewGroup) child);
            } else if (!(child instanceof TextView)) {
                sum += Math.max(child.getMeasuredWidth(), 1);
            }
        }
        return sum;
    }

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

    // ==================== 天气定时刷新（每30分钟）====================
    private void startWeatherRefresh() {
        // 幂等：先移除旧回调，避免重复创建多个定时器
        if (weatherRefreshHandler == null) {
            weatherRefreshHandler = new Handler(Looper.getMainLooper());
        }
        if (weatherRefreshRunnable == null) {
            weatherRefreshRunnable = new Runnable() {
                @Override
                public void run() {
                    if (lastLatitude != 0 && lastLongitude != 0) {
                        LogUtil.i("NewsLive", "定时刷新天气");
                        fetchWeather(lastLatitude, lastLongitude);
                    }
                    weatherRefreshHandler.postDelayed(this, 30 * 60 * 1000);
                }
            };
        }
        weatherRefreshHandler.removeCallbacks(weatherRefreshRunnable);
        weatherRefreshHandler.postDelayed(weatherRefreshRunnable, 30 * 60 * 1000);
    }

    private void updateClock() {
        if (tvDateTime == null) return;
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int year = cal.get(java.util.Calendar.YEAR);
            int month = cal.get(java.util.Calendar.MONTH) + 1;
            int day = cal.get(java.util.Calendar.DAY_OF_MONTH);
            int weekday = cal.get(java.util.Calendar.DAY_OF_WEEK);
            int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int minute = cal.get(java.util.Calendar.MINUTE);
            int second = cal.get(java.util.Calendar.SECOND);
            String[] weekNames = {"日", "一", "二", "三", "四", "五", "六"};

            // 合并显示：公历年月日 星期 时分秒
            tvDateTime.setText(String.format("%d年%02d月%02d日 周%s %02d:%02d:%02d",
                    year, month, day, weekNames[weekday - 1], hour, minute, second));

            // 时辰：每分钟更新一次（文字宽度变化，需重新自适应字号）
            int currentMinute = hour * 60 + minute;
            if (currentMinute != lastShichenMinute) {
                lastShichenMinute = currentMinute;
                if (tvShichen != null) {
                    tvShichen.setText(ShichenUtil.getShichen(cal));
                    scheduleBannerAutoFit();
                }
            }

            // 日期/农历/节气仅在日期变化时更新
            int todayDay = cal.get(java.util.Calendar.DAY_OF_YEAR);
            if (todayDay != lastComputedDay) {
                lastComputedDay = todayDay;
                updateDateInfo(cal);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 每秒例行校准：竖屏全屏按钮浮层的显示条件若在事件间隙被错过，1秒内自动补上
        updatePortraitPlayOverlay();
    }

    private void updateDateInfo(java.util.Calendar cal) {
        int year = cal.get(java.util.Calendar.YEAR);
        int month = cal.get(java.util.Calendar.MONTH) + 1;
        int day = cal.get(java.util.Calendar.DAY_OF_MONTH);

        LogUtil.i("NewsLive", "updateDateInfo: " + year + "-" + month + "-" + day);

        if (tvLunar != null) {
            try {
                int[] lunar = LunarCalendar.solarToLunar(year, month, day);
                LogUtil.i("NewsLive", "lunar result: year=" + lunar[0] + " month=" + lunar[1] + " day=" + lunar[2] + " isLeap=" + lunar[3]);
                String lunarStr = LunarCalendar.formatLunar(lunar[0], lunar[1], lunar[2], lunar[3] == 1);
                LogUtil.i("NewsLive", "lunar string: " + lunarStr);
                tvLunar.setText(lunarStr);
            } catch (Exception e) {
                LogUtil.e("NewsLive", "lunar calc error", e);
                tvLunar.setText("农历计算错误");
            }
        }

        if (tvJieqi != null) {
            try {
                String jieqi = LunarCalendar.getJieqiInfo(year, month, day);
                LogUtil.i("NewsLive", "jieqi: " + jieqi);
                tvJieqi.setText(jieqi);
                updateJieqiStyle(jieqi.startsWith("今日"));
                scheduleBannerAutoFit();
            } catch (Exception e) {
                LogUtil.e("NewsLive", "jieqi calc error", e);
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
        // 手动配置地区优先：配置了就不再自动定位
        manualLocation = prefs.getString(KEY_MANUAL_LOCATION, "");
        if (manualLocation != null && !manualLocation.trim().isEmpty()) {
            manualLocation = manualLocation.trim();
            tvLocation.setText("📍 " + shortenLocation(manualLocation));
            scheduleBannerAutoFit();
            executorService.execute(() -> {
                double[] coord = geocodeManualLocation(manualLocation);
                if (coord != null) {
                    lastLatitude = coord[0];
                    lastLongitude = coord[1];
                    runOnUiThread(() -> fetchWeather(lastLatitude, lastLongitude));
                } else {
                    LogUtil.w("NewsLive", "manual location unresolved, fallback to IP: " + manualLocation);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "手动地区无法解析，已回退自动定位", Toast.LENGTH_LONG).show();
                        fetchLocationByIP();
                    });
                }
            });
            return;
        }
        // 电视无GPS，直接使用IP定位
        tvLocation.setText("📍 定位中...");
        fetchLocationByIP();
    }

    /**
     * Photon(komoot)地名搜索：基于OSM数据，对中国区县/乡镇/街道收录远好于Open-Meteo，
     * 且国内网络可直连（Nominatim直连不可达，实测HTTP:000超时）。
     * 过滤规则：优先行政区划条目（osm_key=place），跳过同名车站/POI等噪声。
     */
    private double[] tryGeocodePhoton(String query) {
        try {
            String urlStr = "https://photon.komoot.io/api?q=" +
                URLEncoder.encode(query, "UTF-8") + "&limit=10";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "NewsLiveApp/1.0 (weather location config)");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            conn.disconnect();
            JSONObject json = new JSONObject(response.toString());
            JSONArray features = json.optJSONArray("features");
            if (features == null || features.length() == 0) {
                LogUtil.w("NewsLive", "photon no result: " + query);
                return null;
            }
            // 两轮筛选：先找行政区划条目（避免"临川"命中火车站等噪声），找不到再放宽到名称匹配
            JSONObject best = null;
            for (int pass = 0; pass < 2 && best == null; pass++) {
                for (int i = 0; i < features.length(); i++) {
                    JSONObject f = features.getJSONObject(i);
                    JSONObject pr = f.optJSONObject("properties");
                    if (pr == null) continue;
                    if (!"中国".equals(pr.optString("country", ""))) continue;
                    String pname = pr.optString("name", "");
                    if (pname.isEmpty()) continue;
                    boolean administrative = "place".equals(pr.optString("osm_key", ""))
                        || java.util.Arrays.asList("county", "city", "town", "district", "state", "village", "suburb", "quarter")
                            .contains(pr.optString("type", ""));
                    boolean nameMatch = pname.contains(query) || query.contains(pname);
                    if (pass == 0 && !administrative) continue;
                    if (pass == 1 && !nameMatch) continue;
                    best = f;
                    break;
                }
            }
            if (best == null) {
                LogUtil.w("NewsLive", "photon no administrative/name match: " + query);
                return null;
            }
            JSONArray coords = best.getJSONObject("geometry").optJSONArray("coordinates");
            if (coords == null || coords.length() < 2) return null;
            double lon = coords.getDouble(0); // GeoJSON坐标顺序 [lon, lat]
            double lat = coords.getDouble(1);
            LogUtil.i("NewsLive", "photon hit: " + query + " -> " + best.optJSONObject("properties").optString("name")
                + " lat=" + lat + " lon=" + lon);
            return new double[]{lat, lon};
        } catch (Exception e) {
            LogUtil.e("NewsLive", "tryGeocodePhoton(" + query + ") failed: " + e.getMessage());
            return null;
        }
    }

    /** 解析手动输入的地区名为[省,市,区]（如"广东省深圳市南山区"→[广东省,深圳市,南山区]） */
    private String[] parseManualLocation(String name) {
        String pro = "", city = "", district = "";
        java.util.regex.Matcher m;
        m = java.util.regex.Pattern.compile("(北京市|天津市|上海市|重庆市|[^省]{1,8}省|[^自治]{1,10}自治区)").matcher(name);
        if (m.find()) pro = m.group(1);
        m = java.util.regex.Pattern.compile("([^省市自治]{1,10}市)").matcher(name);
        if (m.find()) {
            String c = m.group(1);
            if (!c.equals(pro)) city = c;
        }
        m = java.util.regex.Pattern.compile("([^市省]{1,10}(?:区|县|旗))").matcher(name);
        if (m.find()) district = m.group(1);
        return new String[]{pro, city, district};
    }

    /**
     * 手动地区转坐标（逐级尝试）：区验证→区名→OSM(区+市消歧)→市→OSM全文。
     * Open-Meteo地名库对区县收录稀疏，OSM Nominatim覆盖到乡镇/街道，两者互补。
     * @return [lat, lon]，全部失败返回null（视为无效地区）
     */
    private double[] geocodeManualLocation(String name) {
        String[] parts = parseManualLocation(name);
        String pro = parts[0], city = parts[1], district = parts[2];
        LogUtil.i("NewsLive", "manual location parsed: pro=" + pro + " city=" + city + " district=" + district);
        double[] coord = null;
        if (!district.isEmpty()) {
            coord = tryGeocodeDistrict(district, city, pro);
            if (coord == null) coord = tryGeocodeName(district, 1);
            if (coord == null) coord = tryGeocodePhoton(city.isEmpty() ? district : district + " " + city);
        }
        if (coord == null && !city.isEmpty()) {
            String c = city.endsWith("市") ? city.substring(0, city.length() - 1) : city;
            coord = tryGeocodeName(c, 1);
            if (coord == null) coord = tryGeocodePhoton(c);
        }
        if (coord == null) {
            coord = tryGeocodePhoton(name);
        }
        return coord;
    }

    /** 配置页提交手动地区：先验证（能查到天气坐标才生效），通过后保存并立即刷新定位与天气 */
    private void handleManualLocationUpdate(String newLoc) {
        String cur = prefs.getString(KEY_MANUAL_LOCATION, "");
        if (newLoc == null) newLoc = "";
        newLoc = newLoc.trim();
        if (newLoc.equals(cur)) return;
        if (newLoc.isEmpty()) {
            // 清空=恢复自动IP定位
            prefs.edit().remove(KEY_MANUAL_LOCATION).apply();
            manualLocation = "";
            runOnUiThread(() -> {
                Toast.makeText(this, "已恢复自动IP定位", Toast.LENGTH_SHORT).show();
                requestLocationAndWeather();
            });
            return;
        }
        final String loc = newLoc;
        executorService.execute(() -> {
            double[] coord = geocodeManualLocation(loc);
            if (coord != null) {
                prefs.edit().putString(KEY_MANUAL_LOCATION, loc).apply();
                manualLocation = loc;
                lastLatitude = coord[0];
                lastLongitude = coord[1];
                runOnUiThread(() -> {
                    Toast.makeText(this, "地区已设置: " + loc, Toast.LENGTH_LONG).show();
                    tvLocation.setText("📍 " + shortenLocation(loc));
                    scheduleBannerAutoFit();
                    fetchWeather(lastLatitude, lastLongitude);
                });
            } else {
                LogUtil.w("NewsLive", "manual location invalid (no weather data): " + loc);
                runOnUiThread(() -> Toast.makeText(this,
                    "地区无法识别（查不到对应天气），未保存: " + loc, Toast.LENGTH_LONG).show());
            }
        });
    }

    // IP定位：通过公网IP获取位置（电视无GPS）
    // 主用百度qifu区级定位（免Key，精确到区），备用太平洋电脑网API；
    // 坐标通过Open-Meteo按区名地理编码（验证省市归属防止同名区错配），市级兜底
    private void fetchLocationByIP() {
        executorService.execute(() -> {
            String pro = "", city = "", district = "";
            // 1) 百度qifu区级IP定位（免Key，国内直连）
            String[] q = fetchQifuDistrict();
            if (q != null) {
                pro = q[0]; city = q[1]; district = q[2];
            }
            // 2) pconline兜底（region字段有时含区名）
            if (city.isEmpty()) {
                String[] p = fetchPconlineLocation();
                if (p != null) {
                    pro = p[0]; city = p[1]; district = p[2];
                }
            }
            if (city.isEmpty() && district.isEmpty()) {
                LogUtil.e("NewsLive", "qifu & pconline both failed");
                fetchLocationByIPBackup("");
                return;
            }
            LogUtil.i("NewsLive", "location: pro=" + pro + " city=" + city + " district=" + district);

            // 显示优先级：区 > 市（用户要求至少定位到区）
            String display = !district.isEmpty() ? district : city;
            final String shortName = shortenLocation(display);
            runOnUiThread(() -> {
                tvLocation.setText("📍 " + shortName);
                scheduleBannerAutoFit();
            });

            // 坐标：区级优先（验证归属）→ 市级兜底 → 老链路(ip-api)兜底 → 天气
            geocodeAndFetchWeatherRefined(pro, city, district);
        });
    }

    /** 百度qifu区级IP定位（免Key，国内直连）：返回[省, 市, 区]，失败返回null */
    private String[] fetchQifuDistrict() {
        try {
            URL url = new URL("https://qifu-api.baidubce.com/ip/local/geo/v1/district");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            conn.disconnect();
            JSONObject json = new JSONObject(response.toString());
            if (json.optInt("code", -1) != 0) return null;
            JSONObject data = json.optJSONObject("data");
            if (data == null) return null;
            String prov = data.optString("prov", "");
            String city = data.optString("city", "");
            String district = data.optString("district", "");
            if (prov.isEmpty() && city.isEmpty()) return null;
            LogUtil.i("NewsLive", "qifu: prov=" + prov + " city=" + city + " district=" + district);
            return new String[]{prov, city, district};
        } catch (Exception e) {
            LogUtil.e("NewsLive", "qifu failed: " + e.getMessage());
            return null;
        }
    }

    /** 太平洋电脑网IP定位：返回[省, 市, 区]，失败返回null（原主逻辑提取为备用） */
    private String[] fetchPconlineLocation() {
        try {
            URL url = new URL("https://whois.pconline.com.cn/ipJson.jsp?json=true");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            // pconline返回GBK编码
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "GBK"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            conn.disconnect();
            JSONObject json = new JSONObject(response.toString());
            LogUtil.i("NewsLive", "pconline response: " + response);
            return new String[]{
                json.optString("pro", ""),
                json.optString("city", ""),
                json.optString("region", "")
            };
        } catch (Exception e) {
            LogUtil.e("NewsLive", "pconline failed", e);
            return null;
        }
    }

    /** Open-Meteo地名搜索坐标：返回[lat,lon]，失败返回null */
    private double[] tryGeocodeName(String name, int count) {
        try {
            String urlStr = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                URLEncoder.encode(name, "UTF-8") + "&count=" + count + "&language=zh&format=json";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            conn.disconnect();
            JSONObject json = new JSONObject(response.toString());
            JSONArray results = json.optJSONArray("results");
            if (results == null || results.length() == 0) return null;
            JSONObject first = results.getJSONObject(0);
            return new double[]{first.getDouble("latitude"), first.getDouble("longitude")};
        } catch (Exception e) {
            LogUtil.e("NewsLive", "tryGeocodeName(" + name + ") failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 区名搜索坐标并验证省市归属（全国同名区多，防止错配到其他城市），失败返回null。
     * Open-Meteo结果带admin1(省)/admin2(市)/admin3(区)，逐条比对命中即用。
     */
    private double[] tryGeocodeDistrict(String district, String city, String pro) {
        try {
            String urlStr = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                URLEncoder.encode(district, "UTF-8") + "&count=10&language=zh&format=json";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            conn.disconnect();
            JSONArray results = new JSONObject(response.toString()).optJSONArray("results");
            if (results == null) return null;
            String cityCore = city.replace("市", "");
            String proCore = pro.replace("省", "").replace("市", "").replace("自治区", "");
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                String admin1 = r.optString("admin1", "");
                String admin2 = r.optString("admin2", "");
                String admin3 = r.optString("admin3", "");
                boolean match = (!admin3.isEmpty() && district.contains(admin3))
                    || (!cityCore.isEmpty() && (admin2.contains(cityCore) || admin3.contains(cityCore)))
                    || (!proCore.isEmpty() && admin1.contains(proCore));
                if (match) {
                    double lat = r.getDouble("latitude");
                    double lon = r.getDouble("longitude");
                    LogUtil.i("NewsLive", "district geocoded: " + district + " -> "
                        + admin1 + "/" + admin2 + "/" + admin3 + " lat=" + lat + " lon=" + lon);
                    return new double[]{lat, lon};
                }
            }
            LogUtil.w("NewsLive", "district geocoding no verified match: " + district);
        } catch (Exception e) {
            LogUtil.e("NewsLive", "tryGeocodeDistrict failed: " + e.getMessage());
        }
        return null;
    }

    /** 区级坐标→天气（区名验证定位），市级兜底，全部失败走老链路(ip-api) */
    private void geocodeAndFetchWeatherRefined(String pro, String city, String district) {
        executorService.execute(() -> {
            double lat = 0, lon = 0;
            if (!district.isEmpty()) {
                double[] d = tryGeocodeDistrict(district, city, pro);
                if (d != null) { lat = d[0]; lon = d[1]; }
                if (lat == 0) {
                    // OSM Nominatim兜底：区县/乡镇收录更全
                    d = tryGeocodePhoton(city.isEmpty() ? district : district + " " + city);
                    if (d != null) { lat = d[0]; lon = d[1]; }
                }
            }
            if (lat == 0 && !city.isEmpty()) {
                String cityName = city.endsWith("市") ? city.substring(0, city.length() - 1) : city;
                double[] c = tryGeocodeName(cityName, 1);
                if (c != null) { lat = c[0]; lon = c[1]; }
            }
            if (lat != 0) {
                lastLatitude = lat;
                lastLongitude = lon;
                fetchWeather(lat, lon);
            } else {
                LogUtil.w("NewsLive", "district & city geocoding failed, fallback to ip-api");
                fetchLocationByIPBackup((pro + " " + city + " " + district).trim());
            }
        });
    }

    // 地理编码：城市名转坐标（Open-Meteo Geocoding API，国内可访问，无需Key）
    private void geocodeAndFetchWeather(String cityName, String fallbackDisplayName) {
        executorService.execute(() -> {
            try {
                String urlStr = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(cityName, "UTF-8") + "&count=1&language=zh&format=json";
                LogUtil.i("NewsLive", "geocoding: " + cityName);
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
                LogUtil.i("NewsLive", "geocoding response: " + resp);
                JSONObject json = new JSONObject(resp);
                JSONArray results = json.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject first = results.getJSONObject(0);
                    double lat = first.getDouble("latitude");
                    double lon = first.getDouble("longitude");
                    LogUtil.i("NewsLive", "geocoded: " + cityName + " -> lat=" + lat + " lon=" + lon);
                    lastLatitude = lat;
                    lastLongitude = lon;
                    fetchWeather(lat, lon);
                } else {
                    LogUtil.w("NewsLive", "geocoding no results for: " + cityName);
                    fetchLocationByIPBackup(fallbackDisplayName);
                }
            } catch (Exception e) {
                LogUtil.e("NewsLive", "geocoding failed", e);
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
                LogUtil.i("NewsLive", "ip-api response: " + resp);
                JSONObject json = new JSONObject(resp);

                double lat = json.optDouble("lat", 0);
                double lon = json.optDouble("lon", 0);
                String city = json.optString("city", "");
                String region = json.optString("regionName", "");

                LogUtil.i("NewsLive", "ip-api city=" + city + " region=" + region + " lat=" + lat + " lon=" + lon);

                if (lat != 0 && lon != 0) {
                    lastLatitude = lat;
                    lastLongitude = lon;
                    // 先获取天气（不依赖定位名）
                    fetchWeather(lat, lon);

                    if (preferredCityName != null && !preferredCityName.isEmpty()) {
                        // 优先使用pconline的中文城市名，去掉省份只显示市
                        String finalName = shortenLocation(preferredCityName);
                        runOnUiThread(() -> tvLocation.setText("📍 " + finalName));
                    } else {
                        // 无中文名，用坐标逆地理编码获取中文名
                        LogUtil.i("NewsLive", "reverse geocoding for Chinese name...");
                        fetchCityName(lat, lon, region);
                    }
                } else {
                    runOnUiThread(() -> tvLocation.setText("📍 定位失败"));
                }
            } catch (Exception e) {
                LogUtil.e("NewsLive", "ip-api failed", e);
                // ip-api失败时，若有pconline中文名则尝试地理编码获取坐标
                if (preferredCityName != null && !preferredCityName.isEmpty()) {
                    String geocodeName = preferredCityName;
                    String[] parts = preferredCityName.split(" ");
                    if (parts.length > 0) geocodeName = parts[parts.length - 1];
                    if (geocodeName.endsWith("市")) geocodeName = geocodeName.substring(0, geocodeName.length() - 1);
                    if (geocodeName.endsWith("区")) geocodeName = geocodeName.substring(0, geocodeName.length() - 1);
                    LogUtil.i("NewsLive", "ip-api failed, trying geocoding: " + geocodeName);
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
                LogUtil.i("NewsLive", "nominatim: " + urlStr);
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
                LogUtil.i("NewsLive", "nominatim response: " + resp);
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

                String finalName = shortenLocation(name);
                runOnUiThread(() -> tvLocation.setText("📍 " + finalName));
            } catch (Exception e) {
                LogUtil.e("NewsLive", "nominatim failed", e);
                // 备用：BigDataCloud
                try {
                    LogUtil.i("NewsLive", "trying bigdatacloud reverse geocoding...");
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
                    LogUtil.i("NewsLive", "bigdatacloud response: " + resp);
                    JSONObject json = new JSONObject(resp);
                    String city = json.optString("city", "");
                    String locality = json.optString("locality", "");
                    String subdivision = json.optString("principalSubdivision", "");

                    String name = "";
                    if (!city.isEmpty()) name = city;
                    else if (!locality.isEmpty()) name = locality;
                    else if (!subdivision.isEmpty()) name = subdivision;
                    if (name.isEmpty()) name = fallbackName != null ? fallbackName : String.format("%.4f,%.4f", lat, lon);

                    String finalName = shortenLocation(name);
                    runOnUiThread(() -> tvLocation.setText("📍 " + finalName));
                } catch (Exception e2) {
                    LogUtil.e("NewsLive", "bigdatacloud failed", e2);
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
                LogUtil.i("NewsLive", "fetchWeather: lat=" + lat + " lon=" + lon);
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
                LogUtil.i("NewsLive", "weather response: " + resp);
                JSONObject json = new JSONObject(resp);
                JSONObject daily = json.getJSONObject("daily");
                JSONArray codes = daily.getJSONArray("weathercode");
                JSONArray maxTemps = daily.getJSONArray("temperature_2m_max");
                JSONArray minTemps = daily.getJSONArray("temperature_2m_min");

                final String[] labels = {"今", "明", "后"};
                final String[] icons = new String[3];
                final String[] temps = new String[3];

                for (int i = 0; i < 3; i++) {
                    icons[i] = weatherCodeToIcon(codes.getInt(i));
                    double maxT = maxTemps.getDouble(i);
                    double minT = minTemps.getDouble(i);
                    temps[i] = String.format("%.0f°/%.0f°", maxT, minT);
                }
                LogUtil.i("NewsLive", "weather OK: " + temps[0] + " " + temps[1] + " " + temps[2]);

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
                    scheduleBannerAutoFit();
                });
            } catch (Exception e) {
                LogUtil.e("NewsLive", "fetchWeather failed", e);
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

    // 缩短定位名称用于叠加层显示：优先显示区名（定位到区），无区名时显示城市
    private String shortenLocation(String name) {
        if (name == null || name.isEmpty()) return "未知";
        // 去掉空格分隔的多段
        String[] parts = name.trim().split("\\s+");
        if (parts.length >= 3) {
            // 省 市 区：优先显示区（用户要求至少定位到区）
            String d = parts[2];
            if (d.endsWith("市")) d = d.substring(0, d.length() - 1); // 区位数据可能是县级市
            return d;
        }
        if (parts.length >= 2) {
            // 取第二段（通常是市）
            String city = parts[1];
            // 去掉"市"后缀
            if (city.endsWith("市")) city = city.substring(0, city.length() - 1);
            return city;
        }
        // 单段：保留"区"后缀（本身就是区名），去掉省/市后缀
        String result = name.trim();
        if (result.endsWith("市")) result = result.substring(0, result.length() - 1);
        else if (result.endsWith("省")) result = result.substring(0, result.length() - 1);
        return result;
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
        pendingAutoSwitch = false;
        candidateVideoUrl = "";
        if (pendingSwitchTimeoutRunnable != null) {
            handler.removeCallbacks(pendingSwitchTimeoutRunnable);
            pendingSwitchTimeoutRunnable = null;
        }
        // 向后查找下一个启用的源，最多遍历一圈
        int size = webSiteUrls.size();
        int next = currentSiteIndex;
        for (int i = 0; i < size; i++) {
            next = (next + 1) % size;
            if (next < webSiteEnabled.size() && webSiteEnabled.get(next)) break;
        }
        currentSiteIndex = next;
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
        pendingAutoSwitch = false;
        candidateVideoUrl = "";
        if (pendingSwitchTimeoutRunnable != null) {
            handler.removeCallbacks(pendingSwitchTimeoutRunnable);
            pendingSwitchTimeoutRunnable = null;
        }
        // 向前查找上一个启用的源，最多遍历一圈
        int size = webSiteUrls.size();
        int prev = currentSiteIndex;
        for (int i = 0; i < size; i++) {
            prev = (prev - 1 + size) % size;
            if (prev < webSiteEnabled.size() && webSiteEnabled.get(prev)) break;
        }
        currentSiteIndex = prev;
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
        stopWebVideoStallDetector();
        cancelWebRefreshFallback();
        cancelStreamRotation();
        cancelGiveUpRecovery();
        releasePendingPlayer();
        isPlaying = false;
        updatePortraitPlayOverlay();
        keyRewriteCapable = false;
        isWebVideoFullscreenRequested = false;
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
        pendingAutoSwitch = false;
        candidateVideoUrl = "";
        if (pendingSwitchTimeoutRunnable != null) {
            handler.removeCallbacks(pendingSwitchTimeoutRunnable);
            pendingSwitchTimeoutRunnable = null;
        }
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
        pendingAutoSwitch = false;
        candidateVideoUrl = "";
        if (pendingSwitchTimeoutRunnable != null) {
            handler.removeCallbacks(pendingSwitchTimeoutRunnable);
            pendingSwitchTimeoutRunnable = null;
        }
    }
    
    private void toggleOrientationLock() {
        isOrientationLocked = !isOrientationLocked;
        prefs.edit().putBoolean(KEY_LOCK_ORIENTATION, isOrientationLocked).apply();

        updateLockButtonIcon();

        if (isOrientationLocked) {
            // 关键：锁定不能只挡传感器监听——Manifest是fullSensor，系统自身也会随传感器旋转。
            // 首次启动后若一次方向事件都没发生过，Activity仍处于fullSensor模式，
            // 点锁定后照样会转（"提示已锁定但方向还在变"）。必须立刻显式钉死当前方向。
            int current = getResources().getConfiguration().orientation;
            setRequestedOrientation(current == Configuration.ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            Toast.makeText(this, "已锁定屏幕方向", Toast.LENGTH_SHORT).show();
        } else {
            // 解锁：交还系统传感器，方向跟随恢复
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
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
        streamUrls.add("https://piccpndali.v.myalicdn.com/audio/cctv13_2.m3u8");
        streamNames.add("CCTV13新闻FM（仅音频）");
        streamUrls.add("http://ls.qingting.fm/live/3412131.m3u8?bitrate=64");
        streamNames.add("音乐FM（仅音频）");
    }

    private void parseConfig(JSONObject config) {
        try {
            streamUrls.clear();
            streamNames.clear();
            streamGroups.clear();
            streamSpeeds.clear();
            JSONArray sources = config.getJSONArray("sources");
            for (int i = 0; i < sources.length(); i++) {
                JSONObject source = sources.getJSONObject(i);
                streamUrls.add(source.getString("url"));
                String name = source.optString("name", "源" + (i + 1));
                streamNames.add(name);
                // 分组与测速为可选字段(优选工具/App内置扫描会写入),缺失时按名字归类
                String group = source.optString("group", "");
                if (group.isEmpty()) group = SourceScanner.classify(name);
                streamGroups.add(group);
                int speed = 0;
                Object sp = source.opt("speed");
                if (sp instanceof Number) speed = ((Number) sp).intValue();
                else if (sp instanceof String) { try { speed = Integer.parseInt(((String) sp).trim()); } catch (Exception ignore) {} }
                streamSpeeds.add(speed);
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
                if (i < streamGroups.size()) source.put("group", streamGroups.get(i));
                if (i < streamSpeeds.size() && streamSpeeds.get(i) > 0) source.put("speed", streamSpeeds.get(i));
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

    /** 直播源自动优选：解析 /scan_start 请求并启动内置检测引擎（单例防重入） */
    private String handleScanStart(String request) {
        try {
            String query = request.contains("?") ? request.split("\\?")[1].split(" ")[0] : "";
            String subsParam = "";
            int min = 80, conc = 15;
            for (String kv : query.split("&")) {
                if (kv.startsWith("subs=")) subsParam = java.net.URLDecoder.decode(kv.substring(5), "UTF-8");
                else if (kv.startsWith("min=")) min = Integer.parseInt(kv.substring(4));
                else if (kv.startsWith("conc=")) conc = Integer.parseInt(kv.substring(5));
            }
            String[] subs = subsParam.split("\\|");
            java.util.List<String> valid = new java.util.ArrayList<>();
            for (String s : subs) if (s.startsWith("http")) valid.add(s.trim());
            if (valid.isEmpty()) return "{\"status\":\"error\",\"msg\":\"没有有效订阅URL\"}";
            if (activeScanner != null && activeScanner.isRunning()) {
                return "{\"status\":\"running\"}";
            }
            activeScanner = new SourceScanner();
            scanStatusJson = "{\"running\":true,\"phase\":\"启动\",\"cur\":0,\"total\":0,\"msg\":\"\"}";
            final java.util.List<String> fSubs = valid;
            final int fMin = min, fConc = conc;
            activeScanner.scan(fSubs.toArray(new String[0]), fConc, fMin, new SourceScanner.Callback() {
                @Override
                public void onProgress(String phase, int cur, int total, String msg) {
                    scanStatusJson = "{\"running\":true,\"phase\":\"" + jsonEsc(phase)
                            + "\",\"cur\":" + cur + ",\"total\":" + total
                            + ",\"msg\":\"" + jsonEsc(msg) + "\"}";
                }

                @Override
                public void onDone(String configJson, int channels, int medianKB) {
                    scanStatusJson = "{\"running\":false,\"done\":true,\"channels\":" + channels
                            + ",\"median\":" + medianKB + "}";
                    try {
                        updateConfig(configJson); // 复用现有逻辑：parseConfig + 保存 + 刷新播放
                    } catch (Exception e) {
                        LogUtil.e("scan", "应用优选结果失败: " + e);
                    }
                }

                @Override
                public void onError(String msg) {
                    scanStatusJson = "{\"running\":false,\"error\":\"" + jsonEsc(msg) + "\"}";
                }
            });
            return "{\"status\":\"started\",\"subs\":" + fSubs.size() + "}";
        } catch (Exception e) {
            return "{\"status\":\"error\",\"msg\":\"" + e.getMessage() + "\"}";
        }
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
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

        // 硬件加速渲染（软件渲染在电视上会导致视频画面抖动/闪烁）。
        // 注意用LAYER_TYPE_NONE：WebView自带硬件合成器，外层再套LAYER_TYPE_HARDWARE
        // 会额外占用一整屏离屏缓冲，低端电视内存翻倍，易触发渲染进程被杀导致永久黑屏
        webView.setLayerType(View.LAYER_TYPE_NONE, null);

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
                // 页面加载完成：频道名去掉"加载中"后缀，同步为当前网页频道
                updateWebChannelLabel();
                // 持久化保存cookie（保留登录态）
                CookieManager.getInstance().flush();
                // 静默刷新模式：网页在后台加载，立即静音网页视频，避免与ExoPlayer声音叠加
                if (silentRefreshPending) {
                    view.evaluateJavascript(
                        "(function(){try{var vs=document.querySelectorAll('video');" +
                        "for(var i=0;i<vs.length;i++){vs[i].muted=true;}}catch(e){}})();", null);
                }
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

            // 渲染进程被系统杀掉/崩溃（低端电视内存不足时高发）：
            // 不处理的话WebView永久黑屏且所有JS查询失效，表现为"卡了之后就一直播不了"。
            // 返回true表示已处理，然后重建WebView恢复。
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                LogUtil.e("NewsLive", "WebView renderer process gone! crashed="
                    + (detail != null && detail.didCrash()));
                recreateWebView();
                return true;
            }
            
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false;
                }
                return true;
            }

            // 网络层嗅探：截获 m3u8 / mp4 / flv 直播流请求，自动切到 ExoPlayer 全屏播放
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url != null && url.length() > 0) {
                    String lower = url.toLowerCase();
                    // 屏蔽广告和非必要资源，减少卡顿（保留视频流、页面、JS、CSS）
                    if (lower.contains("admaster") || lower.contains("doubleclick") || lower.contains("googlesyndication")
                        || lower.contains("umeng") || lower.contains("baidustatic")
                        || (lower.endsWith(".gif") && !lower.contains("cctv"))
                        || lower.contains("/ad/") || lower.contains("adserver") || lower.contains("ad delivery")
                        || lower.contains("imasdk") || lower.contains("pubmatic") || lower.contains("rubiconproject")) {
                        return new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream("".getBytes()));
                    }
                    boolean isStream = lower.endsWith(".m3u8") || lower.contains(".m3u8?")
                        || lower.endsWith(".mp4") || lower.contains(".mp4?")
                        || lower.endsWith(".flv") || lower.contains(".flv?");
                    if (isStream) {
                        long now = System.currentTimeMillis();
                        // 防抖：切换频道后允许第一次嗅探，之后10秒内不重复触发（避免master+子流重复）
                        // 注意：这里只记录候选地址，不立即设为 lastDetectedVideoUrl
                        // 因为该地址可能是浏览器预加载发起的（视频暂停时也会请求 m3u8）
                        // 必须在 checkVideoPlayingAndSwitch 中确认视频真正播放后才使用
                        if (lastDetectedVideoUrl.isEmpty() || now - lastSniffTime > 10000) {
                            LogUtil.d("NewsLive", "Sniffed candidate stream: " + url);
                            candidateVideoUrl = url;
                            lastSniffTime = now;
                            // 按清晰度变体记录最新候选地址（供403快速恢复/主动轮换优先选择标清）
                            String variant = extractVariantName(url);
                            if (!variant.isEmpty()) {
                                synchronized (candidateByVariant) {
                                    candidateByVariant.put(variant, url);
                                    candidateTimeByVariant.put(variant, now);
                                }
                            }
                            // 延迟2秒，等视频元素就绪后检查播放状态
                            runOnUiThread(() -> {
                                handler.postDelayed(() -> {
                                    checkVideoPlayingAndSwitch(url);
                                }, 2000);
                            });
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
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
                // 视频元素请求全屏时，尝试用ExoPlayer接管播放直链视频
                if (useWebMode && !isWebVideoFullscreenRequested) {
                    tryExtractAndPlayVideo();
                }

                // 同时支持WebView内全屏播放（适用于blob:视频和DRM流）
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }

                customView = view;
                customViewCallback = callback;

                // 创建专门的全屏容器，背景纯黑，彻底消除白色边框
                customViewContainer = new FrameLayout(MainActivity.this);
                customViewContainer.setBackgroundColor(0xFF000000);

                // 视频View及其所有子View背景设为黑色（SurfaceView需特别处理）
                view.setBackgroundColor(0xFF000000);
                applyBlackBackgroundRecursive(view);

                FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                );
                customViewContainer.addView(view, videoParams);

                FrameLayout rootLayout = findViewById(R.id.root_layout);
                FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                );
                // 插到控制面板下方：全屏视频盖住WebView，但不得盖住控制面板/换台遮罩/顶部横幅。
                // 旧代码addView默认加到最顶层，全屏时顶部横幅被盖住（表现为"横幅消失，
                // 退出全屏又出现"）。层级从底到顶：WebView→播放器→全屏视频→面板→进度→遮罩→横幅
                int insertIndex = rootLayout.indexOfChild(controlPanel);
                if (insertIndex <= 0) insertIndex = rootLayout.getChildCount();
                rootLayout.addView(customViewContainer, insertIndex, containerParams);

                // 隐藏控制面板等其他UI元素，保留顶部信息横幅（时间日期、天气节气）
                if (controlPanel != null) controlPanel.setVisibility(View.GONE);
                if (playerContainer != null) playerContainer.setVisibility(View.GONE);
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (tvHintInfo != null) tvHintInfo.setVisibility(View.GONE);
                // WebView隐藏但仍保持视频播放（全屏View是独立渲染层）
                if (webView != null) webView.setVisibility(View.INVISIBLE);
                hideSystemUI();
            }

            @Override
            public void onHideCustomView() {
                if (customView != null) {
                    cleanupCustomView();
                    // 恢复UI元素
                    if (webView != null) webView.setVisibility(View.VISIBLE);
                    if (infoOverlay != null) infoOverlay.setVisibility(View.VISIBLE);
                    hideSystemUI();
                }
            }
        });
        
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void onVideoPlaying(String videoUrl) {
                if (videoUrl != null && !videoUrl.isEmpty() && !videoUrl.startsWith("blob:")) {
                    lastDetectedVideoUrl = videoUrl;
                    if (silentRefreshPending) {
                        // 静默刷新拿到新地址：由这里完成无感切换。
                        // 旧逻辑直接return（等extractAndPlayVideoWithRetry接管），但其重试窗口
                        // 只有约12秒，网页播放器初始化慢时错过窗口就永远拿不到新地址 → 播放
                        // 数分钟后key过期必卡死。这里主动完成切换。
                        // 关键：保持silentRefreshPending标记不清除，switchToPlayerMode内部
                        // 捕获wasSilentRefresh后走playVideoUrlSilent无感路径并自行清标记。
                        final String silentUrl = videoUrl;
                        runOnUiThread(() -> {
                            if (keyRewriteCapable && player != null && player.isPlaying()) {
                                // 数据源层支持key透明重写：本次刷新只为harvest，画面不动
                                completeSilentRefreshAsHarvest(silentUrl);
                                return;
                            }
                            switchToPlayerMode(silentUrl);
                        });
                        return;
                    }
                    // 横屏自动切全屏；或嗅探后等待播放的标志位为 true 时，视频真正开始播放才切换
                    if (lastDeviceOrientation == Configuration.ORIENTATION_LANDSCAPE
                        || pendingAutoSwitch) {
                        final String finalUrl = videoUrl;
                        runOnUiThread(() -> {
                            // 视频真正播放了，地址确定有效，清除等待状态并切换
                            pendingAutoSwitch = false;
                            candidateVideoUrl = "";
                            if (pendingSwitchTimeoutRunnable != null) {
                                handler.removeCallbacks(pendingSwitchTimeoutRunnable);
                                pendingSwitchTimeoutRunnable = null;
                            }
                            switchToPlayerMode(finalUrl);
                        });
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
        showSwitchOverlayHint("正在重试(" + webViewRetryCount + "/" + WEBVIEW_MAX_RETRY + ")...");

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
        showSwitchOverlayError("网页加载失败，请按上下键切换频道");
    }

    /**
     * 重建WebView：渲染进程崩溃/挂起后的唯一可靠恢复手段。
     * 播放器模式下静默重建（不显示网页，不打扰画面）；网页模式下走loadWebSource带遮罩重载。
     */
    private void recreateWebView() {
        if (isFinishing() || isDestroyed()) return;
        if (handler == null) return;
        handler.post(() -> {
            long now = System.currentTimeMillis();
            if (now - lastWebRecreateTime < 10000) {
                webRecreateCount++;
            } else {
                webRecreateCount = 1;
            }
            lastWebRecreateTime = now;
            if (webRecreateCount > 4) {
                LogUtil.e("NewsLive", "recreateWebView too frequently, stop and show error");
                showSwitchOverlayError("播放器恢复失败，请切换频道");
                return;
            }
            LogUtil.e("NewsLive", "recreateWebView #" + webRecreateCount);
            cancelWebViewTimeoutTimer();
            stopWebVideoStallDetector();
            cancelWebRefreshFallback();
            if (webView == null) return;
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                int index = parent.indexOfChild(webView);
                parent.removeView(webView);
                try {
                    webView.destroy();
                } catch (Exception e) {
                    LogUtil.w("NewsLive", "destroy old webview: " + e.getMessage());
                }
                WebView newView = new WebView(this);
                newView.setId(R.id.web_view);
                newView.setBackgroundColor(0xFF000000);
                parent.addView(newView, Math.max(index, 0),
                    new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                webView = newView;
            }
            // 重新应用全部设置/客户端/JS桥
            initWebView();
            boolean playerMode = playerContainer != null
                && playerContainer.getVisibility() == View.VISIBLE;
            if (playerMode) {
                // 播放器模式：静默重建空白页，后续静默刷新需要新key时再加载真实页面
                webView.setVisibility(View.INVISIBLE);
                webView.loadUrl("about:blank");
                LogUtil.i("NewsLive", "WebView recreated silently in player mode");
            } else {
                webViewRetryCount = 0;
                loadWebSource();
            }
        });
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
            "    if (window.__nlKeepPaused) return;" +
            "    if (video.paused || video.ended) {" +
            "      var p = video.play();" +
            "      if (p && p.catch) p.catch(function(){" +
            "        try { video.muted = true; var p2 = video.play(); if (p2 && p2.catch) p2.catch(function(){}); } catch(e) {}" +
            "      });" +
            "    }" +
            "  } catch(e) {}" +
            "}" +
            "" +
            "function removeCover() {" +
            "  var covers = ['.prism-cover','.vjs-cover','.video-cover','.player-cover','.mask-layer','.bp-overlay','.bpx-player-cover','.bilibili-player-video-cover','.video-mask','.ad-mask','.cover-layer','.player-mask','.vjs-overlay','.poster-layer','.vjs-poster','.bpx-player-cover','.x-player-mask','.player-poster'];" +
            "  covers.forEach(function(sel){" +
            "    try {" +
            "      var els = document.querySelectorAll(sel);" +
            "      els.forEach(function(el){ el.style.display = 'none'; });" +
            "    } catch(e) {}" +
            "  });" +
            "}" +
            "" +
            "function clickPlayButton() {" +
            "  var btns = ['.custom-play-btn','.vjs-big-play-button','.video-play-btn','.player-play-btn','.bilibili-player-video-btn-start','.bpx-player-video-btn-start','[class*=play-btn]','[class*=playButton]','[class*=big-play]','[class*=PlayButton]','button[class*=play]','.vjs-play-control','.jw-icon-playback','.x-play-btn','.player-icon-playback','.art-control-play'];" +
            "  for (var i = 0; i < btns.length; i++) {" +
            "    try {" +
            "      var found = document.querySelectorAll(btns[i]);" +
            "      if (found.length > 0) { found[0].click(); return true; }" +
            "    } catch(e) {}" +
            "  }" +
            "  return false;" +
            "}" +
            "" +
            "function tryAutoplayAll() {" +
            "  removeCover();" +
            "  clickPlayButton();" +
            "  var videos = document.querySelectorAll('video');" +
            "  videos.forEach(function(video){" +
            "    if (video.paused || video.ended) tryAutoPlay(video);" +
            "  });" +
            "}" +
            "" +
            "function handleVideo(video) {" +
            "  if (video.__handled) {" +
            "    if (video.paused) tryAutoPlay(video);" +
            "    return;" +
            "  }" +
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
            "  video.addEventListener('pause', function(e) {" +
            "    setTimeout(function(){ tryAutoPlay(video); removeCover(); clickPlayButton(); }, 300);" +
            "  }, true);" +
            "  video.addEventListener('ended', function(e) {" +
            "    setTimeout(function(){ tryAutoPlay(video); }, 300);" +
            "  }, true);" +
            "  video.addEventListener('webkitbeginfullscreen', function() {" +
            "    if (window.AndroidVideoBridge) window.AndroidVideoBridge.onFullscreenRequested();" +
            "  });" +
            "  video.addEventListener('fullscreenchange', function() {" +
            "    if (document.fullscreenElement && window.AndroidVideoBridge) window.AndroidVideoBridge.onFullscreenRequested();" +
            "  });" +
            "  if (video.readyState >= 2) notifyVideo(video);" +
            "  tryAutoPlay(video);" +
            "  removeCover();" +
            "  clickPlayButton();" +
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
            "  tryAutoplayAll();" +
            "}" +
            "" +
            "checkVideos();" +
            "setInterval(function(){ checkVideos(); }, 800);" +
            "for (var _i = 1; _i <= 10; _i++) { setTimeout(tryAutoplayAll, _i * 600); }" +
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
                            // CCTV的DRM流ExoPlayer无法解密，交给WebView播放器播放
                            // 注意：cctvnews.cctv.com（央视新闻直播）的流可以被ExoPlayer正常播放
                            String lowerVid = videoUrl.toLowerCase();
                            boolean isCctv = lowerVid.contains("cdrm") || lowerVid.contains("kcdnvip")
                                || lowerVid.contains("cctv.cn")
                                || (lowerVid.contains("cctv") && lowerVid.contains(".m3u8") && !lowerVid.contains("cctvnews"));
                            if (isCctv) {
                                LogUtil.i("NewsLive", "extractAndPlay: keep WebView for CCTV/DRM stream: " + videoUrl);
                                autoClickPlayButton();
                                return;
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
                                else if (host.contains("mgtv")) pageName = "芒果TV视频";
                                else if (host.contains("sohu")) pageName = "搜狐视频";
                            }
                            final String finalPageName = pageName;
                            final boolean wasSilentRefresh = silentRefreshPending;
                            runOnUiThread(() -> {
                                // 幂等保护：嗅探/JS桥/本轮询三条链路都会在页面视频就绪时触发切换，
                                // 后到的必须跳过——否则刚播放几秒就会被重复stop+prepare卡一下（每次进入必现）
                                if (player != null && videoUrl.equals(currentVideoUrl)
                                        && (player.isPlaying()
                                            || player.getPlaybackState() == Player.STATE_BUFFERING)) {
                                    LogUtil.i("NewsLive", "extract: same url already playing, skip duplicate switch");
                                    return;
                                }
                                if (wasSilentRefresh && keyRewriteCapable
                                        && player != null && player.isPlaying()) {
                                    // 数据源层支持key透明重写：本次刷新只为harvest，画面不动
                                    completeSilentRefreshAsHarvest(videoUrl);
                                    return;
                                }
                                webView.setVisibility(View.GONE);
                                // 暂停并静音网页视频：释放解码器与内存，避免与ExoPlayer抢资源。
                                // 网页播放器暂停后不再产生新key，需要新key时由预取机制静默刷新网页。
                                webView.evaluateJavascript(
                                    "(function(){try{window.__nlKeepPaused=true;" +
                                    "var vs=document.querySelectorAll('video');" +
                                    "for(var i=0;i<vs.length;i++){vs[i].muted=true;try{vs[i].pause();}catch(e){}}}" +
                                    "catch(e){}})();", null);
                                playerContainer.setVisibility(View.VISIBLE);
                                if (player == null) {
                                    initPlayer();
                                }
                                // 静默网页刷新恢复的流：无感切换，不闪进度条/控制面板
                                if (wasSilentRefresh) {
                                    silentRefreshPending = false;
                                    playVideoUrlSilent(videoUrl, finalPageName);
                                } else {
                                    playVideoUrl(videoUrl, finalPageName);
                                }
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
            // 尝试直接播放video（有声播放，不静音）
            "var video = document.querySelector('video');" +
            "if (video) {" +
            "  if (video.muted) video.muted = false;" +
            "  var p = video.play();" +
            "  if (p && p.catch) p.catch(function(){" +
            "    try { video.muted = true; video.play().catch(function(){}); } catch(e) {}" +
            "  });" +
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
        playVideoUrlWithRetry(url, name, 0, false, false);
    }

    /** 静默切换：无进度条、无Toast，用于403快速恢复/主动轮换/静默刷新接续。
     *  播放器正在播放时走双播放器无缝接管（新流预载就绪后瞬时换入，画面不冻结不跳变）；
     *  否则回退到同播放器重prepare（keepContentOnPlayerReset保最后一帧，非黑屏） */
    private void playVideoUrlSilent(String url, String name) {
        // 幂等保护：嗅探与JS桥双路径可能同时拿到同一新地址，已在播/已在切则忽略
        if (url != null && url.equals(currentVideoUrl)
                && (pendingPlayer != null
                    || (player != null && player.isPlaying()))) {
            LogUtil.i("NewsLive", "playVideoUrlSilent: same url already playing/switching, skip");
            return;
        }
        currentVideoUrl = url;
        currentVideoName = name;
        if (player != null && playerContainer != null
                && playerContainer.getVisibility() == View.VISIBLE
                && (player.isPlaying()
                    || (player.getPlaybackState() == Player.STATE_READY && player.getPlayWhenReady()))) {
            seamlessSwitchTo(url, name);
            return;
        }
        playVideoUrlWithRetry(url, name, 0, false, true);
    }

    /**
     * 双播放器无缝续播：
     * 1. 旧播放器保持播放（画面/声音完全不间断）
     * 2. 新流在第二个ExoPlayer实例上预载：挂哑渲染表面让解码器提前初始化、分辨率提前上报
     * 3. 预载就绪后seek到旧播放器的当前播放位置（直播窗口内分片还在即可精确续上，
     *    分片已过期时ExoPlayer自动钳回直播边缘）
     * 4. 等视频宽高比已知后原子换入（同一消息循环内 setPlayer→挂监听→开播→释放旧播放器），
     *    消除旧版"新画面先拉伸再恢复比例"的视觉跳变
     * 预载失败/超时(15s)自动回退到旧路径，不会比原来更差
     */
    private void seamlessSwitchTo(final String url, final String name) {
        releasePendingPlayer();
        cancelStreamRotation(); // 换流期间旧轮换调度作废，接管完成后重新调度
        final ExoPlayer oldPlayer = player;
        final ExoPlayer p2 = createPlayer();
        pendingPlayer = p2;
        // 哑表面：SurfaceTexture无消费者也能让解码器提前初始化并读出视频格式（分辨率上报前置），
        // 换入瞬间PlayerView已知新流宽高比 → 旧画面不会被短暂拉伸
        try {
            pendingDummyTexture = new android.graphics.SurfaceTexture(0);
            pendingDummySurface = new android.view.Surface(pendingDummyTexture);
            p2.setVideoSurface(pendingDummySurface);
        } catch (Exception e) {
            LogUtil.w("NewsLive", "seamlessSwitch: dummy surface failed, " + e.getMessage());
            releaseDummySurface();
        }
        LogUtil.i("NewsLive", "seamlessSwitch: preload " + url);
        pendingPlayerListener = new Player.Listener() {
            private boolean seeked = false;
            private boolean sizeKnown = false;
            private boolean swapPosted = false;

            /** 换入条件：已seek + 宽高比已知；仅READY不换（避免旧画面被拉伸的视觉跳变） */
            private void maybeSwap() {
                if (swapPosted || !seeked || !sizeKnown) return;
                swapPosted = true;
                handler.post(() -> {
                    if (pendingPlayer != p2) return;
                    pendingPlayer = null;
                    if (pendingPlayerListener != null) {
                        p2.removeListener(pendingPlayerListener);
                        pendingPlayerListener = null;
                    }
                    cancelPendingSwitchTimeout();
                    player = p2;
                    // PlayerView绑定p2：此刻宽高比已知 → 旧画面以正确比例保持，不会拉伸
                    playerView.setPlayer(p2);
                    attachMainListener(url, name, 0, false, true);
                    updateKeyRewriteCapable();
                    isPlaying = true;
                    giveUpRecoveryCount = 0;
                    cancelGiveUpRecovery();
                    hideSwitchOverlay();
                    sniffRefreshCount = 0;
                    currentPlayStartTime = System.currentTimeMillis();
                    scheduleStreamRotation();
                    updatePortraitPlayOverlay();
                    p2.setPlayWhenReady(true);
                    if (oldPlayer != null) {
                        try { oldPlayer.stop(); } catch (Exception e) { }
                        try { oldPlayer.release(); } catch (Exception e) { }
                    }
                    releaseDummySurface(); // 哑表面完成使命
                    LogUtil.i("NewsLive", "seamlessSwitch: took over seamlessly");
                });
            }

            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
                if (pendingPlayer != p2) return;
                if (videoSize.width > 0 && videoSize.height > 0) {
                    sizeKnown = true;
                    maybeSwap();
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (pendingPlayer != p2) return; // 已被新一轮预载/释放取代
                if (state == Player.STATE_READY) {
                    if (!seeked) {
                        seeked = true;
                        // 以"此刻"旧播放器的位置为目标，重播偏差只剩seek缓冲的1~2秒
                        long target = 0;
                        try {
                            target = oldPlayer != null ? Math.max(oldPlayer.getCurrentPosition(), 0) : 0;
                        } catch (Exception e) { /* 旧播放器可能已被释放 */ }
                        p2.seekTo(target);
                        // 兜底：部分设备哑表面下分辨率上报偏晚，2.5秒后不再等待（宁轻微拉伸不长冻结）
                        handler.postDelayed(() -> {
                            if (pendingPlayer != p2 || sizeKnown) return;
                            try {
                                androidx.media3.common.VideoSize vs = p2.getVideoSize();
                                sizeKnown = vs.width > 0;
                            } catch (Exception e) { }
                            sizeKnown = true;
                            maybeSwap();
                        }, 2500);
                        return; // 等seek后的READY
                    }
                    try {
                        androidx.media3.common.VideoSize vs = p2.getVideoSize();
                        sizeKnown = vs.width > 0;
                    } catch (Exception e) { }
                    maybeSwap();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (pendingPlayer != p2) return;
                LogUtil.w("NewsLive", "seamlessSwitch preload failed, fallback: " + error.getMessage());
                releasePendingPlayer();
                playVideoUrlWithRetry(url, name, 0, false, true);
            }
        };
        p2.addListener(pendingPlayerListener);
        p2.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
        p2.prepare();
        // 预载超时兜底：迟迟不就绪则回退原路径（同播放器静默重prepare）
        cancelPendingSwitchTimeout();
        pendingTimeoutRunnable = () -> {
            if (pendingPlayer == p2) {
                LogUtil.w("NewsLive", "seamlessSwitch preload timeout(" + PENDING_SWITCH_TIMEOUT_MS / 1000 + "s), fallback");
                releasePendingPlayer();
                playVideoUrlWithRetry(url, name, 0, false, true);
            }
        };
        handler.postDelayed(pendingTimeoutRunnable, PENDING_SWITCH_TIMEOUT_MS);
    }

    /** 释放哑渲染表面 */
    private void releaseDummySurface() {
        if (pendingDummySurface != null) {
            try { pendingDummySurface.release(); } catch (Exception e) { }
            pendingDummySurface = null;
        }
        if (pendingDummyTexture != null) {
            try { pendingDummyTexture.release(); } catch (Exception e) { }
            pendingDummyTexture = null;
        }
    }

    /** 释放预载播放器（换台/显式换流/退出时调用，避免僵尸预载在后台接管播放） */
    private void releasePendingPlayer() {
        cancelPendingSwitchTimeout();
        releaseDummySurface();
        if (pendingPlayer != null && pendingPlayerListener != null) {
            try { pendingPlayer.removeListener(pendingPlayerListener); } catch (Exception e) { }
        }
        pendingPlayerListener = null;
        if (pendingPlayer != null) {
            try { pendingPlayer.release(); } catch (Exception e) { }
            pendingPlayer = null;
        }
    }

    private void cancelPendingSwitchTimeout() {
        if (pendingTimeoutRunnable != null) {
            handler.removeCallbacks(pendingTimeoutRunnable);
            pendingTimeoutRunnable = null;
        }
    }

    private void playVideoUrlWithRetry(String url, String name, int retryCount, boolean isRefreshed) {
        playVideoUrlWithRetry(url, name, retryCount, isRefreshed, false);
    }

    private void playVideoUrlWithRetry(String url, String name, int retryCount, boolean isRefreshed, boolean silent) {
        if (player == null || url == null || url.isEmpty()) return;

        // 每次换源/重试都先取消上一轮的轮换调度与无缝预载，避免旧调度器/预载器在新播放期间误切换
        releasePendingPlayer();
        cancelStreamRotation();
        updateKeyRewriteCapable();

        LogUtil.i("NewsLive", "playVideoUrl: " + url + " retry=" + retryCount + " refreshed=" + isRefreshed + " silent=" + silent);

        tvSourceInfo.setText(name + (isRefreshed ? " (已刷新)" : ""));
        if (!silent) {
            progressBar.setVisibility(View.VISIBLE);
        }

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
        try {
            if (!silent) {
                player.stop();
            }
            player.setMediaItem(mediaItem);
            player.prepare();
            player.setPlayWhenReady(true);
            LogUtil.i("NewsLive", "prepare() called successfully");
        } catch (Exception e) {
            LogUtil.e("NewsLive", "prepare() failed", e);
        }
        
        attachMainListener(url, name, retryCount, isRefreshed, silent);
    }

    /** 挂载主播放器监听器（缓冲看门狗、403快速恢复、解码降级、暂停恢复） */
    private void attachMainListener(String url, String name, int retryCount, boolean isRefreshed, boolean silent) {
        if (player == null) return;
        if (currentPlayerListener != null) {
            player.removeListener(currentPlayerListener);
            currentPlayerListener = null;
        }
        final int[] bufferingTime = {0};
        final boolean[] hasError = {false};
        final int[] seekRetryCount = {0};
        final int[] pauseRetryCount = {0};
        final boolean finalSilent = silent;
        
        currentPlayerListener = new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                currentVideoWidth = videoSize.width;
                currentVideoHeight = videoSize.height;
                updateVideoLayout(videoSize.width, videoSize.height);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                LogUtil.i("NewsLive", "onPlaybackStateChanged: " + playbackState + " url=" + url);
                switch (playbackState) {
                    case Player.STATE_BUFFERING:
                        // 无缝预载进行中时不弹转圈：旧画面还在播，短暂缓冲不该打扰观看
                        if (!finalSilent && pendingPlayer == null) {
                            progressBar.setVisibility(View.VISIBLE);
                        }
                        bufferingTime[0] = 0;
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING) {
                                    bufferingTime[0]++;
                                    if (bufferingTime[0] % 5 == 0) {
                                        LogUtil.w("NewsLive", "Buffering " + bufferingTime[0] + "s, pos=" + player.getCurrentPosition() + " buffered=" + player.getBufferedPosition());
                                    }
                                    if (bufferingTime[0] > 15) {
                                        if (!isRefreshed && useWebMode && seekRetryCount[0] >= 2) {
                                            if (sniffRefreshCount < MAX_SNIFF_REFRESH) {
                                                sniffRefreshCount++;
                                                if (!finalSilent) {
                                                    Toast.makeText(MainActivity.this, "缓冲超时，正在重新获取视频地址(" + sniffRefreshCount + "/" + MAX_SNIFF_REFRESH + ")...", Toast.LENGTH_SHORT).show();
                                                }
                                                refreshVideoFromWeb(true);
                                            } else {
                                                if (!finalSilent) {
                                                    Toast.makeText(MainActivity.this, "多次重试失败，请尝试切换频道或检查网络", Toast.LENGTH_LONG).show();
                                                }
                                                progressBar.setVisibility(View.GONE);
                                                scheduleGiveUpRecovery();
                                            }
                                    } else if (seekRetryCount[0] < 3) {
                                        seekRetryCount[0]++;
                                        if (!finalSilent) {
                                            Toast.makeText(MainActivity.this, "缓冲超时，重连中(" + seekRetryCount[0] + "/3)...", Toast.LENGTH_SHORT).show();
                                        }
                                        // 直播流 seek 无意义，改为重新 prepare
                                        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
                                        player.prepare();
                                        bufferingTime[0] = 0;
                                    } else if (!useWebMode && streamUrls.size() > 1) {
                                        // 播放器模式：重试无效，自动换下一条线路（优选列表按速度排序，下一条通常更快）
                                        bufferingTime[0] = 0;
                                        Toast.makeText(MainActivity.this, "当前线路持续卡顿，自动切换下一条线路…", Toast.LENGTH_SHORT).show();
                                        switchToNextSource();
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
                        sniffRefreshCount = 0;
                        giveUpRecoveryCount = 0;
                        cancelGiveUpRecovery();
                        hideSwitchOverlay();
                        updatePortraitPlayOverlay();
                        clearSurfaceIfAudioOnly();
                        currentPlayStartTime = System.currentTimeMillis(); // 记录key使用起点
                        // ExoPlayer已恢复播放：取消网页刷新兜底定时器，避免60秒后多余刷新
                        cancelWebRefreshFallback();
                        webRefreshFallbackCount = 0;
                        scheduleStreamRotation();
                        if (!finalSilent) {
                            startHideControlTimer();
                        }
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

                LogUtil.e("NewsLive", "onPlayerError: " + error.getMessage() + " errorCode=" + error.errorCode + " cause=" + (error.getCause() != null ? error.getCause().getMessage() : "null"), error);

                // CCTV直播流403：auth_key配额/有效期耗尽，同一URL重试必然失败。
                // 优先使用网页嗅探到的新地址（新auth_key）直接切换，秒级恢复；
                // 没有可用候选地址时才回退到静默刷新网页重新获取。
                if (isHttp403(error)) {
                    LogUtil.w("NewsLive", "403 detected, try fresh sniffed url first");
                    markKeyFailed(url);
                    String fresh = pickFreshCandidateUrl(url, false);
                    if (fresh != null) {
                        handler.postDelayed(() -> {
                            if (player != null) {
                                playVideoUrlSilent(fresh, name);
                            }
                        }, 500);
                        return;
                    }
                    // 没有新地址：跳过无意义的同URL重试，静默刷新网页重新获取（不打扰观看）
                    if (!isRefreshed && useWebMode && sniffRefreshCount < MAX_SNIFF_REFRESH) {
                        sniffRefreshCount++;
                        LogUtil.w("NewsLive", "No fresh candidate for 403, silent refresh from web");
                        refreshVideoFromWeb(true);
                        return;
                    }
                    if (!finalSilent) {
                        Toast.makeText(MainActivity.this, "直播地址已过期，请尝试切换频道或检查网络", Toast.LENGTH_LONG).show();
                    }
                    scheduleGiveUpRecovery();
                    return;
                }

                // 解码器初始化失败（如高清流内存不足ENOMEM）：直接换标清mbd地址，避免同URL重试浪费
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                    LogUtil.w("NewsLive", "Decoder init failed, try mbd(low-res) url");
                    markKeyFailed(url);
                    String fresh = pickFreshCandidateUrl(url, true);
                    if (fresh != null) {
                        handler.postDelayed(() -> {
                            if (player != null) {
                                playVideoUrlSilent(fresh, name);
                            }
                        }, 500);
                        return;
                    }
                }

                int newRetryCount = retryCount + 1;
                if (newRetryCount <= 3) {
                    final int finalRetryCount = newRetryCount;
                    handler.postDelayed(() -> {
                        if (player != null) {
                            if (finalSilent) {
                                // 静默模式：无Toast无进度条，保持重试计数防止无限循环
                                playVideoUrlWithRetry(url, name, finalRetryCount, isRefreshed, true);
                            } else {
                                Toast.makeText(MainActivity.this, "自动恢复中(" + finalRetryCount + "/3)...", Toast.LENGTH_SHORT).show();
                                playVideoUrlWithRetry(url, name, finalRetryCount, isRefreshed, false);
                            }
                        }
                    }, 2000);
                } else if (!isRefreshed && useWebMode && sniffRefreshCount < MAX_SNIFF_REFRESH) {
                    sniffRefreshCount++;
                    if (!finalSilent) {
                        Toast.makeText(MainActivity.this, "地址可能已过期，正在重新获取(" + sniffRefreshCount + "/" + MAX_SNIFF_REFRESH + ")...", Toast.LENGTH_SHORT).show();
                    }
                    refreshVideoFromWeb(true);
                } else {
                    if (!finalSilent) {
                        Toast.makeText(MainActivity.this, "播放错误，请尝试切换网站或检查网络: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    scheduleGiveUpRecovery();
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
                    } else if (!isRefreshed && useWebMode && sniffRefreshCount < MAX_SNIFF_REFRESH) {
                        sniffRefreshCount++;
                        if (!finalSilent) {
                            Toast.makeText(MainActivity.this, "视频源不稳定，正在重新获取(" + sniffRefreshCount + "/" + MAX_SNIFF_REFRESH + ")...", Toast.LENGTH_SHORT).show();
                        }
                        refreshVideoFromWeb(true);
                    } else {
                        scheduleGiveUpRecovery();
                    }
                }
            }
        };
        player.addListener(currentPlayerListener);
    }
    
    private void updateVideoLayout(int videoWidth, int videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0) return;

        isPortraitVideo = videoHeight > videoWidth;
        // 不再按视频尺寸强制旋转屏幕：旧逻辑每次缓冲/换key轮换后onVideoSizeChanged都会
        // 把屏幕锁回横屏，导致手机竖屏时"画面不跟着变竖屏"。
        // 屏幕方向完全跟随设备物理旋转（onDeviceOrientationChanged）或手动方向键切换。
    }

    /** 判断播放异常是否为HTTP 403（CCTV直播流auth_key配额耗尽） */
    private boolean isHttp403(PlaybackException error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) cause).responseCode == 403;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** 提取URL中的auth_key参数值（CCTV直播流用于防盗链，有使用配额/有效期） */
    private String extractAuthKey(String url) {
        if (url == null) return "";
        int i = url.indexOf("auth_key=");
        if (i < 0) return "";
        String rest = url.substring(i + "auth_key=".length());
        int amp = rest.indexOf('&');
        return amp > 0 ? rest.substring(0, amp) : rest;
    }

    /** 提取URL中的频道标识（如channel_cctv13），用于判断候选地址是否属于当前频道 */
    private String extractChannelName(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("channel_[a-z0-9]+").matcher(url);
        return m.find() ? m.group() : "";
    }

    /** 提取URL中的清晰度变体名（如channel_cctv13_mbd → mbd） */
    private String extractVariantName(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("channel_[a-z0-9]+_(\\w+)").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    /** 去掉URL中的auth_key参数（用于识别"同一播放列表端点、不同签名"的地址） */
    private String stripAuthKey(String url) {
        if (url == null) return "";
        int i = url.indexOf("auth_key=");
        if (i < 0) return url;
        int amp = url.indexOf('&', i);
        return amp > 0 ? url.substring(0, i) + url.substring(amp) : url.substring(0, i);
    }

    /** 找"同一播放列表端点、key不同、且新鲜"的候选地址（主动重写用） */
    private String findFreshCandidateSameEndpoint(String url) {
        String stripped = stripAuthKey(url);
        long now = System.currentTimeMillis();
        synchronized (candidateByVariant) {
            String best = null;
            long bestTime = -1;
            for (java.util.Map.Entry<String, String> e : candidateByVariant.entrySet()) {
                String cand = e.getValue();
                Long t = candidateTimeByVariant.get(e.getKey());
                if (t == null || now - t > CANDIDATE_REWRITE_MAX_AGE_MS) continue;
                if (!stripAuthKey(cand).equals(stripped)) continue;
                if (cand.equals(url)) continue;
                String candKey = extractAuthKey(cand);
                synchronized (recentlyFailedKeys) {
                    if (recentlyFailedKeys.containsKey(candKey)) continue;
                }
                if (t > bestTime) { bestTime = t; best = cand; }
            }
            return best;
        }
    }

    /** 找"同频道（优先同清晰度）、新鲜"的候选地址（403兜底替换播放列表内容用） */
    private String findFreshCandidateSameChannel(String url) {
        String channel = extractChannelName(url);
        String variant = extractVariantName(url);
        long now = System.currentTimeMillis();
        synchronized (candidateByVariant) {
            String best = null;
            long bestTime = -1;
            boolean bestSameVariant = false;
            for (java.util.Map.Entry<String, String> e : candidateByVariant.entrySet()) {
                String cand = e.getValue();
                Long t = candidateTimeByVariant.get(e.getKey());
                if (t == null || now - t > CANDIDATE_REWRITE_MAX_AGE_MS) continue;
                String candCh = extractChannelName(cand);
                if (!channel.isEmpty() && !candCh.equals(channel)) continue;
                String candKey = extractAuthKey(cand);
                if (candKey.isEmpty()) continue;
                synchronized (recentlyFailedKeys) {
                    if (recentlyFailedKeys.containsKey(candKey)) continue;
                }
                boolean sameVariant = !variant.isEmpty() && extractVariantName(cand).equals(variant);
                if (best == null || (sameVariant && !bestSameVariant)
                        || (sameVariant == bestSameVariant && t > bestTime)) {
                    best = cand;
                    bestTime = t;
                    bestSameVariant = sameVariant;
                }
            }
            return best;
        }
    }

    /**
     * 包装Http数据源：直播流auth_key过期(403)时，透明改用静默刷新harvest的最新签名地址。
     * 播放列表内容是同一直播窗口（media sequence连续），播放器解析后无缝续播——
     * 不重建播放器、不重启流，等效网页播放器在页面内换key的原生机制。
     * 主动模式：每次播放列表重取时直接改写到最新签名地址（避免每次都吃一次403）；
     * 被动模式：403后取同频道最新候选，把其内容作为本次请求的响应返回。
     */
    private class KeyRewritingDataSource implements androidx.media3.datasource.DataSource {
        private final DefaultHttpDataSource.Factory factory;
        private final androidx.media3.datasource.DataSource base;
        private androidx.media3.datasource.DataSource active;
        private final java.util.List<androidx.media3.datasource.TransferListener> listeners =
            new java.util.ArrayList<>();

        KeyRewritingDataSource(DefaultHttpDataSource.Factory factory) {
            this.factory = factory;
            this.base = factory.createDataSource();
        }

        @Override
        public void addTransferListener(androidx.media3.datasource.TransferListener transferListener) {
            listeners.add(transferListener);
            base.addTransferListener(transferListener);
        }

        @Override
        public long open(androidx.media3.datasource.DataSpec dataSpec) throws IOException {
            active = base;
            String url = dataSpec.uri.toString();
            boolean isPlaylist = url.toLowerCase().contains(".m3u8");
            if (isPlaylist && url.contains("auth_key=")) {
                // 主动重写：直接用最新签名地址取播放列表（省一次403往返）
                String rewritten = findFreshCandidateSameEndpoint(url);
                if (rewritten != null) {
                    try {
                        return base.open(dataSpec.buildUpon().setUri(Uri.parse(rewritten)).build());
                    } catch (IOException e) {
                        LogUtil.w("NewsLive", "KeyRewrite: proactive rewrite failed, fallback to original");
                        try { base.close(); } catch (Exception ignore) { }
                    }
                }
            }
            try {
                return base.open(dataSpec);
            } catch (HttpDataSource.InvalidResponseCodeException e) {
                if (e.responseCode != 403 || !isPlaylist || !url.contains("auth_key=")) throw e;
                // 被动兜底：用同频道最新候选地址的内容作为本次响应（直播窗口连续 → 无感续播）
                String candidate = findFreshCandidateSameChannel(url);
                if (candidate == null || candidate.equals(url)) throw e;
                try { base.close(); } catch (Exception ignore) { }
                androidx.media3.datasource.DataSource tmp = factory.createDataSource();
                for (androidx.media3.datasource.TransferListener l : listeners) {
                    tmp.addTransferListener(l);
                }
                long len = tmp.open(dataSpec.buildUpon().setUri(Uri.parse(candidate)).build());
                active = tmp;
                LogUtil.i("NewsLive", "KeyRewrite: playlist 403 -> seamless swap to fresh key");
                return len;
            }
        }

        @Override
        public Uri getUri() {
            return active != null ? active.getUri() : null;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return active != null ? active.read(buffer, offset, length) : -1;
        }

        @Override
        public void close() throws IOException {
            if (active != null) {
                active.close();
                if (active != base) {
                    try { base.close(); } catch (Exception ignore) { }
                }
            }
            active = null;
        }

        @Override
        public java.util.Map<String, java.util.List<String>> getResponseHeaders() {
            return active != null ? active.getResponseHeaders() : null;
        }
    }

    /**
     * 从嗅探缓存中挑一个"新鲜"的候选直播地址：
     * - 非DRM直链流（.m3u8/.mp4/.flv/.ts）
     * - 与当前地址同频道（防止误切到广告/其他视频）
     * - auth_key与当前不同（当前key已403失效时才值得切换）
     * - 最近120秒内嗅探到（过旧说明网页播放器已停止刷新，切过去大概率也已失效）
     * - auth_key不在最近失败名单中（403后的key会持续失败）
     * - 优先标清mbd（解码内存占用小）
     * @param requireMbd true=只接受mbd变体（解码降级用）
     */
    private String pickFreshCandidateUrl(String currentUrl, boolean requireMbd) {
        long now = System.currentTimeMillis();
        // 清理过期失败记录
        synchronized (recentlyFailedKeys) {
            java.util.Iterator<java.util.Map.Entry<String, Long>> it = recentlyFailedKeys.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > FAILED_KEY_TTL_MS) it.remove();
            }
        }
        String curCh = extractChannelName(currentUrl);
        String curKey = extractAuthKey(currentUrl);
        String fallback = null;
        synchronized (candidateByVariant) {
            // 第一轮：优先与当前相同的清晰度变体（key轮换不掉画质），其次标清mbd
            java.util.LinkedHashSet<String> variantOrder = new java.util.LinkedHashSet<>();
            String preferredVariant = extractVariantName(currentUrl);
            if (!preferredVariant.isEmpty()) variantOrder.add(preferredVariant);
            java.util.Collections.addAll(variantOrder, "mbd", "mhd", "mud", "md", "hd");
            for (String variant : variantOrder) {
                if (requireMbd && !variant.equals("mbd")) continue;
                String cand = candidateByVariant.get(variant);
                Long t = candidateTimeByVariant.get(variant);
                if (cand == null || t == null) continue;
                if (now - t > CANDIDATE_MAX_AGE_MS) continue;
                if (cand.equals(currentUrl)) continue;
                if (isDrmStream(cand)) continue;
                String candCh = extractChannelName(cand);
                if (!curCh.isEmpty() && !candCh.isEmpty() && !curCh.equals(candCh)) continue;
                String candKey = extractAuthKey(cand);
                if (candKey.isEmpty() || candKey.equals(curKey)) continue;
                boolean failed;
                synchronized (recentlyFailedKeys) {
                    failed = recentlyFailedKeys.containsKey(candKey);
                }
                if (failed) continue;
                if (variant.equals("mbd")) return cand; // 标清可用则直接返回
                if (fallback == null) fallback = cand;
            }
            // 第二轮：任意其他变体
            if (fallback == null && !requireMbd) {
                for (java.util.Map.Entry<String, String> e : candidateByVariant.entrySet()) {
                    String cand = e.getValue();
                    Long t = candidateTimeByVariant.get(e.getKey());
                    if (t == null || now - t > CANDIDATE_MAX_AGE_MS) continue;
                    if (cand.equals(currentUrl)) continue;
                    if (isDrmStream(cand)) continue;
                    String candCh = extractChannelName(cand);
                    if (!curCh.isEmpty() && !candCh.isEmpty() && !curCh.equals(candCh)) continue;
                    String candKey = extractAuthKey(cand);
                    if (candKey.isEmpty() || candKey.equals(curKey)) continue;
                    boolean failed;
                    synchronized (recentlyFailedKeys) {
                        failed = recentlyFailedKeys.containsKey(candKey);
                    }
                    if (failed) continue;
                    fallback = cand;
                    break;
                }
            }
        }
        return fallback;
    }

    /** 记录一个auth_key为失败（403等），5分钟内不再选用 */
    private void markKeyFailed(String url) {
        String key = extractAuthKey(url);
        if (key.isEmpty()) return;
        synchronized (recentlyFailedKeys) {
            recentlyFailedKeys.put(key, System.currentTimeMillis());
        }
    }

    /** 更新key透明重写能力标记：仅网页模式的带auth_key直播流支持 */
    private void updateKeyRewriteCapable() {
        keyRewriteCapable = useWebMode && currentVideoUrl != null && currentVideoUrl.contains("auth_key=");
    }

    /**
     * 静默刷新完成但当前播放已支持key透明重写：只入库新key，不切换播放地址。
     * 播放画面完全不动；网页视频静音暂停，WebView留在底层供下次harvest复用。
     */
    private void completeSilentRefreshAsHarvest(String videoUrl) {
        silentRefreshPending = false;
        cancelWebRefreshFallback();
        webRefreshFallbackCount = 0;
        lastDetectedVideoUrl = videoUrl;
        LogUtil.i("NewsLive", "Silent refresh: keys harvested, playback continues seamlessly");
        // 静音并暂停网页视频，释放解码资源
        webView.evaluateJavascript(
            "(function(){try{window.__nlKeepPaused=true;" +
            "var vs=document.querySelectorAll('video');" +
            "for(var i=0;i<vs.length;i++){vs[i].muted=true;try{vs[i].pause();}catch(e){}}}" +
            "catch(e){}})();", null);
        webView.onPause();
        webView.pauseTimers();
    }

    /** 主动轮换（key养护）：key过期由数据源层透明重写（KeyRewritingDataSource）处理，
     *  不再切换播放器实例；这里只负责定期通过静默刷新网页harvest新的签名key备胎 */
    private void scheduleStreamRotation() {
        cancelStreamRotation();
        final Runnable[] holder = new Runnable[1];
        holder[0] = () -> {
            if (holder[0] != streamRotateRunnable) return; // 已被新的调度取代
            if (player == null || playerContainer.getVisibility() != View.VISIBLE || !useWebMode) {
                return; // 播放器已退出，停止轮换
            }
            if (player.isPlaying() && player.getPlaybackState() == Player.STATE_READY
                    && keyRewriteCapable && currentPlayStartTime > 0
                    && System.currentTimeMillis() - currentPlayStartTime > PREFETCH_AFTER_MS
                    && System.currentTimeMillis() - lastKeyHarvestTime > KEY_HARVEST_INTERVAL_MS) {
                lastKeyHarvestTime = System.currentTimeMillis();
                LogUtil.i("NewsLive", "Rotation: harvest fresh keys via silent web refresh");
                refreshVideoFromWeb(true);
            }
            // 始终自续期（harvest完成后由本循环按间隔继续巡检）
            handler.postDelayed(holder[0], STREAM_ROTATE_INTERVAL_MS);
        };
        streamRotateRunnable = holder[0];
        handler.postDelayed(streamRotateRunnable, STREAM_ROTATE_INTERVAL_MS);
    }

    private void cancelStreamRotation() {
        if (streamRotateRunnable != null) {
            handler.removeCallbacks(streamRotateRunnable);
            streamRotateRunnable = null;
        }
    }

    /**
     * 深度恢复兜底：所有重试/刷新链路都放弃后，定期静默重取直播地址。
     * 旧逻辑在重试耗尽后只弹Toast就永久放弃，表现为"卡了就一直播不了"。
     * 每次触发后检查播放状态：仍无播放则静默刷新网页重取地址，并继续守护直到恢复。
     */
    private void scheduleGiveUpRecovery() {
        cancelGiveUpRecovery();
        if (giveUpRecoveryCount >= MAX_GIVE_UP_RECOVERY) {
            LogUtil.e("NewsLive", "Deep recovery exhausted (" + MAX_GIVE_UP_RECOVERY + "), give up");
            showSwitchOverlayError("播放失败，请按上下键切换频道");
            return;
        }
        giveUpRecoveryRunnable = () -> {
            giveUpRecoveryRunnable = null;
            giveUpRecoveryCount++;
            if (player == null || playerContainer == null
                    || playerContainer.getVisibility() != View.VISIBLE) {
                return; // 已不在播放器模式，交给其他链路处理
            }
            boolean alive = player.isPlaying()
                || (player.getPlaybackState() == Player.STATE_READY && player.getPlayWhenReady());
            if (alive) {
                giveUpRecoveryCount = 0; // 已被其他链路救活，重置计数
                return;
            }
            LogUtil.w("NewsLive", "Deep recovery attempt " + giveUpRecoveryCount
                + "/" + MAX_GIVE_UP_RECOVERY);
            sniffRefreshCount = 0;
            refreshVideoFromWeb(true);
            scheduleGiveUpRecovery(); // 继续守护，直到恢复播放
        };
        handler.postDelayed(giveUpRecoveryRunnable, GIVE_UP_RECOVERY_DELAY_MS);
    }

    private void cancelGiveUpRecovery() {
        if (giveUpRecoveryRunnable != null) {
            handler.removeCallbacks(giveUpRecoveryRunnable);
            giveUpRecoveryRunnable = null;
        }
    }

    private void refreshVideoFromWeb() {
        refreshVideoFromWeb(false);
    }

    /**
     * 重新从网页获取视频地址。
     * @param silent true=静默刷新：WebView保持在底层隐藏加载，播放器画面不切换，
     *               不显示网页/进度条/提示，拿到新地址后无感切回ExoPlayer（用于403自动恢复）
     */
    private void refreshVideoFromWeb(boolean silent) {
        // 播放器模式下拒绝一切网页刷新：网页模式的定时器（换台兜底/超时重试等）可能
        // 在切到播放器模式后到期误触发，把画面切到"正在加载"的网页且盖掉播放器画面
        if (!useWebMode) {
            LogUtil.w("NewsLive", "refreshVideoFromWeb skipped: player mode active");
            return;
        }
        stopWebVideoStallDetector();
        cancelStreamRotation();
        isWebVideoFullscreenRequested = false;
        fullscreenRetryCount = 0;
        // 设置冷却时间：刷新后视频需要加载，30秒内不触发卡顿刷新
        lastStallRefreshTime = System.currentTimeMillis();

        if (silent) {
            // 静默刷新：WebView留在底层（playerContainer盖在上面），不打扰观看。
            // 注意：不能调loadWebSource()（它会切WebView可见/隐藏播放器导致闪屏），直接loadUrl
            silentRefreshPending = true;
            webViewRetryCount = 0;
            // 关键修复：切到播放器模式时WebView被onPause/pauseTimers挂起，页面JS完全停摆，
            // 不恢复定时器的话视频永远播不起来，静默刷新拿不到新地址 → 播放一段时间后必卡死
            webView.setVisibility(View.INVISIBLE);
            webView.onResume();
            webView.resumeTimers();
            if (webView.getUrl() == null || !webView.getUrl().equals(webSourceUrl)) {
                webView.loadUrl(webSourceUrl);
            } else {
                webView.reload();
            }
            // 静默兜底定时器：60秒后仍未拿到新地址则再试（不弹Toast）
            cancelWebRefreshFallback();
            if (webRefreshFallbackCount < MAX_WEB_REFRESH_FALLBACK) {
                webRefreshFallbackCount++;
                webRefreshFallbackRunnable = () -> {
                    LogUtil.w("NewsLive", "Silent refresh not recovered 60s, retrying");
                    refreshVideoFromWeb(true);
                };
                handler.postDelayed(webRefreshFallbackRunnable, WEB_REFRESH_FALLBACK_DELAY_MS);
            }
            return;
        }

        webView.setVisibility(View.VISIBLE);
        playerContainer.setVisibility(View.GONE);
        tvSourceInfo.setText("正在重新获取视频地址...");
        progressBar.setVisibility(View.VISIBLE);
        showSwitchOverlay(webSiteNames.isEmpty() ? currentVideoName
            : webSiteNames.get(currentSiteIndex));

        webViewRetryCount = 0;

        if (webView.getUrl() == null || !webView.getUrl().equals(webSourceUrl)) {
            loadWebSource();
        } else {
            webView.reload();
        }

        // 兜底定时器：如果60秒后视频仍未恢复播放（onWebVideoPlaying未被调用），再次刷新
        startWebRefreshFallback();
    }

    /** 启动刷新后兜底定时器：视频长时间未恢复播放时再次刷新（最多 MAX_WEB_REFRESH_FALLBACK 次，避免无限刷新） */
    private void startWebRefreshFallback() {
        cancelWebRefreshFallback();
        if (webRefreshFallbackCount >= MAX_WEB_REFRESH_FALLBACK) {
            LogUtil.w("NewsLive", "refresh fallback reached max(" + MAX_WEB_REFRESH_FALLBACK + "), stop retrying");
            return;
        }
        webRefreshFallbackCount++;
        final int attempt = webRefreshFallbackCount;
        webRefreshFallbackRunnable = () -> {
            LogUtil.w("NewsLive", "Video not recovered 60s after refresh, retrying (" + attempt + "/" + MAX_WEB_REFRESH_FALLBACK + ")");
            Toast.makeText(MainActivity.this, "视频未恢复，重新加载...", Toast.LENGTH_SHORT).show();
            refreshVideoFromWeb();
        };
        handler.postDelayed(webRefreshFallbackRunnable, WEB_REFRESH_FALLBACK_DELAY_MS);
    }

    /** 取消刷新后兜底定时器 */
    private void cancelWebRefreshFallback() {
        if (webRefreshFallbackRunnable != null) {
            handler.removeCallbacks(webRefreshFallbackRunnable);
            webRefreshFallbackRunnable = null;
        }
    }

    /** 检查WebView视频是否正在播放（支持iframe内的video），播放后触发全屏
     *  retryIndex: 重试次数，最多5次，每次间隔2秒 */
    private void checkWebVideoPlayingAndFullscreen(int retryIndex) {
        if (webView == null) return;
        // 播放器模式下不轮询（WebView已让位给ExoPlayer，轮询会干扰播放）
        if (playerContainer != null && playerContainer.getVisibility() == View.VISIBLE) return;
        // 检查主document和同源iframe内的video播放状态
        String checkJs = "(function(){try{" +
            "function checkDoc(doc){try{var v=doc.querySelector('video');if(v&&!v.paused&&v.currentTime>0)return 'playing';}catch(e){}return '';}" +
            // 先查主文档
            "var r=checkDoc(document);if(r)return r;" +
            // 再查同源iframe
            "var iframes=document.querySelectorAll('iframe');" +
            "for(var i=0;i<iframes.length;i++){try{if(iframes[i].contentDocument){r=checkDoc(iframes[i].contentDocument);if(r)return r;}}catch(e){}}" +
            "return 'not-playing';}catch(e){return 'error';}})();";
        webView.evaluateJavascript(checkJs, r -> {
            if (r != null && r.contains("playing")) {
                onWebVideoPlaying();
            } else if (retryIndex < 5) {
                // 2秒后重试，最多6次（共12秒）
                handler.postDelayed(() -> checkWebVideoPlayingAndFullscreen(retryIndex + 1), 2000);
            }
        });
    }

    /** WebView视频开始播放后的统一处理：隐藏控制面板（保留顶部信息条）、触发全屏、启动卡顿检测 */
    private void onWebVideoPlaying() {
        isPlaying = true;
        // 视频已恢复播放，取消刷新兜底定时器并重置计数
        webRefreshFallbackCount = 0;
        cancelWebRefreshFallback();
        startHideControlTimer();
        // 仅隐藏控制面板和进度条，保留顶部信息横幅（时间日期、天气节气）
        if (controlPanel != null) controlPanel.setVisibility(View.GONE);
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        // 设置WebView背景为黑色
        if (webView != null) webView.setBackgroundColor(0xFF000000);
        LogUtil.i("NewsLive", "WebView video playing, isPlaying=true, hide control panel, keep info overlay");
        requestWebVideoFullscreen();
        // 播放器模式下不启动WebView卡顿检测（避免误判干扰ExoPlayer播放）
        if (playerContainer == null || playerContainer.getVisibility() != View.VISIBLE) {
            startWebVideoStallDetector();
        }
    }

    /**
     * 触发网页视频元素全屏：注入CSS让video元素及其所有祖先容器铺满整个WebView视口。
     * 关键：央视等页面把video放在iframe里，必须同时处理主文档与所有同源iframe——
     * 1. 对每个document分别注入video全屏CSS
     * 2. 装着video的iframe元素本身在父文档里拉成全屏
     * 旧版只查主document，iframe里的视频完全没全屏化（网页header下方布局 → 画面偏下），
     * 且无论成功与否回调都收起换台遮罩（露出网页内容）。
     * 只有确认全屏CSS真正生效才收遮罩；视频未就绪时2.5秒后重试（最多5次）。
     */
    private void requestWebVideoFullscreen() {
        requestWebVideoFullscreen(true);
    }

    private void requestWebVideoFullscreen(boolean allowRetry) {
        if (webView == null) return;
        if (isWebVideoFullscreenRequested) {
            hideSwitchOverlay(); // CSS已注入过，直接收起换台遮罩
            return;
        }
        isWebVideoFullscreenRequested = true;
        // 对指定document注入video全屏样式；iframe里的video还要把iframe元素本身拉全屏
        String js = "(function(){try{" +
            "function styleVideo(doc){" +
            "  try{var v=doc.querySelector('video');if(!v)return false;" +
            "  var st=doc.getElementById('news-live-fullscreen-style');" +
            "  if(!st){st=doc.createElement('style');st.id='news-live-fullscreen-style';" +
            "    st.textContent='video{position:fixed!important;top:0!important;left:0!important;" +
            "width:100vw!important;height:100vh!important;min-width:100vw!important;min-height:100vh!important;" +
            "max-width:100vw!important;max-height:100vh!important;z-index:2147483647!important;" +
            "object-fit:contain!important;background:#000000!important;outline:none!important;border:none!important;}" +
            "video::-webkit-media-controls{display:none!important;}" +
            "html,body{margin:0!important;padding:0!important;overflow:hidden!important;background:#000!important;}';" +
            "    (doc.head||doc.documentElement).appendChild(st);}" +
            "  var el=v.parentNode;var n=0;" +
            "  while(el&&el!==doc.body&&n<20){" +
            "    el.style.position='fixed';el.style.top='0';el.style.left='0';" +
            "    el.style.width='100vw';el.style.height='100vh';" +
            "    el.style.zIndex='2147483646';el.style.background='#000000';el.style.overflow='hidden';" +
            "    el=el.parentNode;n++;}" +
            "  v.style.position='fixed';v.style.top='0';v.style.left='0';" +
            "  v.style.width='100vw';v.style.height='100vh';" +
            "  v.style.zIndex='2147483647';v.style.objectFit='contain';v.style.background='#000000';" +
            "  var ch=doc.body?doc.body.children:null;" +
            "  if(ch){for(var i=0;i<ch.length;i++){var c=ch[i];if(c===v)continue;if(!c.contains(v)){c.style.display='none';}}}" +
            "  return true;" +
            "  }catch(e){return false;}}" +
            "var ok=styleVideo(document);" +
            "var frames=document.querySelectorAll('iframe');" +
            "for(var i=0;i<frames.length;i++){" +
            "  try{var fd=frames[i].contentDocument;if(!fd)continue;" +
            "    if(styleVideo(fd)){" +
            "      var f=frames[i];" +
            "      f.style.setProperty('position','fixed','important');f.style.setProperty('top','0','important');" +
            "      f.style.setProperty('left','0','important');f.style.setProperty('width','100vw','important');" +
            "      f.style.setProperty('height','100vh','important');f.style.setProperty('min-width','100vw','important');" +
            "      f.style.setProperty('min-height','100vh','important');f.style.setProperty('z-index','2147483645','important');" +
            "      f.style.setProperty('background','#000','important');f.style.setProperty('border','none','important');" +
            "      ok=true;" +
            "    }" +
            "  }catch(e){}}" +
            "return ok?'css-fullscreen':'no-video';" +
            "}catch(e){return 'err';}})();";
        webView.evaluateJavascript(js, r -> {
            LogUtil.i("NewsLive", "requestWebVideoFullscreen: " + r);
            if (r != null && r.contains("css-fullscreen")) {
                fullscreenRetryCount = 0;
                // 全屏CSS确认生效后才收起换台遮罩：用户第一眼看到的就是已全屏的视频画面
                hideSwitchOverlay();
                updatePortraitPlayOverlay();
            } else if (allowRetry && fullscreenRetryCount < 5) {
                // 视频元素尚未就绪（页面还在初始化）：复位标记稍后重试，遮罩保持
                isWebVideoFullscreenRequested = false;
                fullscreenRetryCount++;
                handler.postDelayed(() -> {
                    if (useWebMode && webView != null) {
                        requestWebVideoFullscreen(true);
                    }
                }, 2500);
            } else {
                isWebVideoFullscreenRequested = false;
            }
        });
    }

    /** 启动WebView视频卡顿检测：定时检查currentTime是否推进
     *  关键策略：readyState<3（加载/缓冲中）不判卡顿；刷新后30秒冷却期避免连续刷新
     *  恢复策略：卡顿时优先用ExoPlayer接管播放（解码更稳定），无可用流地址才刷新页面 */
    private void startWebVideoStallDetector() {
        stopWebVideoStallDetector();
        lastWebVideoTime = -1;
        webVideoStallCount = 0;
        stallCheckPending = false;
        stallHungCount = 0;
        final String checkJs = "(function(){try{" +
            "function findVideo(doc){try{var v=doc.querySelector('video');if(v)return v;}catch(e){}return null;}" +
            "var v=findVideo(document);" +
            "if(!v){var iframes=document.querySelectorAll('iframe');for(var i=0;i<iframes.length;i++){try{if(iframes[i].contentDocument){v=findVideo(iframes[i].contentDocument);if(v)break;}}catch(e){}}}" +
            "if(!v)return 'no-video';" +
            "return JSON.stringify({t:v.currentTime,paused:v.paused,ready:v.readyState,vw:v.videoWidth,vh:v.videoHeight,muted:v.muted});}catch(e){return 'err';}})();";
        webVideoStallRunnable = new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                if (stallCheckPending) {
                    // 上一轮查询迟迟没有回调：渲染进程可能已挂起/崩溃
                    stallHungCount++;
                    webVideoStallCount += WEB_STALL_CHECK_INTERVAL / 1000;
                    LogUtil.w("NewsLive", "StallCheck: renderer no response x" + stallHungCount);
                } else {
                    stallCheckPending = true;
                    webView.evaluateJavascript(checkJs, result -> {
                        stallCheckPending = false;
                        stallHungCount = 0;
                        processStallCheckResult(result);
                    });
                }
                // 连续3轮无响应：判定渲染进程挂起/崩溃，重建WebView恢复
                if (stallHungCount >= 3) {
                    stallHungCount = 0;
                    webVideoStallCount = 0;
                    stallCheckPending = false;
                    Toast.makeText(MainActivity.this, "播放器异常，正在自动恢复...", Toast.LENGTH_SHORT).show();
                    recreateWebView();
                    return; // 重建后会重新启动检测
                }
                // 看门狗自续：无论回调是否返回都保持检测循环
                // （旧实现依赖回调续期，渲染器挂起后看门狗静默死亡，再也没人救活播放）
                handler.postDelayed(this, WEB_STALL_CHECK_INTERVAL);
            }
        };
        handler.postDelayed(webVideoStallRunnable, WEB_STALL_CHECK_INTERVAL);
    }

    /** 处理卡顿查询结果：currentTime推进/暂停/缓冲判定 + 卡顿恢复动作 */
    private void processStallCheckResult(String result) {
        if (result == null || result.contains("no-video") || result.contains("err")) {
            webVideoStallCount++;
        } else {
            try {
                String jsonStr = result.replace("\\\"", "\"").replaceAll("^\"|\"$", "");
                JSONObject json = new JSONObject(jsonStr);
                double currentTime = json.optDouble("t", 0);
                boolean paused = json.optBoolean("paused", true);
                int readyState = json.optInt("ready", 0);
                LogUtil.d("NewsLive", "StallCheck: t=" + currentTime + " paused=" + paused + " ready=" + readyState + " stallCount=" + webVideoStallCount);
                // readyState < 3 (HAVE_FUTURE_DATA)：视频正在加载/缓冲，不判定卡顿
                if (readyState < 3) {
                    webVideoStallCount = 0;
                } else if (paused) {
                    // 视频暂停且能播放，可能卡住
                    webVideoStallCount += WEB_STALL_CHECK_INTERVAL / 1000;
                } else if (lastWebVideoTime >= 0 && currentTime == lastWebVideoTime) {
                    // 非暂停但currentTime没推进，真正卡顿
                    webVideoStallCount += WEB_STALL_CHECK_INTERVAL / 1000;
                } else {
                    // 正常推进，重置计数
                    webVideoStallCount = 0;
                }
                lastWebVideoTime = currentTime;
            } catch (Exception e) {
                webVideoStallCount++;
            }
        }
        if (webVideoStallCount >= WEB_STALL_THRESHOLD) {
            // 冷却期内不刷新，计数保留，冷却期一到立即恢复
            long now = System.currentTimeMillis();
            if (now - lastStallRefreshTime < STALL_REFRESH_COOLDOWN_MS) {
                LogUtil.d("NewsLive", "Stall detected but in cooldown (" + (now - lastStallRefreshTime) / 1000 + "s since last refresh), keep counting");
            } else {
                webVideoStallCount = 0;
                lastStallRefreshTime = now;
                // 优先尝试用ExoPlayer接管播放（解码管线更稳定，不受WebView解码器故障影响）
                if (!lastDetectedVideoUrl.isEmpty() && !isDrmStream(lastDetectedVideoUrl)) {
                    LogUtil.w("NewsLive", "WebView video stalled, switching to ExoPlayer: " + lastDetectedVideoUrl);
                    Toast.makeText(MainActivity.this, "视频卡顿，切换播放器恢复...", Toast.LENGTH_SHORT).show();
                    switchToPlayerMode(lastDetectedVideoUrl);
                } else {
                    // 无可用流地址或DRM流，刷新当前页面
                    LogUtil.w("NewsLive", "WebView video stalled " + WEB_STALL_THRESHOLD + "s, refreshing current page");
                    Toast.makeText(MainActivity.this, "视频卡顿，正在刷新...", Toast.LENGTH_SHORT).show();
                    refreshVideoFromWeb();
                }
            }
        }
    }

    /** 判断是否为DRM/特殊编码流（ExoPlayer无法播放，必须留在WebView） */
    private boolean isDrmStream(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        return lower.contains("cdrm") || lower.contains("kcdnvip")
            || (lower.contains("cctv") && lower.contains(".m3u8") && !lower.contains("cctvnews"));
    }

    /** 停止WebView视频卡顿检测 */
    private void stopWebVideoStallDetector() {
        if (webVideoStallRunnable != null) {
            handler.removeCallbacks(webVideoStallRunnable);
            webVideoStallRunnable = null;
        }
    }

    private void loadWebSource() {
        if (!useWebMode) {
            // 播放器模式下拒绝网页加载（历史定时器误触发防护）
            LogUtil.w("NewsLive", "loadWebSource skipped: player mode active");
            return;
        }
        if (!isNetworkAvailable) {
            Toast.makeText(this, "网络不可用，请检查网络连接", Toast.LENGTH_LONG).show();
            return;
        }

        cancelStreamRotation();
        silentRefreshPending = false; // 显式加载（换台/恢复）不再是静默链路，避免误静音
        keyRewriteCapable = false;    // 播放回到WebView，数据源重写不适用
        webView.setVisibility(View.VISIBLE);
        playerContainer.setVisibility(View.GONE);
        tvSourceInfo.setText(webSiteNames.isEmpty() ? "加载中..." : webSiteNames.get(currentSiteIndex) + "(加载中...)");
        progressBar.setVisibility(View.VISIBLE);
        // 原生电视换台体验：黑色遮罩盖住网页加载过程，视频就绪后才露出画面
        showSwitchOverlay(webSiteNames.isEmpty() ? "正在加载..." : webSiteNames.get(currentSiteIndex));

        isWebViewLoading = true;
        webViewLoadStartTime = System.currentTimeMillis();
        webViewRetryCount = 0;

        webView.resumeTimers();
        webView.onResume();
        webView.loadUrl(webSourceUrl);

        // 兜底：部分页面视频播放后嗅探/JS桥都不触发，主动轮询播放状态以收起换台遮罩
        handler.postDelayed(() -> {
            if (useWebMode && switchOverlay != null
                    && switchOverlay.getVisibility() == View.VISIBLE) {
                checkWebVideoPlayingAndFullscreen(0);
            }
        }, 10000);

        startWebViewTimeoutTimer();
    }

    /** 构造一个配置完整的ExoPlayer实例（主播放器与无缝续播预载播放器共用同一套参数） */
    private ExoPlayer createPlayer() {
        // 为CCTV等需要Referer的流添加请求头
        java.util.Map<String, String> requestHeaders = new java.util.HashMap<>();
        requestHeaders.put("Referer", "https://tv.cctv.com/");
        requestHeaders.put("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        DefaultHttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .setDefaultRequestProperties(requestHeaders);

        int minBuffer = Math.max(bufferMinMs, 10000);
        int maxBuffer = Math.max(bufferMaxMs, 60000);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
            // 初始起播缓冲5000ms（旧值1000ms会导致冷启动时缓冲太薄，
            // 播放几秒后遇网络抖动必小卡一次再补满——每次进入App都会复现）
            .setBufferDurationsMs(minBuffer, maxBuffer, 5000, minBuffer)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(maxBuffer, true)
            .setTargetBufferBytes(-1)
            .build();

        // 启用解码器回退：硬件解码失败时自动切换到软件解码
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        // 使用Context构造，自动包含HLS/ Dash/ SmoothStreaming等支持。
        // 数据源包一层KeyRewritingDataSource：直播流auth_key过期(403)时透明改用最新签名地址，
        // 播放列表内容连续（同一直播窗口）→ 播放器无感续播，等效网页播放器的原无缝机制
        androidx.media3.datasource.DataSource.Factory wrappedFactory =
            () -> new KeyRewritingDataSource(httpDataSourceFactory);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(this)
            .setDataSourceFactory(wrappedFactory);

        // 带宽估计器初始值设为2Mbps（与下方码率上限一致）：
        // 冷启动时估计为空，ABR会保守地从低码率变体开始播，几秒后测得带宽充足再升档，
        // 解码器重建造成"第一次启动播放5秒左右必卡一下"；给定初始值后首次即选中正确变体
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter.Builder(this)
            .setInitialBitrateEstimate(2_000_000)
            .build();

        ExoPlayer p = new ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .build();

        // 限制最高分辨率540p、码率2Mbps：KHAN-230盒子硬解器对1080p会初始化失败(ENOMEM)，
        // 限制后ExoPlayer自动选择低分辨率变体，避免解码失败导致黑屏/断流
        androidx.media3.common.TrackSelectionParameters trackParams = p.getTrackSelectionParameters()
            .buildUpon()
            .setMaxVideoSize(960, 540)
            .setMaxVideoBitrate(2000000)
            .build();
        p.setTrackSelectionParameters(trackParams);

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build();
        p.setAudioAttributes(audioAttributes, false);
        return p;
    }

    private void initPlayer() {
        player = createPlayer();

        // 禁用PlayerView内置控制器（播放/暂停按钮、进度条）：TV遥控器用方向键/菜单键操作，
        // 内置控制器会在缓冲/切源时自动弹出，影响直播观看的无感体验
        playerView.setUseController(false);
        // 播放器重置/出错后保持最后一帧画面（不黑屏），新流READY后无缝接上
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setPlayer(player);
    }

    /** 网页模式下同步频道名标签为当前网页频道（播放器源列表的名字只在播放器模式显示） */
    private void updateWebChannelLabel() {
        if (tvSourceInfo != null && !webSiteUrls.isEmpty()
                && currentSiteIndex < webSiteNames.size()) {
            tvSourceInfo.setText(webSiteNames.get(currentSiteIndex));
        }
    }

    private void switchMode() {
        useWebMode = !useWebMode;
        prefs.edit().putBoolean(KEY_USE_WEB_MODE, useWebMode).apply();

        if (useWebMode) {
            cancelStreamRotation();
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
            // 频道名显示网页频道的名字（updateSourceInfo显示的是播放器源列表名，仅播放器模式适用）
            updateWebChannelLabel();
            Toast.makeText(this, "切换到网页模式", Toast.LENGTH_SHORT).show();
        } else {
            cancelWebViewTimeoutTimer();
            // 关键：清掉网页模式遗留的全部定时器/标记——否则网页模式的刷新兜底定时器
            // 在播放器模式下到期误触发，把画面切到"正在加载"的网页（声音仍是播放器源）
            cancelWebRefreshFallback();
            stopWebVideoStallDetector();
            silentRefreshPending = false;
            fullscreenRetryCount = 0;
            webView.pauseTimers();
            webView.onPause();
            webView.setVisibility(View.GONE);
            playerContainer.setVisibility(View.VISIBLE);
            hideSwitchOverlay();
            if (player == null) {
                initPlayer();
            }
            // 真正切到播放器模式：播放"直播源列表"配置的源。
            // 旧逻辑优先播网页嗅探的lastDetectedVideoUrl，导致切模式后播的还是网页频道
            // 的视频流，模式切换形同虚设。网页流只在网页模式内使用（嗅探自动接管）。
            if (isStreamListEnabled && !streamUrls.isEmpty()) {
                loadStreamFromConfig(currentUrlIndex);
            } else {
                Toast.makeText(this, "未启用直播源列表，请到配置页开启并添加源", Toast.LENGTH_LONG).show();
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

    /** 频道节目单：左侧分组、右侧频道（显示测速），点击直接播放。OK键/节目单按钮呼出 */
    private void showChannelMenu() {
        if (streamUrls.isEmpty()) {
            Toast.makeText(this, "暂无直播源，请先在设置页优选或添加源", Toast.LENGTH_LONG).show();
            return;
        }
        float density = getResources().getDisplayMetrics().density;

        // 分组提取（保持出现顺序）+「全部」
        java.util.LinkedHashSet<String> groupSet = new java.util.LinkedHashSet<>();
        for (String g : streamGroups) groupSet.add(g == null || g.isEmpty() ? "其他" : g);
        final java.util.List<String> groups = new java.util.ArrayList<>();
        groups.add("全部");
        groups.addAll(groupSet);
        final String[] selGroup = {groups.get(0)};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (12 * density);
        root.setPadding(pad, pad, pad, pad);

        ListView lvGroups = new ListView(this);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                (int) (106 * density), LinearLayout.LayoutParams.MATCH_PARENT);
        glp.rightMargin = (int) (8 * density);
        lvGroups.setLayoutParams(glp);
        lvGroups.setDivider(null);

        ListView lvChannels = new ListView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lvChannels.setLayoutParams(clp);

        root.addView(lvGroups);
        root.addView(lvChannels);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("节目单（" + streamUrls.size() + " 个频道）")
                .setView(root)
                .setNegativeButton("关闭", null)
                .create();

        final java.util.List<Integer> idxHolder = new java.util.ArrayList<>();
        final android.widget.ArrayAdapter<String> groupAdapter =
                new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, groups) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        View v = super.getView(position, convertView, parent);
                        android.widget.TextView tv = v.findViewById(android.R.id.text1);
                        if (groups.get(position).equals(selGroup[0])) {
                            tv.setTextColor(0xFF2196F3);
                            tv.setTypeface(null, android.graphics.Typeface.BOLD);
                        } else {
                            tv.setTextColor(0xFF333333);
                            tv.setTypeface(null, android.graphics.Typeface.NORMAL);
                        }
                        return v;
                    }
                };

        Runnable updateChannels = () -> {
            idxHolder.clear();
            for (int i = 0; i < streamUrls.size(); i++) {
                String g = i < streamGroups.size() ? streamGroups.get(i) : "其他";
                if (g == null || g.isEmpty()) g = "其他";
                if ("全部".equals(selGroup[0]) || selGroup[0].equals(g)) idxHolder.add(i);
            }
            java.util.List<String> rows = new java.util.ArrayList<>();
            for (int idx : idxHolder) {
                String speed = (idx < streamSpeeds.size() && streamSpeeds.get(idx) > 0)
                        ? streamSpeeds.get(idx) + "KB/s" : "";
                String mark = idx == currentUrlIndex ? "▶ " : "    ";
                rows.add(mark + streamNames.get(idx) + (speed.isEmpty() ? "" : "  「" + speed + "」"));
            }
            android.widget.ArrayAdapter<String> chAdapter =
                    new android.widget.ArrayAdapter<String>(MainActivity.this,
                            android.R.layout.simple_list_item_1, rows) {
                        @Override
                        public View getView(int position, View convertView, ViewGroup parent) {
                            View v = super.getView(position, convertView, parent);
                            android.widget.TextView tv = v.findViewById(android.R.id.text1);
                            if (idxHolder.get(position) == currentUrlIndex) {
                                tv.setTextColor(0xFF2196F3);
                            } else {
                                tv.setTextColor(0xFFEEEEEE);
                            }
                            return v;
                        }
                    };
            lvChannels.setAdapter(chAdapter);
            int cur = idxHolder.indexOf(currentUrlIndex);
            if (cur >= 0) lvChannels.setSelection(Math.max(cur - 3, 0));
        };

        lvGroups.setAdapter(groupAdapter);
        lvGroups.setOnItemClickListener((parent, view, position, id) -> {
            selGroup[0] = groups.get(position);
            groupAdapter.notifyDataSetChanged();
            updateChannels.run();
        });
        lvChannels.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            currentUrlIndex = idxHolder.get(position);
            loadStreamFromConfig(currentUrlIndex);
        });

        dialog.show();
        updateChannels.run();
    }

    /** App 内打开配置页（内置 WebView 走 127.0.0.1 回环，不依赖外部浏览器） */
    private void showSettingsDialog() {
        if (!isNetworkAvailable) {
            Toast.makeText(this, "网络不可用，配置服务未启动", Toast.LENGTH_SHORT).show();
        }
        float density = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF2196F3);
        bar.setPadding((int) (12 * density), (int) (8 * density), (int) (12 * density), (int) (8 * density));
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("⚙ 设置（本机配置页 http://127.0.0.1:" + HTTP_PORT + "）");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(15);
        android.widget.TextView close = new android.widget.TextView(this);
        close.setText("✕ 关闭");
        close.setTextColor(0xFFFFFFFF);
        close.setTextSize(15);
        close.setPadding((int) (16 * density), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(close);
        WebView wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        root.addView(bar);
        root.addView(wv, wlp);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar)
                .setView(root)
                .create();
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            wv.loadUrl("about:blank");
            wv.destroy();
        });
        dialog.show();
        wv.loadUrl("http://127.0.0.1:" + HTTP_PORT);
    }

    /** 退出应用（沉浸式下返回键难找时的兜底入口） */
    private void confirmExit() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("退出")
                .setMessage("确定退出新闻直播吗？")
                .setPositiveButton("退出", (d, w) -> finishAndRemoveTask())
                .setNegativeButton("取消", null)
                .show();
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
        
        // 移除上一次注册的监听器，防止监听器泄漏；同时作废可能存在的无缝预载
        releasePendingPlayer();
        if (currentPlayerListener != null) {
            player.removeListener(currentPlayerListener);
            currentPlayerListener = null;
        }

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(streamUrls.get(index)));
        player.stop();
        player.setMediaItem(mediaItem);
        currentPlayerListener = new Player.Listener() {
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
                        giveUpRecoveryCount = 0;
                        cancelGiveUpRecovery();
                        hideSwitchOverlay();
                        clearSurfaceIfAudioOnly();
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
                    scheduleGiveUpRecovery();
                }
            }
        };
        player.addListener(currentPlayerListener);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void showControlPanel() {
        if (controlPanel != null) {
            controlPanel.setVisibility(View.VISIBLE);
            isControlVisible = true;
        }
        updatePortraitPlayOverlay();
    }

    private void hideControlPanel() {
        if (controlPanel != null) {
            controlPanel.setVisibility(View.GONE);
            isControlVisible = false;
        }
        updatePortraitPlayOverlay();
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
            if (config.has("bannerVisible")) {
                bannerVisible = config.optBoolean("bannerVisible", true);
                prefs.edit().putBoolean(KEY_BANNER_VISIBLE, bannerVisible).apply();
            }
            if (config.has("bannerFontSize")) {
                bannerFontSize = config.optInt("bannerFontSize", 13);
                prefs.edit().putInt(KEY_BANNER_FONT_SIZE, bannerFontSize).apply();
            }
            if (config.has("bannerHeight")) {
                bannerHeight = config.optInt("bannerHeight", 28);
                prefs.edit().putInt(KEY_BANNER_HEIGHT, bannerHeight).apply();
            }
            if (config.has("bannerAutoFit")) {
                bannerAutoFit = config.optBoolean("bannerAutoFit", true);
                prefs.edit().putBoolean(KEY_BANNER_AUTO_FIT, bannerAutoFit).apply();
                applyBannerStyle(); // 重新收敛字号（自动=尽量放大 / 手动=固定基准）
            }
            if (config.has("manualLocation")) {
                // 手动地区：先验证（查得到天气坐标才生效），通过后保存并立即刷新定位与天气
                handleManualLocationUpdate(config.optString("manualLocation", ""));
            }

            if (config.has("websites")) {
                JSONArray websites = config.getJSONArray("websites");
                webSiteNames.clear();
                webSiteUrls.clear();
                webSiteEnabled.clear();
                for (int i = 0; i < websites.length(); i++) {
                    JSONObject site = websites.getJSONObject(i);
                    webSiteNames.add(site.optString("name", "网站" + (i + 1)));
                    webSiteUrls.add(site.optString("url", ""));
                    webSiteEnabled.add(site.optBoolean("enabled", true));
                }
                saveWebSites();
                // 切换到第一个启用的源
                currentSiteIndex = 0;
                for (int i = 0; i < webSiteEnabled.size(); i++) {
                    if (webSiteEnabled.get(i)) { currentSiteIndex = i; break; }
                }
                if (!webSiteUrls.isEmpty() && currentSiteIndex < webSiteUrls.size()) {
                    webSourceUrl = webSiteUrls.get(currentSiteIndex);
                }
            }

            if (config.has("sources")) {
                parseConfig(config);
                saveConfigLocal();
            }

            currentUrlIndex = 0;
            runOnUiThread(() -> {
                updatePlayerModeButtons();
                applyBannerStyle();
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
                site.put("enabled", i < webSiteEnabled.size() ? webSiteEnabled.get(i) : true);
                sites.put(site);
            }
            prefs.edit().putString(KEY_WEB_SITES, sites.toString()).putInt(KEY_WEB_SITES_VERSION, CURRENT_WEB_SITES_VERSION).apply();
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
                    // 播放器模式：OK键呼出节目单（市面直播App习惯），暂停走播放/暂停键或面板
                    showChannelMenu();
                    return true;

                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // 左右方向键不再切换模式（统一由眼睛按钮切换），交给页面焦点导航
                    break;

                case KeyEvent.KEYCODE_DPAD_UP:
                    if (useWebMode) {
                        switchToPrevWebSite();
                    } else {
                        switchToPrevSource();
                    }
                    return true;

                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (useWebMode) {
                        switchToNextWebSite();
                    } else {
                        switchToNextSource();
                    }
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

                case KeyEvent.KEYCODE_BACK: {
                    // 1) 先退出WebView全屏视图
                    if (customView != null) {
                        cleanupCustomView();
                        if (webView != null) webView.setVisibility(View.VISIBLE);
                        if (infoOverlay != null) infoOverlay.setVisibility(View.VISIBLE);
                        lastBackPressTime = 0; // 退出全屏不计入双击退出
                        return true;
                    }
                    // 2) 2秒内连按两次返回：退出应用（直播应用单次误触不应直接退出）
                    long nowBack = System.currentTimeMillis();
                    if (nowBack - lastBackPressTime < 2000) {
                        finish();
                        return true;
                    }
                    lastBackPressTime = nowBack;
                    // 3) 控制面板打开时先收起面板
                    if (controlPanel != null && controlPanel.getVisibility() == View.VISIBLE) {
                        hideControlPanel();
                        Toast.makeText(this, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    // 4) 网页有历史时先返回上一页
                    if (webView != null && webView.canGoBack() && useWebMode) {
                        webView.goBack();
                        Toast.makeText(this, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    // 5) 无历史：提示双击退出
                    Toast.makeText(this, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show();
                    return true;
                }

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
        cancelStreamRotation();
        if (player != null) {
            player.setPlayWhenReady(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (useWebMode && webView != null) {
            webView.resumeTimers();
            webView.onResume();
            // 后台/休眠唤醒后,直播流已失效,重新加载页面
            // 条件:网络可用 且 (曾断网 或 离开时间超过30秒)
            if (isNetworkAvailable && wasNetworkLostWhilePaused) {
                wasNetworkLostWhilePaused = false;
                loadWebSource();
            } else if (isNetworkAvailable && pausedAt > 0 && System.currentTimeMillis() - pausedAt > 30000) {
                loadWebSource();
            }
        }
        // ExoPlayer播放中恢复前台：重新启动主动轮换调度（onStop已取消）
        if (player != null && playerContainer != null
                && playerContainer.getVisibility() == View.VISIBLE && useWebMode) {
            scheduleStreamRotation();
        }
        // 恢复天气定时刷新，并立即刷新一次（保证从后台/休眠唤醒后天气为最新）
        startWeatherRefresh();
        if (isNetworkAvailable && lastLatitude != 0 && lastLongitude != 0) {
            LogUtil.i("NewsLive", "onResume 立即刷新天气");
            fetchWeather(lastLatitude, lastLongitude);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = System.currentTimeMillis();
        // 后台时暂停天气定时刷新，避免无效网络请求
        if (weatherRefreshHandler != null && weatherRefreshRunnable != null) {
            weatherRefreshHandler.removeCallbacks(weatherRefreshRunnable);
        }
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

        // 停止天气定时刷新
        if (weatherRefreshHandler != null && weatherRefreshRunnable != null) {
            weatherRefreshHandler.removeCallbacks(weatherRefreshRunnable);
        }

        // 停止WebView视频卡顿检测
        stopWebVideoStallDetector();
        // 取消刷新兜底定时器
        cancelWebRefreshFallback();
        cancelStreamRotation();
        // 取消深度恢复守护与换台遮罩兜底
        cancelGiveUpRecovery();
        hideSwitchOverlay();
        // 释放无缝续播预载播放器
        releasePendingPlayer();

        // 清理全屏视图
        cleanupCustomView();

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
            // 移除监听器后再释放，避免泄漏的监听器回调已释放的播放器
            if (currentPlayerListener != null) {
                player.removeListener(currentPlayerListener);
                currentPlayerListener = null;
            }
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
        // 刷盘日志，避免丢失最后几条
        LogUtil.flush();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 先退出WebView全屏视图
        if (customView != null) {
            cleanupCustomView();
            if (webView != null) webView.setVisibility(View.VISIBLE);
            if (infoOverlay != null) infoOverlay.setVisibility(View.VISIBLE);
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
        // 旋转不重建（manifest已声明configChanges）：重排控制面板/横幅布局/竖屏浮层/横幅字号
        applyControlPanelLayout();
        // 横幅按新方向重排（竖屏三行/横屏单行），字号从头收敛，避免多行布局滞留到横屏
        bannerFitScale = 1f;
        relayoutBannerForOrientation();
        applyBannerStyle();
        updatePortraitPlayOverlay();
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
                java.io.InputStream in = client.getInputStream();
                // 字节级读取请求头（直到\r\n\r\n）。不能用字符流读头+定长字符读body：
                // ①BufferedReader会预读吞掉body开头字节；②按字符读contentLength字节，
                //   含中文时字符数<字节数导致read阻塞/截断→JSON解析报"配置格式错误"（保存偶发失败）
                java.io.ByteArrayOutputStream headBuf = new java.io.ByteArrayOutputStream();
                int hb;
                while ((hb = in.read()) != -1) {
                    headBuf.write(hb);
                    byte[] h = headBuf.toByteArray();
                    int s = h.length;
                    if (s >= 4 && h[s - 1] == '\n' && h[s - 2] == '\r' && h[s - 3] == '\n' && h[s - 4] == '\r') break;
                }
                String headerBlock = headBuf.toString("UTF-8");
                String[] headerLines = headerBlock.split("\r\n");
                String request = headerLines.length > 0 ? headerLines[0] : null;
                int contentLength = 0;
                for (String hl : headerLines) {
                    if (hl.toLowerCase().startsWith("content-length:")) {
                        try {
                            contentLength = Integer.parseInt(hl.substring(hl.indexOf(':') + 1).trim());
                        } catch (Exception ignore) { }
                    }
                }

                if (request != null && request.startsWith("POST")) {
                    // 按字节精确读满body再整体UTF-8解码（中文/emoji安全）
                    java.io.ByteArrayOutputStream bodyBuf = new java.io.ByteArrayOutputStream();
                    byte[] tmp = new byte[4096];
                    int remaining = contentLength, n;
                    while (remaining > 0 && (n = in.read(tmp, 0, Math.min(tmp.length, remaining))) != -1) {
                        bodyBuf.write(tmp, 0, n);
                        remaining -= n;
                    }
                    String configBody = bodyBuf.toString("UTF-8");

                    updateConfig(configBody);
                    
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\n\r\n{\"status\":\"ok\"}";
                    client.getOutputStream().write(response.getBytes());
                } else if (request != null && request.startsWith("GET") && request.contains("/proxy?url=")) {
                    String proxyUrl = java.net.URLDecoder.decode(request.split("url=")[1].split(" ")[0], "UTF-8");
                    String proxyResult = fetchUrlContent(proxyUrl);
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\n\r\n" + proxyResult;
                    client.getOutputStream().write(response.getBytes());
                } else if (request != null && request.startsWith("GET") && request.contains("/scan_start")) {
                    String resp = handleScanStart(request);
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\n\r\n" + resp;
                    client.getOutputStream().write(response.getBytes());
                } else if (request != null && request.startsWith("GET") && request.contains("/scan_status")) {
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\n\r\n" + scanStatusJson;
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
            // 用 org.json 生成,自动转义引号/反斜杠,避免特殊字符破坏内嵌 JS
            JSONArray sourcesArr = new JSONArray();
            for (int i = 0; i < streamUrls.size(); i++) {
                JSONObject o = new JSONObject();
                try {
                    o.put("name", streamNames.get(i));
                    o.put("url", streamUrls.get(i));
                    sourcesArr.put(o);
                } catch (Exception ignore) { }
            }
            String sourcesJson = sourcesArr.toString();

            JSONArray webSitesArr = new JSONArray();
            for (int i = 0; i < webSiteUrls.size(); i++) {
                JSONObject o = new JSONObject();
                try {
                    o.put("name", webSiteNames.get(i));
                    o.put("url", webSiteUrls.get(i));
                    o.put("enabled", i < webSiteEnabled.size() && webSiteEnabled.get(i));
                    webSitesArr.put(o);
                } catch (Exception ignore) { }
            }
            String webSitesJson = webSitesArr.toString();

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
                ".source-item.disabled-item{background:#f0f0f0;opacity:0.6;border-color:#ccc}" +
                ".enable-label{display:inline-flex;align-items:center;gap:4px;margin:4px 0;font-size:14px;color:#333}" +
                ".item-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px}" +
                ".drag-handle{color:#999;font-size:20px;cursor:grab;padding:0 4px}" +
                ".drag-tip{color:#999;font-size:11px;margin-left:8px}" +
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
                "<div class='tip'>💡 按住卡片左上角 ☰ 手柄上下拖动调整顺序（仅手柄区域可拖动） | 频道+/-切换网站</div>" +
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
                "<div class='tip'>播放器模式下使用以下源。数量多时自动分页浏览，支持搜索过滤。</div>" +
                "<input type='text' id='srcSearch' placeholder='🔍 搜索频道名 / URL 过滤' oninput='srcSearchChanged(this.value)' style='width:100%;box-sizing:border-box;margin-bottom:6px'>" +
                "<div id='sources'></div>" +
                "<button class='btn-add' onclick='addSource();srcPage=999;renderSources()'>+ 添加直播源</button>" +
                "</div>" +
                "<div class='section' style='background:#EDE7F6;border:1px solid #7E57C2'>" +
                "<div class='section-title'>🔍 直播源自动优选（App 内置检测）</div>" +
                "<div class='tip'>拉取订阅 → 逐条连通验证与分片测速 → 每频道保留最快线路 → 自动替换上方直播源列表。建议在 WiFi 下运行，约 1~3 分钟；移动数据下测速结果仅代表当前网络。</div>" +
                "<textarea id='scanSubs' rows='3' style='width:100%;box-sizing:border-box;font-size:12px'>https://vbskycn.github.io/iptv/tv/iptv4.txt\nhttps://iptv-org.github.io/iptv/countries/cn.m3u</textarea>" +
                "<div class='buffer-inputs' style='margin-top:6px'>" +
                "<div><label style='display:block;font-size:12px;margin-bottom:4px'>淘汰线 KB/s</label><input type='number' id='scanMin' value='120'></div>" +
                "<div><label style='display:block;font-size:12px;margin-bottom:4px'>并发数</label><input type='number' id='scanConc' value='15'></div>" +
                "</div>" +
                "<div class='btn-group'><button class='btn-fetch' id='scanBtn' onclick='startScan()'>🚀 开始优选</button></div>" +
                "<div id='scanProgress' class='tip' style='text-align:left'></div>" +
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
                "<div class='section-title'>📊 顶部信息横幅</div>" +
                "<label><input type='checkbox' id='bannerVisible' " + (bannerVisible ? "checked" : "") + "> 显示顶部信息横幅（农历/时间/天气）</label>" +
                "<label><input type='checkbox' id='bannerAutoFit' " + (bannerAutoFit ? "checked" : "") + "> 自动调节字号（推荐：不截断遮挡任何文字的前提下尽量放大）</label>" +
                "<div class='buffer-inputs' style='margin-top:8px'>" +
                "<div><label style='display:block;margin-bottom:4px'>手动字号基准 (9-18，关闭自动调节时生效)</label><input type='number' id='bannerFontSize' min='9' max='18' style='width:100%' value='" + bannerFontSize + "'></div>" +
                "<div><label style='display:block;margin-bottom:4px'>区域高度 dp (20-60)</label><input type='number' id='bannerHeight' min='20' max='60' style='width:100%' value='" + bannerHeight + "'></div>" +
                "</div>" +
                "<div class='tip'>自动调节：横屏/竖屏各行按内容自动收敛到\"恰好放满\"的最大字号（上限24sp）；关闭后使用手动字号，超宽时只缩小保证不截断。</div>" +
                "</div>" +
                "<div class='section'>" +
                "<div class='section-title'>📍 地区设置（定位/天气）</div>" +
                "<input type='text' id='manualLocation' placeholder='留空=自动IP定位；如：江西省抚州市临川区' value='" + manualLocation.replace("'", "") + "'>" +
                "<div class='btn-group'><button class='btn-fetch' onclick='queryLocation()'>🔍 查询验证（实时试查天气）</button></div>" +
                "<div id='locationResult' class='tip' style='text-align:left'>输入地区后点\"查询验证\"实时试查：✅查得到=地区有效并预览当前气温，❌查不到=可能不存在或数据源未收录。</div>" +
                "<div class='tip'>手动配置后不再自动定位，天气按此地区获取。支持省+市+区/县/乡镇（如：江西省抚州市临川区、上顿渡镇）。查询通过后点底部\"保存配置\"生效；清空保存则恢复自动定位。</div>" +
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
                "var draggedItem=null;" +
                "var srcPage=0;var srcFilter='';var SRC_PAGE_SIZE=20;" +
                "function renderSources(){" +
                "  var list=[];" +
                "  for(var i=0;i<sources.length;i++){" +
                "    if(!srcFilter||(sources[i].name+' '+sources[i].url).toLowerCase().indexOf(srcFilter)>=0)list.push(i);" +
                "  }" +
                "  var totalPages=Math.max(1,Math.ceil(list.length/SRC_PAGE_SIZE));" +
                "  if(srcPage>=totalPages)srcPage=totalPages-1;" +
                "  if(srcPage<0)srcPage=0;" +
                "  var pageItems=list.slice(srcPage*SRC_PAGE_SIZE,srcPage*SRC_PAGE_SIZE+SRC_PAGE_SIZE);" +
                "  var html='';" +
                "  for(var j=0;j<pageItems.length;j++){" +
                "    var i=pageItems[j];" +
                "    html+='<div class=\"source-item\" data-index=\"'+i+'\" data-type=\"source\" ondragstart=\"dragStart(event)\" ondragover=\"dragOver(event)\" ondrop=\"drop(event)\" ondragend=\"dragEnd(event)\">';" +
                "    html+='<div class=\"item-header\"><span class=\"drag-handle\" draggable=\"true\" title=\"按住我拖动调整顺序\" onmousedown=\"armDrag(event)\" onmouseup=\"disarmDrag(event)\" ontouchstart=\"armDrag(event)\" ontouchend=\"disarmDrag(event)\">☰</span><span class=\"item-index\">'+(i+1)+'</span><span class=\"drag-tip\">按住左侧 ☰ 上下拖动调整顺序</span></div>';" +
                "    html+='<input type=\"text\" placeholder=\"名称\" value=\"'+sources[i].name+'\" onchange=\"sources['+i+'].name=this.value\">';" +
                "    html+='<input type=\"url\" placeholder=\"直播地址\" value=\"'+sources[i].url+'\" onchange=\"sources['+i+'].url=this.value\">';" +
                "    html+='<div class=\"btn-group\">';" +
                "    html+='<button class=\"btn-up\" onclick=\"moveSourceUp('+i+')\" '+(i===0?'disabled style=\"opacity:0.5\"':'')+'>↑</button>';" +
                "    html+='<button class=\"btn-down\" onclick=\"moveSourceDown('+i+')\" '+(i===sources.length-1?'disabled style=\"opacity:0.5\"':'')+'>↓</button>';" +
                "    html+='<button class=\"btn-del\" onclick=\"delSource('+i+')\">删除</button>';" +
                "    html+='</div></div>';" +
                "  }" +
                "  if(!list.length)html='<div class=\"tip\">无匹配的源</div>';" +
                "  html+='<div style=\"text-align:center;margin-top:8px\">'+"+
                "    '<button onclick=\"srcPage--;renderSources()\">◀ 上一页</button> '+"+
                "    '<span style=\"margin:0 8px\">第 '+(srcPage+1)+' / '+totalPages+' 页 · 共 '+list.length+' 条</span> '+"+
                "    '<button onclick=\"srcPage++;renderSources()\">下一页 ▶</button></div>';" +
                "  document.getElementById('sources').innerHTML=html;" +
                "}" +
                "function srcSearchChanged(v){srcFilter=(v||'').toLowerCase();srcPage=0;renderSources();}" +
                "function renderWebsites(){" +
                "  var html='';" +
                "  for(var i=0;i<websites.length;i++){" +
                "    var en=websites[i].enabled!==false;" +
                "    html+='<div class=\"source-item website-item'+(en?'':' disabled-item')+'\" data-index=\"'+i+'\" data-type=\"website\" ondragstart=\"dragStart(event)\" ondragover=\"dragOver(event)\" ondrop=\"drop(event)\" ondragend=\"dragEnd(event)\">';" +
                "    html+='<div class=\"item-header\"><span class=\"drag-handle\" draggable=\"true\" title=\"按住我拖动调整顺序\" onmousedown=\"armDrag(event)\" onmouseup=\"disarmDrag(event)\" ontouchstart=\"armDrag(event)\" ontouchend=\"disarmDrag(event)\">☰</span><span class=\"item-index\" style=\"background:#2196F3\">'+(i+1)+'</span><span class=\"drag-tip\">按住左侧 ☰ 上下拖动调整顺序</span></div>';" +
                "    html+='<input type=\"text\" placeholder=\"网站名称\" value=\"'+websites[i].name+'\" onchange=\"websites['+i+'].name=this.value\">';" +
                "    html+='<input type=\"url\" placeholder=\"网站地址\" value=\"'+websites[i].url+'\" onchange=\"websites['+i+'].url=this.value\">';" +
                "    html+='<label class=\"enable-label\"><input type=\"checkbox\" '+(en?'checked':'')+' onchange=\"websites['+i+'].enabled=this.checked;renderWebsites();\"> 启用</label>';" +
                "    html+='<div class=\"btn-group\">';" +
                "    html+='<button class=\"btn-up\" onclick=\"moveWebsiteUp('+i+')\" '+(i===0?'disabled style=\"opacity:0.5\"':'')+'>↑</button>';" +
                "    html+='<button class=\"btn-down\" onclick=\"moveWebsiteDown('+i+')\" '+(i===websites.length-1?'disabled style=\"opacity:0.5\"':'')+'>↓</button>';" +
                "    html+='<button class=\"btn-del\" onclick=\"delWebsite('+i+')\">删除</button>';" +
                "    html+='</div></div>';" +
                "  }" +
                "  document.getElementById('websites').innerHTML=html;" +
                "}" +
                "function armDrag(e){var card=e.target.closest('.source-item');if(card)card.draggable=true;}" +
                "function disarmDrag(e){var card=e.target.closest('.source-item');if(card)card.draggable=false;}" +
                "function dragStart(e){var card=e.target.closest('.source-item');if(!card){e.preventDefault();return;}draggedItem=card;card.classList.add('dragging');e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('type',card.dataset.type);}" +
                "function dragOver(e){e.preventDefault();var item=e.target.closest('.source-item');if(item&&item!==draggedItem)item.classList.add('drag-over');}" +
                "function drop(e){e.preventDefault();var item=e.target.closest('.source-item');if(item&&item!==draggedItem){var from=parseInt(draggedItem.dataset.index);var to=parseInt(item.dataset.index);var type=e.dataTransfer.getData('type');if(type==='source'){var t=sources[from];sources.splice(from,1);sources.splice(to,0,t);renderSources();}else{var t=websites[from];websites.splice(from,1);websites.splice(to,0,t);renderWebsites();}}document.querySelectorAll('.source-item').forEach(el=>el.classList.remove('drag-over'));}" +
                "function dragEnd(e){var card=e.target.closest('.source-item');if(card){card.classList.remove('dragging');card.draggable=false;}document.querySelectorAll('.source-item').forEach(el=>el.classList.remove('drag-over'));}" +
                "function moveSourceUp(i){if(i>0){var t=sources[i];sources[i]=sources[i-1];sources[i-1]=t;renderSources();}}" +
                "function moveSourceDown(i){if(i<sources.length-1){var t=sources[i];sources[i+1]=sources[i];sources[i+1]=t;renderSources();}}" +
                "function delSource(i){if(confirm('确定删除？')){sources.splice(i,1);renderSources();}}" +
                "function addSource(){sources.push({name:'',url:''});renderSources();}" +
                "function moveWebsiteUp(i){if(i>0){var t=websites[i];websites[i]=websites[i-1];websites[i-1]=t;renderWebsites();}}" +
                "function moveWebsiteDown(i){if(i<websites.length-1){var t=websites[i];websites[i+1]=websites[i];websites[i+1]=t;renderWebsites();}}" +
                "function delWebsite(i){if(confirm('确定删除？')){websites.splice(i,1);renderWebsites();}}" +
                "function addWebsite(){websites.push({name:'',url:'',enabled:true});renderWebsites();}" +
                "function fetchRemote(){var url=document.getElementById('remoteUrl').value;if(!url){alert('请输入URL');return;}fetch('/proxy?url='+encodeURIComponent(url)).then(r=>r.json()).then(d=>{if(d.error){alert('获取失败:'+d.error);}else if(d.sources){sources=d.sources;renderSources();alert('获取成功');}else{alert('格式错误');}}).catch(e=>alert('获取失败:'+e));}" +
                "function showLocResult(text,lat,lon){" +
                "  var wUrl='https://api.open-meteo.com/v1/forecast?latitude='+lat+'&longitude='+lon+'&current_weather=true';" +
                "  fetch('/proxy?url='+encodeURIComponent(wUrl)).then(r=>r.json()).then(w=>{" +
                "    var t=(w&&w.current_weather)?w.current_weather.temperature:'?';" +
                "    document.getElementById('locationResult').innerHTML='✅ <span style=\"color:#2E7D32;font-weight:bold\">'+text+'</span><br>当前气温: '+t+'°C —— 地区有效，点底部\"保存配置\"即可生效';" +
                "  }).catch(()=>{document.getElementById('locationResult').innerHTML='✅ '+text+'（地区有效，点底部\"保存配置\"生效）';});" +
                "}" +
                "function queryLocation(){" +
                "  var name=document.getElementById('manualLocation').value.trim();" +
                "  var box=document.getElementById('locationResult');" +
                "  if(!name){box.innerHTML='<span style=\"color:#C62828\">请先输入地区名称</span>';return;}" +
                "  box.innerHTML='⏳ 查询中（Open-Meteo未命中会自动尝试OSM乡镇级数据源）...';" +
                "  var omUrl='https://geocoding-api.open-meteo.com/v1/search?name='+encodeURIComponent(name)+'&count=5&language=zh&format=json';" +
                "  fetch('/proxy?url='+encodeURIComponent(omUrl)).then(r=>r.json()).then(om=>{" +
                "    if(om&&om.error){box.innerHTML='<span style=\"color:#C62828\">❌ 数据源连接失败：'+om.error+'</span>';return null;}" +
                "    if(om&&om.results&&om.results.length){var r0=om.results[0];showLocResult('命中: '+r0.name+(r0.admin1?'（'+r0.admin1+'）':''),r0.latitude,r0.longitude);return null;}" +
                "    var phUrl='https://photon.komoot.io/api?q='+encodeURIComponent(name)+'&limit=10';" +
                "    return fetch('/proxy?url='+encodeURIComponent(phUrl)).then(r=>r.json()).then(ph=>{" +
                "      if(ph&&ph.error){box.innerHTML='<span style=\"color:#C62828\">❌ 数据源连接失败：'+ph.error+'</span>';return null;}" +
                "      var hit=null;" +
                "      if(ph&&ph.features){for(var i=0;i<ph.features.length;i++){var f=ph.features[i];var pr=f.properties||{};" +
                "        if(pr.country!=='中国')continue;var nm=pr.name||'';" +
                "        var adm=(pr.osm_key==='place')||['county','city','town','district','state','village','suburb','quarter'].indexOf(pr.type)>=0;" +
                "        var nmMatch=nm&&(nm.indexOf(name)>=0||name.indexOf(nm)>=0);" +
                "        if(adm||nmMatch){hit=f;break;}}}" +
                "      if(hit){var c=hit.geometry.coordinates;showLocResult('命中(OSM乡镇级): '+(hit.properties.name||name),c[1],c[0]);}" +
                "      else{box.innerHTML='<span style=\"color:#C62828\">❌ 未找到该地区——可能不存在，或地名数据源未收录到该乡镇/街道</span>';}" +
                "    });" +
                "  }).catch(e=>{box.innerHTML='<span style=\"color:#C62828\">查询失败: '+e+'</span>';});" +
                "}" +
                "function saveConfig(){var d={sources:sources,websites:websites,remoteUrl:document.getElementById('remoteUrl').value,autoUpdate:document.getElementById('autoUpdate').checked,bufferMin:parseInt(document.getElementById('bufferMin').value)||5000,bufferMax:parseInt(document.getElementById('bufferMax').value)||30000,useWebMode:document.getElementById('useWebMode').checked,playerModeEnabled:document.getElementById('playerModeEnabled').checked,bannerVisible:document.getElementById('bannerVisible').checked,bannerAutoFit:document.getElementById('bannerAutoFit').checked,bannerFontSize:parseInt(document.getElementById('bannerFontSize').value)||13,bannerHeight:parseInt(document.getElementById('bannerHeight').value)||28,manualLocation:document.getElementById('manualLocation').value};fetch('',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(d)}).then(r=>r.json()).then(x=>alert('保存成功！')).catch(e=>alert('保存失败:'+e));}" +
                "renderSources();" +
                "renderWebsites();" +
                "var scanTimer=null;" +
                "function startScan(){" +
                "  var subs=document.getElementById('scanSubs').value.split('\\n').map(function(s){return s.trim();}).filter(function(s){return s.indexOf('http')===0;});" +
                "  if(!subs.length){alert('请先填写订阅URL');return;}" +
                "  if(!confirm('开始优选?将逐条连通验证与测速(约1~3分钟),完成后自动替换直播源列表。'))return;" +
                "  document.getElementById('scanBtn').disabled=true;" +
                "  var q='/scan_start?min='+(parseInt(document.getElementById('scanMin').value)||80)+'&conc='+(parseInt(document.getElementById('scanConc').value)||15)+'&subs='+encodeURIComponent(subs.join('|'));" +
                "  fetch(q).then(function(r){return r.json();}).then(function(d){" +
                "    if(d.status==='error'){alert(d.msg||'启动失败');document.getElementById('scanBtn').disabled=false;return;}" +
                "    if(d.status==='running'){alert('已有优选任务在运行');document.getElementById('scanBtn').disabled=false;return;}" +
                "    pollScan();" +
                "  }).catch(function(e){alert('启动失败:'+e);document.getElementById('scanBtn').disabled=false;});" +
                "}" +
                "function pollScan(){" +
                "  if(scanTimer)clearInterval(scanTimer);" +
                "  scanTimer=setInterval(function(){" +
                "    fetch('/scan_status').then(function(r){return r.json();}).then(function(d){" +
                "      var box=document.getElementById('scanProgress');" +
                "      if(d.error){clearInterval(scanTimer);box.innerHTML='<span style=\"color:#C62828\">❌ '+d.error+'</span>';document.getElementById('scanBtn').disabled=false;return;}" +
                "      if(!d.running&&d.done){clearInterval(scanTimer);box.innerHTML='✅ 优选完成:'+d.channels+' 个频道(中位 '+d.median+' KB/s),已自动替换直播源列表';alert('优选完成:'+d.channels+' 个频道(中位 '+d.median+' KB/s),已自动替换直播源列表并开始播放');setTimeout(function(){location.reload();},1500);return;}" +
                "      var pct=d.total>0?Math.round(d.cur/d.total*100):0;" +
                "      box.innerHTML='⏳ '+d.phase+' '+d.cur+'/'+d.total+' ('+pct+'%) '+(d.msg||'');" +
                "    }).catch(function(){});" +
                "  },1000);" +
                "}" +
                "</script></body></html>";
        }
    }
}
