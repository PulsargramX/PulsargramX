/*
 * This file is a part of Pulsargram X, based on Telegram X.
 * Copyright © 2026 Pulsargram X contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See <https://www.gnu.org/licenses/> for the GNU General Public License.
 */
package org.thunderdog.challegram.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.component.webapp.WebAppActions;
import org.thunderdog.challegram.component.webapp.WebAppActionButton;
import org.thunderdog.challegram.component.webapp.WebAppBridge;
import org.thunderdog.challegram.component.webapp.WebAppBrowserActions;
import org.thunderdog.challegram.component.webapp.WebAppDevice;
import org.thunderdog.challegram.component.webapp.WebAppSession;
import org.thunderdog.challegram.component.webapp.WebAppOrigin;
import org.thunderdog.challegram.component.webapp.WebAppLaunchRequest;
import org.thunderdog.challegram.component.webapp.WebAppTheme;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.navigation.HeaderView;
import org.thunderdog.challegram.navigation.ActivityResultHandler;
import org.thunderdog.challegram.navigation.ViewController;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibCache;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.ColorState;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Drawables;
import org.thunderdog.challegram.support.RippleSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.thunderdog.challegram.component.webapp.WebAppDevice.object;

/** Native Mini App surface. The WebView and callbacks belong to one TDLib session. */
public final class WebAppController extends ViewController<WebAppSession> implements ActivityResultHandler, BaseActivity.PasscodeListener, TdlibCache.UserDataChangeListener {
  private int fileRequestCode = -1;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private FrameLayout root;
  private LinearLayout sheet;
  private LinearLayout header;
  private LinearLayout buttonBar;
  private FrameLayout content;
  private LinearLayout errorView;
  private TextView errorText;
  private TextView title;
  private ImageView back;
  private Button mainButton;
  private Button secondaryButton;
  private ProgressBar mainProgress;
  private ProgressBar secondaryProgress;
  private ProgressBar progress;
  private WebView webView;
  private WebAppBridge bridge;
  private WebAppActions actions;
  private WebAppBrowserActions browserActions;
  private WebAppDevice device;
  private PageHost pageHost;
  private final Map<String, FrameRuntime> frameRuntimes = new HashMap<>();
  private final List<Runnable> initialNativeRequests = new ArrayList<>();
  private boolean hasFocused;
  private ValueCallback<Uri[]> fileCallback;
  private int fileGeneration;
  private PermissionRequest mediaRequest;
  private AlertDialog mediaDialog;
  private AlertDialog closeDialog;
  private AlertDialog menuDialog;
  private View customView;
  private WebChromeClient.CustomViewCallback customViewCallback;
  private long lastGesture;
  private volatile int documentGeneration;
  private volatile boolean destroyed;
  private boolean expanded;
  private boolean fullscreen;
  private boolean minimized;
  private boolean backVisible;
  private boolean settingsVisible;
  private boolean confirmClose;
  private boolean closingByApp;
  private ViewController<?> telegramLinkContext;
  private boolean verticalSwipes = true;
  private boolean pageFailed;
  private boolean activityPaused;
  private int originalOrientation;
  private int initialSystemUiVisibility;
  private boolean orientationLocked;
  private int backgroundColor;
  private int headerColor;
  private int bottomColor;
  private boolean customBackground;
  private boolean customHeader;
  private String headerColorKey = "";
  private boolean customBottom;
  private JSONObject mainState = new JSONObject();
  private JSONObject secondaryState = new JSONObject();
  private Insets safeInsets = Insets.NONE;
  private Runnable minimizeListener;
  private Runnable restoreListener;
  private Runnable timeout;
  private final Runnable stableViewport = () -> emitViewport(true);

  public WebAppController (Context context, Tdlib tdlib) { super(context, tdlib); }

  @Override public int getId () { return R.id.controller_webkit; }
  @Override protected int getHeaderHeight () { return 0; }
  @Override protected int getMaximumHeaderHeight () { return 0; }
  @Override protected int getCustomHeaderHeight () { return 0; }
  @Override protected boolean swipeNavigationEnabled () { return false; }
  @Override public boolean retainOnBackNavigation () {
    return minimized && !getArguments().isClosed();
  }

  @Override
  protected View onCreateView (Context context) {
    context().addPasscodeListener(this);
    tdlib.cache().addUserDataListener(getArguments().request.botUserId, this);
    originalOrientation = context().getRequestedOrientation();
    initialSystemUiVisibility = context().getWindow().getDecorView().getSystemUiVisibility();
    backgroundColor = WebAppTheme.backgroundColor();
    headerColor = backgroundColor;
    bottomColor = backgroundColor;
    root = new FrameLayout(context);
    FrameLayout.LayoutParams rootParams = new FrameLayout.LayoutParams(-1, -1);
    // This surface supplies its own toolbar in NavigationLayout's reserved header space.
    rootParams.topMargin = -HeaderView.getSize(false);
    root.setLayoutParams(rootParams);
    root.setBackgroundColor(0x66000000);
    sheet = new LinearLayout(context);
    sheet.setOrientation(LinearLayout.VERTICAL);
    root.addView(sheet, new FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM));
    header = new LinearLayout(context);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(Screen.dp(4), 0, Screen.dp(4), 0);
    back = iconButton(R.drawable.baseline_close_24, R.string.WebAppRuntimeClose, view -> {
      gesture();
      if (backVisible) emit("back_button_pressed", null); else close(false);
    });
    header.addView(back, new LinearLayout.LayoutParams(Screen.dp(48), Screen.dp(56)));
    title = new TextView(context);
    title.setTextSize(18);
    title.setTypeface(Fonts.getRobotoMedium());
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    title.setCompoundDrawablePadding(Screen.dp(6));
    updateTitle();
    title.setContentDescription(Lang.getString(R.string.WebAppRuntimeExpand));
    title.setOnClickListener(view -> { gesture(); if (minimized) restore(); else expand(); });
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
    ImageView minimize = iconButton(R.drawable.baseline_keyboard_arrow_down_24,
      R.string.WebAppRuntimeMinimize, view -> { gesture(); minimize(); });
    minimize.setVisibility(isAgeVerification() ? View.GONE : View.VISIBLE);
    header.addView(minimize, new LinearLayout.LayoutParams(Screen.dp(48), Screen.dp(56)));
    ImageView more = iconButton(R.drawable.baseline_more_vert_24,
      R.string.WebAppRuntimeMore, view -> { gesture(); showMenu(); });
    more.setVisibility(isAgeVerification() ? View.GONE : View.VISIBLE);
    header.addView(more, new LinearLayout.LayoutParams(Screen.dp(48), Screen.dp(56)));
    sheet.addView(header, new LinearLayout.LayoutParams(-1, Screen.dp(56)));
    progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    sheet.addView(progress, new LinearLayout.LayoutParams(-1, Screen.dp(2)));
    content = new FrameLayout(context);
    sheet.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
    errorView = new LinearLayout(context);
    errorView.setOrientation(LinearLayout.VERTICAL);
    errorView.setGravity(Gravity.CENTER);
    errorView.setPadding(Screen.dp(24), Screen.dp(24), Screen.dp(24), Screen.dp(24));
    errorText = new TextView(context);
    errorText.setGravity(Gravity.CENTER);
    errorText.setTextSize(16);
    errorView.addView(errorText, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-2, Screen.dp(48));
    retryParams.topMargin = Screen.dp(16);
    errorView.addView(textButton(R.string.WebAppRuntimeRetry, view -> { gesture(); reload(); }), retryParams);
    errorView.setVisibility(View.GONE);
    content.addView(errorView, new FrameLayout.LayoutParams(-1, -1));
    buttonBar = new LinearLayout(context);
    buttonBar.setPadding(Screen.dp(8), Screen.dp(4), Screen.dp(8), Screen.dp(4));
    buttonBar.setGravity(Gravity.CENTER);
    mainButton = new WebAppActionButton(context);
    mainButton.setTextSize(16);
    mainButton.setTypeface(Fonts.getRobotoMedium());
    mainButton.setAllCaps(false);
    mainButton.setSingleLine(true);
    mainButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
    mainButton.setOnClickListener(view -> { gesture(); emit("main_button_pressed", null); });
    secondaryButton = new WebAppActionButton(context);
    secondaryButton.setTextSize(16);
    secondaryButton.setTypeface(Fonts.getRobotoMedium());
    secondaryButton.setAllCaps(false);
    secondaryButton.setSingleLine(true);
    secondaryButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
    secondaryButton.setOnClickListener(view -> { gesture(); emit("secondary_button_pressed", null); });
    mainProgress = new ProgressBar(context);
    secondaryProgress = new ProgressBar(context);
    sheet.addView(buttonBar, new LinearLayout.LayoutParams(-1, -2));
    root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
      updateSize();
      emitViewport(false);
      handler.removeCallbacks(stableViewport);
      handler.postDelayed(stableViewport, 120);
    });
    ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
      safeInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
      Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
      root.setPadding(fullscreen ? safeInsets.left : 0, fullscreen ? safeInsets.top : 0,
        fullscreen ? safeInsets.right : 0, Math.max(ime.bottom, fullscreen ? safeInsets.bottom : 0));
      emitSafeArea();
      return insets;
    });
    root.setOnClickListener(view -> { if (!expanded && !fullscreen) close(false); });
    installHeaderSwipe();
    TdApi.WebAppOpenMode mode = getArguments().mode;
    expanded = mode instanceof TdApi.WebAppOpenModeFullSize || mode instanceof TdApi.WebAppOpenModeFullScreen;
    boolean startFullscreen = mode instanceof TdApi.WebAppOpenModeFullScreen;
    updateTheme();
    updateButtons();
    createWebView();
    getArguments().setCloseListener(() -> handler.post(() -> close(true)));
    if (startFullscreen) root.post(() -> setFullscreen(true));
    return root;
  }

  private Button textButton (int resource, View.OnClickListener listener) {
    Button button = new Button(context());
    button.setAllCaps(false);
    button.setText(Lang.getString(resource));
    button.setTextSize(16);
    button.setTypeface(Fonts.getRobotoMedium());
    button.setTextColor(Theme.getColor(ColorId.fillingPositiveContent));
    addThemeTextColorListener(button, ColorId.fillingPositiveContent);
    RippleSupport.setSimpleWhiteBackground(button, ColorId.fillingPositive, 12f, this);
    button.setContentDescription(Lang.getString(resource));
    button.setOnClickListener(listener);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(Screen.dp(24), 0, Screen.dp(24), 0);
    return button;
  }

  private ImageView iconButton (int icon, int description, View.OnClickListener listener) {
    ImageView button = new ImageView(context());
    button.setImageResource(icon);
    button.setScaleType(ImageView.ScaleType.CENTER);
    button.setContentDescription(Lang.getString(description));
    button.setOnClickListener(listener);
    RippleSupport.setTransparentSelector(button, 24f, this);
    return button;
  }

  private void updateTitle () {
    if (title == null) return;
    TdApi.User bot = tdlib.cache().user(getArguments().request.botUserId);
    String name = bot != null ? TD.getUserName(bot) : getArguments().request.botUsername;
    title.setText(name == null || name.isEmpty() ? Lang.getString(R.string.WebAppRuntimeTitle) : name);
    android.graphics.drawable.Drawable badge = null;
    if (bot != null && bot.verificationStatus != null && bot.verificationStatus.isVerified) {
      badge = Drawables.get(context().getResources(), R.drawable.deproko_baseline_verify_24).mutate();
      androidx.core.graphics.drawable.DrawableCompat.setTint(badge, Theme.getColor(ColorId.iconActive));
      badge.setBounds(0, 0, Screen.dp(20), Screen.dp(20));
    }
    androidx.core.widget.TextViewCompat.setCompoundDrawablesRelative(title, null, null, badge, null);
  }

  @Override public void onUserUpdated (TdApi.User user) {
    if (user.id == getArguments().request.botUserId) {
      handler.post(() -> { if (!destroyed) updateTitle(); });
    }
  }

  @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
  private void createWebView () {
    if (destroyed || getArguments().isClosed()) return;
    try {
      webView = new WebView(context());
      WebAppBridge.configureProfile(webView, WebAppDevice.namespace(tdlib, getArguments().request.botUserId));
      WebSettings settings = webView.getSettings();
      settings.setJavaScriptEnabled(true);
      settings.setDomStorageEnabled(true);
      settings.setAllowFileAccess(false);
      settings.setAllowContentAccess(false);
      settings.setAllowFileAccessFromFileURLs(false);
      settings.setAllowUniversalAccessFromFileURLs(false);
      settings.setSupportMultipleWindows(true);
      settings.setJavaScriptCanOpenWindowsAutomatically(false);
      if (Build.VERSION.SDK_INT >= 17) settings.setMediaPlaybackRequiresUserGesture(true);
      if (Build.VERSION.SDK_INT >= 21) {
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
      }
      if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
      webView.setBackgroundColor(backgroundColor);
      webView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        emitViewport(false);
        handler.removeCallbacks(stableViewport);
        handler.postDelayed(stableViewport, 120);
      });
      webView.setOnTouchListener((view, event) -> {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) gesture();
        return false;
      });
      bridge = new WebAppBridge(webView, getArguments().url, this::onBridgeEvent, false, isAgeVerification() || getArguments().requireSameOrigin);
      if (!bridge.install()) {
        releaseWebView();
        showError(R.string.WebAppRuntimeWebViewUpdate);
        return;
      }
      webView.setWebViewClient(new Client());
      webView.setWebChromeClient(new Chrome());
      webView.setDownloadListener((url, userAgent, disposition, mime, size) -> {
        if (pageHost != null && pageHost.hasRecentUserGesture()) {
          browserActions.handle("web_app_request_file_download", object("url", url));
        }
      });
      content.addView(webView, 0, new FrameLayout.LayoutParams(-1, -1));
      load();
    } catch (RuntimeException error) {
      releaseWebView();
      showError(R.string.WebAppRuntimeWebViewUpdate);
    }
  }

  private void load () {
    pageFailed = false;
    errorView.setVisibility(View.GONE);
    progress.setVisibility(View.VISIBLE);
    webView.setVisibility(View.VISIBLE);
    webView.loadUrl(getArguments().url);
  }

  public void reload () {
    if (destroyed || getArguments().isClosed()) return;
    if (webView == null) createWebView(); else load();
  }

  private void startDocument () {
    documentGeneration++;
    destroyPage();
    bridge.newDocument();
    pageHost = new PageHost(documentGeneration, null);
    actions = new WebAppActions(pageHost);
    browserActions = new WebAppBrowserActions(pageHost);
    device = new WebAppDevice(pageHost);
    device.setPaused(activityPaused || minimized || !isFocused() || context().isPasscodeShowing());
    lastGesture = 0;
    closingByApp = false;
    telegramLinkContext = null;
    backVisible = false;
    settingsVisible = false;
    confirmClose = false;
    verticalSwipes = true;
    mainState = new JSONObject();
    secondaryState = new JSONObject();
    customBackground = customHeader = customBottom = false;
    updateTheme();
    updateButtons();
    updateBack();
    pageFailed = false;
    errorView.setVisibility(View.GONE);
    progress.setVisibility(View.VISIBLE);
    int generation = documentGeneration;
    timeout = () -> { if (generation == documentGeneration && !destroyed) showError(R.string.WebAppRuntimeError); };
    handler.postDelayed(timeout, 45000);
  }

  private void destroyPage () {
    if (timeout != null) handler.removeCallbacks(timeout);
    timeout = null;
    for (FrameRuntime runtime : frameRuntimes.values()) runtime.destroy();
    frameRuntimes.clear();
    initialNativeRequests.clear();
    if (actions != null) actions.destroy();
    if (browserActions != null) browserActions.destroy();
    if (device != null) device.destroy();
    actions = null;
    browserActions = null;
    device = null;
    cancelFileChooser();
    denyMedia();
    if (menuDialog != null) { menuDialog.dismiss(); menuDialog = null; }
    if (closeDialog != null) { closeDialog.dismiss(); closeDialog = null; }
    hideCustomView();
  }

  private final class Client extends WebViewClient {
    @Override public void onPageStarted (WebView view, String url, Bitmap favicon) {
      if (view != webView || destroyed) return;
      if (!canLoad(url)) { view.stopLoading(); showError(R.string.WebAppRuntimeError); return; }
      startDocument();
    }
    @Override public void onPageFinished (WebView view, String url) {
      if (view != webView || destroyed || pageFailed || !canLoad(url)) return;
      if (timeout != null) handler.removeCallbacks(timeout);
      progress.setVisibility(View.GONE);
      emitTheme();
      emitViewport(true);
      emitSafeArea();
      emit("fullscreen_changed", object("is_fullscreen", fullscreen));
    }
    @Override public boolean shouldOverrideUrlLoading (WebView view, WebResourceRequest request) {
      return navigation(request.getUrl().toString(), request.isForMainFrame(), request.hasGesture());
    }
    @Override public boolean shouldOverrideUrlLoading (WebView view, String url) {
      return navigation(url, true, hasRecentGesture());
    }
    @Override public void onReceivedError (WebView view, int code, String description, String failingUrl) {
      if (Build.VERSION.SDK_INT < 23 && view == webView) showError(R.string.WebAppRuntimeError);
    }
    @Override public void onReceivedError (WebView view, WebResourceRequest request, WebResourceError error) {
      if (request.isForMainFrame() && view == webView) showError(R.string.WebAppRuntimeError);
    }
    @Override public void onReceivedHttpError (WebView view, WebResourceRequest request, WebResourceResponse response) {
      if (request.isForMainFrame() && response.getStatusCode() >= 400 && view == webView) {
        showError(R.string.WebAppRuntimeError);
      }
    }
    @Override public void onReceivedSslError (WebView view, SslErrorHandler ssl, SslError error) {
      ssl.cancel();
      if (view == webView && error.getUrl().equals(view.getUrl())) showError(R.string.WebAppRuntimeError);
    }
    @Override public boolean onRenderProcessGone (WebView view, RenderProcessGoneDetail detail) {
      if (view == webView) { releaseWebView(); showError(R.string.WebAppRuntimeError); }
      return true;
    }
  }

  private boolean sameOrigin (String url) {
    String expected = WebAppBridge.origin(getArguments().url);
    return expected != null && expected.equals(WebAppBridge.origin(url));
  }

  private boolean mayNavigate (String url) {
    return WebAppOrigin.mayNavigate(getArguments().url, url, isAgeVerification() || getArguments().requireSameOrigin);
  }

  private boolean canLoad (String url) {
    return WebAppOrigin.mayNavigate(getArguments().url, url, isAgeVerification());
  }

  private boolean navigation (String url, boolean mainFrame, boolean userGesture) {
    Uri uri = Uri.parse(url);
    String scheme = uri.getScheme();
    if (!mainFrame) return !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme));
    boolean telegramLink = "tg".equalsIgnoreCase(scheme) || "t.me".equalsIgnoreCase(uri.getHost()) ||
      "telegram.me".equalsIgnoreCase(uri.getHost());
    if (!telegramLink && canLoad(url)) return false;
    if (userGesture || hasRecentGesture()) openExternalUrl(url);
    return true;
  }

  private final class Chrome extends WebChromeClient {
    @Override public void onProgressChanged (WebView view, int value) {
      if (view == webView) progress.setProgress(value);
    }
    @Override public boolean onCreateWindow (WebView view, boolean dialog, boolean userGesture, android.os.Message result) {
      if (!userGesture || !hasRecentGesture()) return false;
      final PageHost popupOwner = pageHost;
      final WebView popup = new WebView(context());
      popup.getSettings().setAllowFileAccess(false);
      popup.getSettings().setAllowContentAccess(false);
      popup.setWebViewClient(new WebViewClient() {
        private boolean consumed;
        private boolean open (String url) {
          if (!consumed && !url.equals("about:blank")) {
            consumed = true;
            if (popupOwner != null && popupOwner.isAlive()) openExternalUrl(url);
            popup.post(popup::destroy);
          }
          return true;
        }
        @Override public boolean shouldOverrideUrlLoading (WebView view, String url) { return open(url); }
        @Override public boolean shouldOverrideUrlLoading (WebView view, WebResourceRequest request) { return open(request.getUrl().toString()); }
      });
      ((WebView.WebViewTransport) result.obj).setWebView(popup);
      result.sendToTarget();
      handler.postDelayed(() -> { try { popup.destroy(); } catch (RuntimeException ignored) { } }, 10000);
      return true;
    }
    @Override public boolean onShowFileChooser (WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
      if (fileCallback != null) { callback.onReceiveValue(null); return true; }
      if (pageHost == null || !pageHost.isAlive() || !hasRecentGesture()) { callback.onReceiveValue(null); return true; }
      fileCallback = callback;
      fileGeneration = documentGeneration;
      Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
      String[] types = params.getAcceptTypes();
      List<String> validTypes = new ArrayList<>();
      for (String type : types) if (type != null && type.matches("[a-zA-Z0-9.+*-]+/[a-zA-Z0-9.+*-]+")) validTypes.add(type);
      intent.setType(validTypes.size() == 1 ? validTypes.get(0) : "*/*");
      if (validTypes.size() > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, validTypes.toArray(new String[0]));
      intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
      try {
        fileRequestCode = context().registerWebAppActivityResultHandler(WebAppController.this);
        context().startActivityForResult(intent, fileRequestCode);
      } catch (RuntimeException ignored) { cancelFileChooser(); }
      return true;
    }
    @Override public void onPermissionRequest (PermissionRequest request) { requestMedia(request); }
    @Override public void onPermissionRequestCanceled (PermissionRequest request) { if (mediaRequest == request) denyMedia(); }
    @Override public void onGeolocationPermissionsShowPrompt (String origin, GeolocationPermissions.Callback callback) {
      // Mini Apps use the consent-aware LocationManager bridge instead of raw browser geolocation.
      callback.invoke(origin, false, false);
    }
    @Override public void onShowCustomView (View view, CustomViewCallback callback) {
      if (!hasRecentGesture() || customView != null) { callback.onCustomViewHidden(); return; }
      customView = view;
      customViewCallback = callback;
      root.addView(view, new FrameLayout.LayoutParams(-1, -1));
    }
    @Override public void onHideCustomView () { hideCustomView(); }
  }

  private void requestMedia (PermissionRequest request) {
    if (pageHost == null || !pageHost.isAlive() || !hasRecentGesture() || mediaRequest != null ||
        !mayNavigate(request.getOrigin().toString())) { request.deny(); return; }
    List<String> permissions = new ArrayList<>();
    List<String> resources = new ArrayList<>();
    for (String resource : request.getResources()) {
      if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
        permissions.add(Manifest.permission.CAMERA); resources.add(resource);
      } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
        permissions.add(Manifest.permission.RECORD_AUDIO); resources.add(resource);
      }
    }
    if (resources.isEmpty()) { request.deny(); return; }
    mediaRequest = request;
    PageHost target = pageHost;
    mediaDialog = new AlertDialog.Builder(context()).setTitle(R.string.WebAppRuntimeMediaTitle)
      .setMessage(R.string.WebAppRuntimeMediaMessage)
      .setPositiveButton(R.string.WebAppRuntimeAllow, (dialog, which) -> {
        if (mediaRequest != request) return;
        if (!target.isAlive()) { denyMedia(); return; }
        context().requestCustomPermissions(permissions.toArray(new String[0]), (code, names, results, count) -> {
          if (mediaRequest != request) return;
        if (!target.isAlive()) { denyMedia(); return; }
          List<String> allowed = new ArrayList<>();
          for (int i = 0; i < permissions.size(); i++) {
            if (ContextCompat.checkSelfPermission(context(), permissions.get(i)) == PackageManager.PERMISSION_GRANTED) {
              allowed.add(resources.get(i));
            }
          }
          mediaRequest = null;
          if (allowed.isEmpty()) request.deny(); else request.grant(allowed.toArray(new String[0]));
        });
      }).setNegativeButton(R.string.WebAppRuntimeCancel, (dialog, which) -> denyMedia()).create();
    mediaDialog.setOnCancelListener(dialog -> denyMedia());
    mediaDialog.show();
  }

  private void denyMedia () {
    PermissionRequest pending = mediaRequest;
    mediaRequest = null;
    if (pending != null) pending.deny();
    if (mediaDialog != null) { mediaDialog.setOnCancelListener(null); mediaDialog.dismiss(); mediaDialog = null; }
  }

  private void cancelFileChooser () {
    if (fileRequestCode != -1) {
      context().removeActivityResultHandler(fileRequestCode, this);
      try { context().finishActivity(fileRequestCode); } catch (RuntimeException ignored) { }
      fileRequestCode = -1;
    }
    ValueCallback<Uri[]> callback = fileCallback;
    fileCallback = null;
    if (callback != null) callback.onReceiveValue(null);
  }

  @Override public void onPasscodeShowing (BaseActivity activity, boolean showing) {
    if (destroyed || root == null) return;
    if (showing) {
      documentGeneration++;
      lastGesture = 0;
      destroyPage();
      pauseWebView();
      pageHost = null;
    } else {
      pageHost = new PageHost(documentGeneration, null);
      actions = new WebAppActions(pageHost);
      browserActions = new WebAppBrowserActions(pageHost);
      device = new WebAppDevice(pageHost);
      device.setPaused(activityPaused || minimized || !isFocused() || context().isPasscodeShowing());
      if (isFocused()) resumeWebView();
    }
  }

  @Override public void onActivityResult (int code, int result, Intent data) {
    if (code == fileRequestCode) {
      context().removeActivityResultHandler(fileRequestCode, this);
      fileRequestCode = -1;
      ValueCallback<Uri[]> callback = fileCallback;
      fileCallback = null;
      if (callback == null) return;
      if (destroyed || fileGeneration != documentGeneration || pageHost == null || !pageHost.isAlive() || result != Activity.RESULT_OK) {
        callback.onReceiveValue(null); return;
      }
      List<Uri> uris = new ArrayList<>();
      if (data != null && data.getClipData() != null) {
        for (int i = 0; i < Math.min(data.getClipData().getItemCount(), 100); i++) {
          Uri uri = data.getClipData().getItemAt(i).getUri();
          if (uri != null && "content".equals(uri.getScheme())) uris.add(uri);
        }
      } else if (data != null && data.getData() != null && "content".equals(data.getData().getScheme())) {
        uris.add(data.getData());
      }
      callback.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
    } else {
      if (actions != null) actions.onActivityResult(code, result, data);
      for (FrameRuntime runtime : new ArrayList<>(frameRuntimes.values())) {
        if (runtime.host.isAlive()) runtime.actions.onActivityResult(code, result, data);
      }
    }
  }

  private void onBridgeEvent (String event, JSONObject data) {
    if (pageHost == null || !pageHost.isAlive()) return;
    try {
      switch (event) {
        case "web_app_ready":
          if (timeout != null) handler.removeCallbacks(timeout);
          progress.setVisibility(View.GONE);
          return;
        case "web_app_request_theme": emitTheme(); return;
        case "web_app_request_viewport": emitViewport(true); return;
        case "web_app_request_safe_area":
        case "web_app_request_content_safe_area": emitSafeArea(); return;
        case "web_app_expand": expand(); return;
        case "web_app_close":
          if (canInteract()) {
            closingByApp = telegramLinkContext != null;
            close(false);
          }
          return;
        case "web_app_request_fullscreen": if (canInteract()) setFullscreen(true); return;
        case "web_app_exit_fullscreen": if (canInteract()) setFullscreen(false); return;
        case "web_app_setup_back_button": backVisible = data.optBoolean("is_visible"); updateBack(); return;
        case "web_app_setup_settings_button": settingsVisible = data.optBoolean("is_visible"); return;
        case "web_app_setup_closing_behavior": confirmClose = data.optBoolean("need_confirmation"); return;
        case "web_app_allow_scroll": verticalSwipes = data.optBoolean("y", true); return;
        case "web_app_setup_swipe_behavior": verticalSwipes = data.optBoolean("allow_vertical_swipe", true); return;
        case "web_app_setup_main_button": mainState = data; updateButtons(); return;
        case "web_app_setup_secondary_button": secondaryState = data; updateButtons(); return;
        case "web_app_set_background_color":
          backgroundColor = parseColor(data.optString("color"), backgroundColor); customBackground = true; updateTheme(); return;
        case "web_app_set_header_color":
          String key = data.optString("color_key");
          headerColorKey = key;
          headerColor = key.equals("bg_color") || key.equals("secondary_bg_color") ?
            WebAppTheme.backgroundColor() : parseColor(data.optString("color"), headerColor);
          customHeader = true; updateTheme(); return;
        case "web_app_set_bottom_bar_color":
          bottomColor = parseColor(data.optString("color"), bottomColor); customBottom = true; updateTheme(); return;
        case "web_app_verify_age": if (canInteract()) getArguments().verifyAge(data); return;
        case "web_app_data_send":
          if (!canInteract()) return;
          String value = data.optString("data");
          if (!value.isEmpty() && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4096) getArguments().sendData(value);
          return;
        case "web_app_hide_keyboard":
          if (!canInteract()) return;
          ((InputMethodManager) context().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0);
          return;
        case "web_app_toggle_orientation_lock":
          if (!canInteract()) return;
          orientationLocked = data.optBoolean("locked");
          context().setRequestedOrientation(orientationLocked ? ActivityInfo.SCREEN_ORIENTATION_LOCKED : originalOrientation);
          return;
        default:
          if (!canInteract() && (hasFocused || minimized || activityPaused || context().isPasscodeShowing())) return;
          String frame = bridge.requestingDocument();
          if (frame == null) return;
          for (String previous : new ArrayList<>(frameRuntimes.keySet())) {
            FrameRuntime old = frameRuntimes.get(previous);
            if (!old.host.isAlive()) { frameRuntimes.remove(previous); old.destroy(); }
          }
          FrameRuntime runtime = frameRuntimes.get(frame);
          if (runtime == null) {
            if (frameRuntimes.size() >= 32) return;
            runtime = new FrameRuntime(frame);
            frameRuntimes.put(frame, runtime);
          }
          if (!canInteract()) {
            if (initialNativeRequests.size() < 64) {
              final FrameRuntime initialRuntime = runtime;
              initialNativeRequests.add(() -> dispatchNative(initialRuntime, event, data));
            }
            return;
          }
          dispatchNative(runtime, event, data);
      }
    } catch (RuntimeException ignored) {
      // A bad request must not crash or compromise the native host.
    }
  }

  private void dispatchNative (FrameRuntime runtime, String event, JSONObject data) {
    if (!canInteract() || !runtime.host.isAlive()) return;
    if (runtime.device.handle(event, data) || runtime.browser.handle(event, data)) return;
    runtime.actions.handle(event, data);
  }

  private boolean isAgeVerification () {
    return getArguments().request.source == WebAppLaunchRequest.Source.AGE_VERIFICATION;
  }

  private boolean canInteract () {
    return !destroyed && !minimized && !activityPaused && isFocused() && tdlib.isCurrent() &&
      !context().isPasscodeShowing();
  }

  private void gesture () { lastGesture = SystemClock.elapsedRealtime(); }
  private boolean hasRecentGesture () {
    return canInteract() && lastGesture != 0 && SystemClock.elapsedRealtime() - lastGesture < 10000;
  }

  public void emit (String event, JSONObject data) {
    if (!destroyed && bridge != null && !getArguments().isClosed()) bridge.emit(event, data);
  }

  private void emitTheme () { emit("theme_changed", object("theme_params", themeParameters())); }

  public static JSONObject themeParameters () {
    return WebAppTheme.json();
  }
  private static int parseColor (String value, int fallback) {
    return value.matches("#[0-9a-fA-F]{6}") ? Color.parseColor(value) : fallback;
  }

  private void emitViewport (boolean stable) {
    if (webView == null || root == null) return;
    float density = context().getResources().getDisplayMetrics().density;
    emit("viewport_changed", object("height", webView.getHeight() / density,
      "width", webView.getWidth() / density, "is_expanded", expanded || fullscreen, "is_state_stable", stable));
  }

  private void emitSafeArea () {
    // The controller consumes system and native chrome insets; the WebView's content starts below them.
    emit("safe_area_changed", object("top", 0, "bottom", 0, "left", 0, "right", 0));
    emit("content_safe_area_changed", object("top", 0, "bottom", 0, "left", 0, "right", 0));
  }

  private void updateTheme () {
    if (root == null) return;
    if (!customBackground) backgroundColor = WebAppTheme.backgroundColor();
    if (!customHeader || headerColorKey.equals("bg_color") || headerColorKey.equals("secondary_bg_color")) {
      headerColor = WebAppTheme.backgroundColor();
    }
    if (!customBottom) bottomColor = WebAppTheme.backgroundColor();
    sheet.setBackgroundColor(backgroundColor);
    content.setBackgroundColor(backgroundColor);
    errorView.setBackgroundColor(backgroundColor);
    errorText.setTextColor(Theme.getColor(ColorId.text));
    header.setBackgroundColor(headerColor);
    int headerText = androidx.core.graphics.ColorUtils.calculateLuminance(headerColor) > .45 ? Color.BLACK : Color.WHITE;
    title.setTextColor(headerText);
    for (int i = 0; i < header.getChildCount(); i++) {
      if (header.getChildAt(i) instanceof ImageView) ((ImageView) header.getChildAt(i)).setColorFilter(headerText);
    }
    buttonBar.setBackgroundColor(bottomColor);
    if (webView != null) webView.setBackgroundColor(backgroundColor);
    updateButtons();
  }

  @Override public void onThemeColorsChanged (boolean temporary, ColorState state) {
    super.onThemeColorsChanged(temporary, state);
    updateTheme();
    updateTitle();
    emitTheme();
  }

  private void updateBack () {
    back.setImageResource(backVisible ? R.drawable.baseline_arrow_back_24 : R.drawable.baseline_close_24);
    back.setContentDescription(Lang.getString(backVisible ? R.string.WebAppRuntimeBack : R.string.WebAppRuntimeClose));
    context().notifyBackPressAvailabilityChanged();
  }

  private void updateButtons () {
    if (buttonBar == null) return;
    buttonBar.removeAllViews();
    boolean main = mainState.optBoolean("is_visible") && !mainState.optString("text").trim().isEmpty();
    boolean secondary = secondaryState.optBoolean("is_visible") && !secondaryState.optString("text").trim().isEmpty();
    String position = secondaryState.optString("position", "left");
    boolean stacked = position.equals("top") || position.equals("bottom");
    buttonBar.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
    if (secondary && (position.equals("left") || position.equals("top"))) addActionButton(secondaryButton, secondaryProgress, secondaryState, stacked);
    if (main) addActionButton(mainButton, mainProgress, mainState, stacked);
    if (secondary && !(position.equals("left") || position.equals("top"))) addActionButton(secondaryButton, secondaryProgress, secondaryState, stacked);
    buttonBar.setVisibility(!minimized && (main || secondary) ? View.VISIBLE : View.GONE);
    emitViewport(false);
  }

  private void addActionButton (Button button, ProgressBar spinner, JSONObject state, boolean stacked) {
    if (button.getParent() instanceof ViewGroup) ((ViewGroup) button.getParent()).removeView(button);
    if (spinner.getParent() instanceof ViewGroup) ((ViewGroup) spinner.getParent()).removeView(spinner);
    FrameLayout wrap = new FrameLayout(context());
    String text = state.optString("text");
    button.setText(text.substring(0, Math.min(text.length(), 64)));
    boolean spinning = state.optBoolean("is_progress_visible");
    button.setEnabled(state.optBoolean("is_active", false) && !spinning);
    ((WebAppActionButton) button).setShine(state.optBoolean("has_shine_effect") && !spinning);
    int color = parseColor(state.optString("color"), Theme.getColor(ColorId.fillingPositive));
    int textColor = parseColor(state.optString("text_color"), Theme.getColor(ColorId.fillingPositiveContent));
    button.setTextColor(textColor);
    android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
    shape.setColor(color);
    shape.setCornerRadius(Screen.dp(8));
    button.setBackground(shape);
    button.setAlpha(button.isEnabled() || spinning ? 1f : .5f);
    wrap.addView(button, new FrameLayout.LayoutParams(-1, -1));
    spinner.setVisibility(spinning ? View.VISIBLE : View.GONE);
    FrameLayout.LayoutParams spinnerParams = new FrameLayout.LayoutParams(Screen.dp(22), Screen.dp(22), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    spinnerParams.rightMargin = Screen.dp(10);
    wrap.addView(spinner, spinnerParams);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(stacked ? -1 : 0, Screen.dp(48), stacked ? 0f : 1f);
    params.setMargins(Screen.dp(4), Screen.dp(4), Screen.dp(4), Screen.dp(4));
    buttonBar.addView(wrap, params);
  }

  private void updateSize () {
    if (root == null || root.getHeight() == 0) return;
    int height = minimized ? Screen.dp(56) : expanded || fullscreen ? -1 : Math.max(Screen.dp(240), (int) (root.getHeight() * .62f));
    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) sheet.getLayoutParams();
    if (params.height != height) { params.height = height; sheet.setLayoutParams(params); }
  }

  private void expand () {
    expanded = true;
    if (minimized) restore();
    updateSize();
    emitViewport(true);
  }

  public void setMinimizeListener (Runnable listener) { minimizeListener = listener; }
  public void setRestoreListener (Runnable listener) { restoreListener = listener; }
  public boolean isMinimized () { return minimized; }

  public void minimize () {
    if (destroyed || minimized || isAgeVerification()) return;
    minimized = true;
    if (fullscreen) setFullscreen(false);
    content.setVisibility(View.GONE);
    progress.setVisibility(View.GONE);
    buttonBar.setVisibility(View.GONE);
    pauseWebView();
    emit("visibility_changed", object("is_visible", false));
    updateSize();
    if (minimizeListener != null) minimizeListener.run();
    tdlib.webApps().minimize(this);
  }

  public void restore () {
    if (destroyed) return;
    minimized = false;
    content.setVisibility(View.VISIBLE);
    updateButtons();
    updateSize();
    resumeWebView();
    emit("visibility_changed", object("is_visible", true));
    emitViewport(true);
    if (restoreListener != null) restoreListener.run();
  }

  private void setFullscreen (boolean value) {
    if (destroyed || root == null) return;
    fullscreen = value;
    if (value) expanded = true;
    WindowInsetsControllerCompat controller = new WindowInsetsControllerCompat(context().getWindow(), root);
    if (value) {
      controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      controller.hide(WindowInsetsCompat.Type.systemBars());
    } else {
      controller.show(WindowInsetsCompat.Type.systemBars());
    }
    // Keep a trusted native close/back affordance visible even in fullscreen mode.
    updateSize();
    ViewCompat.requestApplyInsets(root);
    emit("fullscreen_changed", object("is_fullscreen", value));
    emitViewport(true);
    emitSafeArea();
  }

  @SuppressLint("ClickableViewAccessibility")
  private void installHeaderSwipe () {
    final float[] down = new float[2];
    header.setOnTouchListener((view, event) -> {
      if (!verticalSwipes || fullscreen) return false;
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { down[0] = event.getRawY(); down[1] = event.getRawX(); return true; }
      if (event.getActionMasked() == MotionEvent.ACTION_UP) {
        float distance = event.getRawY() - down[0];
        if (Math.abs(event.getRawX() - down[1]) > Math.abs(distance)) return false;
        if (distance < -Screen.dp(48)) expand();
        else if (distance > Screen.dp(72)) { if (expanded) { expanded = false; updateSize(); } else minimize(); }
        return true;
      }
      return true;
    });
  }

  @Override public boolean performOnBackPressed (boolean fromTop, boolean commit) {
    super.performOnBackPressed(fromTop, commit);
    if (!commit) return true;
    if (customView != null) hideCustomView();
    else if (backVisible) emit("back_button_pressed", null);
    else if (fullscreen) setFullscreen(false);
    else close(false);
    return true;
  }

  public void close (boolean force) {
    if (destroyed) return;
    if (!force && confirmClose) {
      if (closeDialog != null) return;
      closeDialog = new AlertDialog.Builder(context()).setTitle(R.string.WebAppRuntimeCloseTitle)
        .setMessage(R.string.WebAppRuntimeCloseMessage)
        .setPositiveButton(R.string.WebAppRuntimeClose, (dialog, which) -> close(true))
        .setNegativeButton(R.string.WebAppRuntimeCancel,
          (dialog, which) -> closingByApp = false).create();
      closeDialog.setOnCancelListener(dialog -> closingByApp = false);
      closeDialog.setOnDismissListener(dialog -> closeDialog = null);
      closeDialog.show();
      return;
    }
    getArguments().setCloseListener(null);
    getArguments().close();
    org.thunderdog.challegram.navigation.NavigationController navigation = context().navigation();
    if (navigation.isAnimating()) {
      handler.postDelayed(() -> close(true), 80);
      return;
    }
    if (navigation.getCurrentStackItem() == this) {
      if (!navigateBack()) handler.postDelayed(() -> close(true), 80);
    } else {
      if (isAttachedToNavigationController()) navigation.removeChildWrapper(this);
      if (!navigation.getStack().destroy(this)) destroy();
    }
  }

  private void showMenu () {
    final String publicUrl = getArguments().request.publicLaunchUrl(tdlib);
    List<String> labels = new ArrayList<>();
    if (settingsVisible) labels.add(Lang.getString(R.string.WebAppRuntimeSettings));
    labels.add(Lang.getString(R.string.WebAppRuntimeReload));
    if (publicUrl != null) labels.add(Lang.getString(R.string.WebAppRuntimeOpenBrowser));
    final boolean settings = settingsVisible;
    if (menuDialog != null) return;
    menuDialog = new AlertDialog.Builder(context()).setItems(labels.toArray(new String[0]), (dialog, index) -> {
      if (destroyed) return;
      gesture();
      if (settings && index == 0) emit("settings_button_pressed", null);
      else if (index == (settings ? 1 : 0)) reload();
      else if (publicUrl != null) org.thunderdog.challegram.tool.Intents.openUriInBrowser(Uri.parse(publicUrl));
    }).create();
    menuDialog.setOnDismissListener(dialog -> menuDialog = null);
    menuDialog.show();
  }

  public void openExternalUrl (String value) {
    if (destroyed || value == null || value.length() > 16384) return;
    Uri uri;
    try { uri = Uri.parse(value); } catch (RuntimeException ignored) { return; }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http") ||
        scheme.equalsIgnoreCase("tg") || scheme.equalsIgnoreCase("mailto") || scheme.equalsIgnoreCase("tel"))) return;
    if ((scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")) && WebAppBridge.origin(value) == null) return;
    try {
      if (scheme.equalsIgnoreCase("tg") || "t.me".equalsIgnoreCase(uri.getHost()) || "telegram.me".equalsIgnoreCase(uri.getHost())) {
        telegramLinkContext = previousStackItem();
        tdlib.ui().openUrl(this, value, null);
      } else context().startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
    } catch (RuntimeException ignored) { }
  }

  /** Keep a requested Mini App link alive when the sending app explicitly closes its view. */
  public ViewController<?> getWebAppLinkFallback () {
    if (closingByApp && getArguments().isClosed() && telegramLinkContext != null &&
        !telegramLinkContext.isDestroyed() && telegramLinkContext.tdlib() == tdlib &&
        context().navigation().getCurrentStackItem() == telegramLinkContext) {
      return telegramLinkContext;
    }
    return null;
  }

  private void showError (int message) {
    if (destroyed || errorView == null) return;
    pageFailed = true;
    backVisible = false;
    confirmClose = false;
    updateBack();
    documentGeneration++;
    destroyPage();
    if (bridge != null) bridge.newDocument();
    if (timeout != null) handler.removeCallbacks(timeout);
    progress.setVisibility(View.GONE);
    errorText.setText(message);
    errorView.setVisibility(View.VISIBLE);
    if (webView != null) webView.setVisibility(View.GONE);
  }

  private void hideCustomView () {
    if (customView != null) { root.removeView(customView); customView = null; }
    if (customViewCallback != null) {
      WebChromeClient.CustomViewCallback callback = customViewCallback;
      customViewCallback = null;
      callback.onCustomViewHidden();
    }
  }

  private void pauseWebView () {
    if (webView != null) webView.onPause();
    if (device != null) device.setPaused(true);
    for (FrameRuntime runtime : frameRuntimes.values()) runtime.device.setPaused(true);
  }

  private void resumeWebView () {
    if (minimized || activityPaused || context().isPasscodeShowing() || !tdlib.isCurrent()) return;
    if (webView != null) webView.onResume();
    if (device != null) device.setPaused(false);
    for (FrameRuntime runtime : frameRuntimes.values()) runtime.device.setPaused(false);
  }

  @Override public void onFocus () {
    super.onFocus();
    hasFocused = true;
    for (Runnable request : new ArrayList<>(initialNativeRequests)) request.run();
    initialNativeRequests.clear();
    if (fullscreen) setFullscreen(true);
    if (orientationLocked) context().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
    resumeWebView();
    emit("visibility_changed", object("is_visible", !minimized));
  }
  @Override public void onBlur () {
    super.onBlur();
    pauseWebView();
    if (orientationLocked) context().setRequestedOrientation(originalOrientation);
    if (fullscreen && root != null) {
      new WindowInsetsControllerCompat(context().getWindow(), root).show(WindowInsetsCompat.Type.systemBars());
    }
    emit("visibility_changed", object("is_visible", false));
  }
  @Override public void onActivityPause () { super.onActivityPause(); activityPaused = true; pauseWebView(); emit("visibility_changed", object("is_visible", false)); }
  @Override public void onActivityResume () { super.onActivityResume(); activityPaused = false; if (isFocused()) { resumeWebView(); emit("visibility_changed", object("is_visible", !minimized)); } }
  @Override public void onConfigurationChanged (Configuration config) { super.onConfigurationChanged(config); updateSize(); emitViewport(true); emitSafeArea(); }

  private void releaseWebView () {
    documentGeneration++;
    destroyPage();
    if (bridge != null) { bridge.destroy(); bridge = null; }
    if (webView != null) {
      WebView old = webView;
      webView = null;
      if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
      old.stopLoading();
      old.setWebChromeClient(null);
      old.setWebViewClient(new WebViewClient());
      old.destroy();
    }
  }

  @Override public void destroy () {
    if (destroyed) return;
    destroyed = true;
    context().removePasscodeListener(this);
    tdlib.cache().removeUserDataListener(getArguments().request.botUserId, this);
    handler.removeCallbacksAndMessages(null);
    getArguments().setCloseListener(null);
    releaseWebView();
    if (closeDialog != null) closeDialog.dismiss();
    if (isFocused()) {
      if (orientationLocked) context().setRequestedOrientation(originalOrientation);
      context().getWindow().getDecorView().setSystemUiVisibility(initialSystemUiVisibility);
      if (fullscreen && root != null) new WindowInsetsControllerCompat(context().getWindow(), root).show(WindowInsetsCompat.Type.systemBars());
    }
    getArguments().close();
    super.destroy();
  }

  private final class FrameRuntime {
    final PageHost host;
    final WebAppActions actions;
    final WebAppBrowserActions browser;
    final WebAppDevice device;
    FrameRuntime (String document) {
      host = new PageHost(documentGeneration, document);
      actions = new WebAppActions(host);
      browser = new WebAppBrowserActions(host);
      device = new WebAppDevice(host);
      device.setPaused(activityPaused || minimized || !isFocused() || context().isPasscodeShowing());
    }
    void destroy () { actions.destroy(); browser.destroy(); device.destroy(); }
  }

  private final class PageHost implements WebAppActions.Host {
    private final int generation;
    private final String document;
    PageHost (int generation, String document) { this.generation = generation; this.document = document; }
    @Override public Context context () { return WebAppController.this.context(); }
    @Override public Activity activity () { return WebAppController.this.context(); }
    @Override public Tdlib tdlib () { return tdlib; }
    @Override public WebAppSession session () { return getArguments(); }
    @Override public WebView webView () { return webView; }
    @Override public ViewController<?> controller () { return WebAppController.this; }
    @Override public boolean isAlive () {
      return !destroyed && tdlib.isCurrent() && generation == documentGeneration && !getArguments().isClosed() &&
        bridge != null && bridge.hasDocument(document);
    }
    @Override public boolean hasRecentUserGesture () { return isAlive() && hasRecentGesture(); }
    @Override public void onUserGesture () { if (isAlive()) gesture(); }
    @Override public void emit (String event, JSONObject data) { if (isAlive()) bridge.emitTo(document, event, data); }
    @Override public void close (boolean force) { if (isAlive()) WebAppController.this.close(force); }
    @Override public void openExternalUrl (String url) { if (isAlive()) WebAppController.this.openExternalUrl(url); }
    // Cleanup must still run after the page closes; callers check isAlive before native side effects.
    @Override public void runOnUiThread (Runnable action) { org.thunderdog.challegram.tool.UI.post(action); }
  }
}
