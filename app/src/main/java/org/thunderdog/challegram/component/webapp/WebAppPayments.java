/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */
package org.thunderdog.challegram.component.webapp;

import android.app.AlertDialog;
import android.net.Uri;
import android.text.InputType;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.drinkless.tdlib.TdApi;
import org.json.JSONObject;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.telegram.MessageListener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checkout owns its credentials and WebView; no payment data enters the Mini App bridge. */
final class WebAppPayments {
  private final WebAppActions actions;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private String slug;
  private TdApi.InputInvoice invoice;
  private TdApi.PaymentForm form;
  private String orderInfoId = "", shippingId = "";
  private long shippingAmount;
  private TdApi.InputCredentials credentials;
  private WebView providerView;
  private WebAppBridge providerBridge;
  private AlertDialog providerDialog;
  private long receiptChatId;
  private String paymentProfile;
  private WebAppStarTopUp topUp;
  private boolean submitting;
  private int generation;
  private volatile HttpURLConnection connection;

  WebAppPayments (WebAppActions actions) { this.actions = actions; }
  private void send (TdApi.Function<?> function, java.util.function.Consumer<TdApi.Object> callback) {
    int token = generation;
    actions.send(function, result -> { if (active() && generation == token) callback.accept(result); });
  }
  private boolean active () { return actions.alive() && slug != null; }
  private final MessageListener receiptListener = new MessageListener() {
    @Override public void onNewMessage (TdApi.Message message) {
      if (message.content instanceof TdApi.MessagePaymentSuccessful) {
        String paidSlug = ((TdApi.MessagePaymentSuccessful) message.content).invoiceName;
        actions.host.runOnUiThread(() -> {
          if (active() && submitting && (invoice instanceof TdApi.InputInvoiceName && slug.equals(paidSlug) ||
              invoice instanceof TdApi.InputInvoiceMessage &&
              ((TdApi.MessagePaymentSuccessful) message.content).invoiceChatId == ((TdApi.InputInvoiceMessage) invoice).chatId &&
              ((TdApi.MessagePaymentSuccessful) message.content).invoiceMessageId == ((TdApi.InputInvoiceMessage) invoice).messageId)) complete("paid");
        });
      }
    }
  };

  void open (String value) {
    open(new TdApi.InputInvoiceName(value));
  }
  void open (TdApi.InputInvoice inputInvoice) {
    String value = inputInvoice instanceof TdApi.InputInvoiceName ? ((TdApi.InputInvoiceName) inputInvoice).name : "native";
    if (inputInvoice instanceof TdApi.InputInvoiceTelegram && !me.vkryl.android.AppInstallationUtil.isAppSideLoaded(actions.host.context())) {
      android.widget.Toast.makeText(actions.host.context(), R.string.WebAppStarsTopUpStoreUnavailable, android.widget.Toast.LENGTH_LONG).show();
      actions.emit("invoice_closed", WebAppActions.object("slug", value, "status", "failed")); return;
    }
    if (slug != null || value.isEmpty() || value.length() > 512 || !actions.host.hasRecentUserGesture()) {
      actions.emit("invoice_closed", WebAppActions.object("slug", value, "status", "cancelled")); return;
    }
    slug = value; generation++;
    invoice = inputInvoice;
    send(new TdApi.GetPaymentForm(invoice, null), result -> {
      if (!active()) return;
      if (!(result instanceof TdApi.PaymentForm)) { fail(result); return; }
      form = (TdApi.PaymentForm) result;
      send(new TdApi.CreatePrivateChat(form.sellerBotUserId, false), chat -> {
        if (!active()) return;
        if (chat instanceof TdApi.Chat) {
          receiptChatId = ((TdApi.Chat) chat).id;
          actions.host.tdlib().listeners().subscribeToMessageUpdates(receiptChatId, receiptListener);
        }
        if (form.type instanceof TdApi.PaymentFormTypeRegular) orderInfo((TdApi.PaymentFormTypeRegular) form.type);
        else checkout();
      });
    });
  }
  private void fail (TdApi.Object result) {
    if (result instanceof TdApi.Error && "BALANCE_TOO_LOW".equals(((TdApi.Error) result).message) &&
        form != null && (form.type instanceof TdApi.PaymentFormTypeStars || form.type instanceof TdApi.PaymentFormTypeStarSubscription) &&
        !(invoice instanceof TdApi.InputInvoiceTelegram)) {
      submitting = false;
      actions.confirm(R.string.WebAppStarsTopUpTitle, actions.host.context().getString(R.string.WebAppStarsTopUpText),
        R.string.WebAppContinue, this::topUp, () -> complete("cancelled"));
      return;
    }
    actions.error(result); complete("failed");
  }
  private void topUp () {
    if (!active() || topUp != null) return;
    int token = generation;
    topUp = new WebAppStarTopUp(actions, status -> {
      topUp = null;
      if (!active() || token != generation) return;
      if ("paid".equals(status)) refreshAfterTopUp();
      else if ("pending".equals(status)) {
        actions.confirm(R.string.WebAppStarsTopUpTitle, actions.host.context().getString(R.string.WebAppStarsTopUpPending),
          R.string.WebAppContinue, this::refreshAfterTopUp, () -> complete("cancelled"));
      } else complete(status);
    });
    topUp.open(receiptChatId);
  }
  private void refreshAfterTopUp () {
    if (!active()) return;
    send(new TdApi.GetPaymentForm(invoice, null), result -> {
      if (!(result instanceof TdApi.PaymentForm)) { fail(result); return; }
      form = (TdApi.PaymentForm) result;
      // Display the current amount and require another explicit Pay action; never retry the charge here.
      if (form.type instanceof TdApi.PaymentFormTypeRegular) orderInfo((TdApi.PaymentFormTypeRegular) form.type);
      else checkout();
    });
  }
  private void complete (String status) {
    if (slug == null) return;
    String completed = slug; slug = null; generation++;
    cleanupProvider(); forgetProfile();
    if (topUp != null) { topUp.destroy(); topUp = null; }
    if (receiptChatId != 0) actions.host.tdlib().listeners().unsubscribeFromMessageUpdates(receiptChatId, receiptListener);
    receiptChatId = 0; submitting = false; form = null; invoice = null; credentials = null;
    orderInfoId = ""; shippingId = ""; shippingAmount = 0;
    actions.emit("invoice_closed", WebAppActions.object("slug", completed, "status", status));
  }
  private void cancel () { complete(submitting ? "pending" : "cancelled"); }
  private ScrollView scroll (LinearLayout content) {
    ScrollView view = new ScrollView(actions.host.activity()); view.addView(content); return view;
  }

  private void orderInfo (TdApi.PaymentFormTypeRegular regular) {
    TdApi.Invoice details = regular.invoice;
    if (!details.needName && !details.needPhoneNumber && !details.needEmailAddress && !details.needShippingAddress) {
      chooseCredentials(regular); return;
    }
    LinearLayout content = actions.column();
    TdApi.OrderInfo saved = regular.savedOrderInfo;
    EditText name = details.needName ? actions.field(content, R.string.WebAppOrderName, saved == null ? "" : saved.name) : null;
    EditText phone = details.needPhoneNumber ? actions.field(content, R.string.WebAppOrderPhone, saved == null ? "" : saved.phoneNumber) : null;
    EditText email = details.needEmailAddress ? actions.field(content, R.string.WebAppOrderEmail, saved == null ? "" : saved.emailAddress) : null;
    if (phone != null) phone.setInputType(InputType.TYPE_CLASS_PHONE);
    if (email != null) email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
    EditText[] address = new EditText[6];
    if (details.needShippingAddress) {
      TdApi.Address old = saved == null ? null : saved.shippingAddress;
      int[] hints = {R.string.WebAppCountryCode, R.string.WebAppState, R.string.WebAppCity, R.string.WebAppStreet, R.string.WebAppStreet2, R.string.WebAppPostalCode};
      String[] values = old == null ? new String[6] : new String[] {old.countryCode, old.state, old.city, old.streetLine1, old.streetLine2, old.postalCode};
      for (int i = 0; i < 6; i++) address[i] = actions.field(content, hints[i], values[i]);
    }
    String disclosure = actions.host.context().getString(R.string.WebAppOrderInfoDisclosure);
    if (details.sendPhoneNumberToProvider) disclosure += "\n" + actions.host.context().getString(R.string.WebAppPhoneToProvider);
    if (details.sendEmailAddressToProvider) disclosure += "\n" + actions.host.context().getString(R.string.WebAppEmailToProvider);
    AlertDialog dialog = actions.show(new AlertDialog.Builder(actions.host.activity()).setTitle(R.string.WebAppOrderInfoTitle)
      .setMessage(disclosure).setView(scroll(content)).setPositiveButton(R.string.WebAppContinue, null)
      .setNegativeButton(android.R.string.cancel, (d, w) -> cancel()), this::cancel);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
      if (!active()) return;
      TdApi.Address shipping = details.needShippingAddress ? new TdApi.Address(text(address[0]).toUpperCase(Locale.US),
        text(address[1]), text(address[2]), text(address[3]), text(address[4]), text(address[5])) : null;
      TdApi.OrderInfo info = new TdApi.OrderInfo(text(name), text(phone), text(email), shipping);
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      send(new TdApi.ValidateOrderInfo(invoice, info, false), result -> {
        if (!active()) return;
        if (!(result instanceof TdApi.ValidatedOrderInfo)) {
          actions.error(result); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); return;
        }
        TdApi.ValidatedOrderInfo validated = (TdApi.ValidatedOrderInfo) result;
        orderInfoId = validated.orderInfoId; dialog.dismiss();
        if (validated.shippingOptions != null && validated.shippingOptions.length > 0) {
          String[] names = new String[validated.shippingOptions.length];
          for (int i = 0; i < names.length; i++) names[i] = validated.shippingOptions[i].title + " — " + amount(sum(validated.shippingOptions[i].priceParts), details.currency);
          actions.show(new AlertDialog.Builder(actions.host.activity()).setTitle(R.string.WebAppShippingTitle)
            .setItems(names, (d, which) -> {
              if (!active()) return;
              shippingId = validated.shippingOptions[which].id;
              shippingAmount = sum(validated.shippingOptions[which].priceParts); chooseCredentials(regular);
            }).setNegativeButton(android.R.string.cancel, (d, w) -> cancel()), this::cancel);
        } else chooseCredentials(regular);
      });
    });
  }
  private static String text (EditText field) { return field == null ? "" : field.getText().toString().trim(); }
  private void chooseCredentials (TdApi.PaymentFormTypeRegular regular) {
    ArrayList<String> choices = new ArrayList<>();
    for (TdApi.SavedCredentials saved : regular.savedCredentials) choices.add(saved.title);
    choices.add(actions.host.context().getString(R.string.WebAppNewCard));
    for (TdApi.PaymentOption option : regular.additionalPaymentOptions) choices.add(option.title);
    if (choices.size() == 1) { newCredentials(regular); return; }
    actions.show(new AlertDialog.Builder(actions.host.activity()).setTitle(R.string.WebAppPaymentMethodTitle)
      .setItems(choices.toArray(new String[0]), (d, which) -> {
        if (!active()) return;
        if (which < regular.savedCredentials.length) savedCredentials(regular.savedCredentials[which].id);
        else if (which == regular.savedCredentials.length) newCredentials(regular);
        else provider(regular.additionalPaymentOptions[which - regular.savedCredentials.length - 1].url);
      }).setNegativeButton(android.R.string.cancel, (d, w) -> cancel()), this::cancel);
  }
  private void savedCredentials (String id) {
    send(new TdApi.GetTemporaryPasswordState(), result -> {
      if (!active()) return;
      if (result instanceof TdApi.TemporaryPasswordState && ((TdApi.TemporaryPasswordState) result).hasPassword && ((TdApi.TemporaryPasswordState) result).validFor > 60) {
        credentials = new TdApi.InputCredentialsSaved(id); checkout(); return;
      }
      LinearLayout fields = actions.column();
      EditText password = actions.field(fields, R.string.WebAppPassword, "");
      password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
      AlertDialog dialog = actions.show(new AlertDialog.Builder(actions.host.activity()).setTitle(R.string.WebAppPaymentPasswordTitle)
        .setView(fields).setPositiveButton(R.string.WebAppContinue, null)
        .setNegativeButton(android.R.string.cancel, (d, w) -> cancel()), this::cancel);
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
        if (!active()) return;
        String entered = password.getText().toString(); password.setText("");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        send(new TdApi.CreateTemporaryPassword(entered, 300), state -> {
          if (!active()) return;
          if (state instanceof TdApi.TemporaryPasswordState && ((TdApi.TemporaryPasswordState) state).hasPassword) {
            dialog.dismiss(); credentials = new TdApi.InputCredentialsSaved(id); checkout();
          } else { actions.error(state); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); }
        });
      });
    });
  }
  private void newCredentials (TdApi.PaymentFormTypeRegular regular) {
    if (regular.paymentProvider instanceof TdApi.PaymentProviderOther) {
      provider(((TdApi.PaymentProviderOther) regular.paymentProvider).url); return;
    }
    LinearLayout fields = actions.column();
    EditText number = actions.field(fields, R.string.WebAppCardNumber, "");
    number.setInputType(InputType.TYPE_CLASS_NUMBER);
    if (android.os.Build.VERSION.SDK_INT >= 26) number.setAutofillHints("creditCardNumber");
    EditText month = actions.field(fields, R.string.WebAppCardMonth, ""); month.setInputType(InputType.TYPE_CLASS_NUMBER);
    EditText year = actions.field(fields, R.string.WebAppCardYear, ""); year.setInputType(InputType.TYPE_CLASS_NUMBER);
    EditText cvc = actions.field(fields, R.string.WebAppCardCvc, ""); cvc.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    TdApi.PaymentProviderStripe stripe = regular.paymentProvider instanceof TdApi.PaymentProviderStripe ? (TdApi.PaymentProviderStripe) regular.paymentProvider : null;
    EditText name = stripe != null && stripe.needCardholderName ? actions.field(fields, R.string.WebAppOrderName, "") : null;
    EditText country = stripe != null && stripe.needCountry ? actions.field(fields, R.string.WebAppCountryCode, "") : null;
    EditText postal = stripe != null && stripe.needPostalCode ? actions.field(fields, R.string.WebAppPostalCode, "") : null;
    AlertDialog dialog = actions.show(new AlertDialog.Builder(actions.host.activity()).setTitle(R.string.WebAppCardTitle)
      .setMessage(R.string.WebAppCardDisclosure).setView(scroll(fields)).setPositiveButton(R.string.WebAppContinue, null)
      .setNegativeButton(android.R.string.cancel, (d, w) -> cancel()), this::cancel);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
      if (!active()) return;
      String card = text(number).replace(" ", "");
      int mm, yyyy;
      try { mm = Integer.parseInt(text(month)); yyyy = Integer.parseInt(text(year)); } catch (NumberFormatException ignored) { mm = 0; yyyy = 0; }
      if (yyyy < 100) yyyy += 2000;
      if (!card.matches("[0-9]{12,19}") || mm < 1 || mm > 12 || yyyy < 2020 || !text(cvc).matches("[0-9]{3,4}")) {
        number.setError(actions.host.context().getString(R.string.WebAppInvalidCard)); return;
      }
      String cv = text(cvc), owner = text(name), countryCode = text(country), zip = text(postal);
      final int expirationMonth = mm, expirationYear = yyyy;
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      number.setText(""); cvc.setText("");
      worker.execute(() -> {
        String token = tokenize(regular.paymentProvider, regular.invoice.isTest, card, expirationMonth, expirationYear, cv, owner, countryCode, zip);
        actions.host.runOnUiThread(() -> {
          if (!active() || !dialog.isShowing()) return;
          if (token == null) { number.setError(actions.host.context().getString(R.string.WebAppPaymentTokenFailed)); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); return; }
          credentials = new TdApi.InputCredentialsNew(token, false); dialog.dismiss(); checkout();
        });
      });
    });
  }

  private String tokenize (TdApi.PaymentProvider provider, boolean test, String number, int month, int year, String cvc, String name, String country, String postal) {
    HttpURLConnection current = null;
    try {
      String endpoint, payload, contentType;
      if (provider instanceof TdApi.PaymentProviderStripe) {
        endpoint = "https://api.stripe.com/v1/tokens";
        payload = "card[number]=" + encode(number) + "&card[exp_month]=" + month + "&card[exp_year]=" + year + "&card[cvc]=" + encode(cvc) +
          "&card[name]=" + encode(name) + "&card[address_country]=" + encode(country) + "&card[address_zip]=" + encode(postal);
        contentType = "application/x-www-form-urlencoded";
      } else if (provider instanceof TdApi.PaymentProviderSmartGlocal) {
        endpoint = ((TdApi.PaymentProviderSmartGlocal) provider).tokenizeUrl;
        if (endpoint == null || endpoint.isEmpty()) endpoint = test ? "https://tgb-playground.smart-glocal.com/cds/v1/tokenize/card" : "https://tgb.smart-glocal.com/cds/v1/tokenize/card";
        Uri parsed = Uri.parse(endpoint);
        if (!"https".equals(parsed.getScheme()) || parsed.getHost() == null || !parsed.getHost().endsWith(".smart-glocal.com") ||
            !"/cds/v1/tokenize/card".equals(parsed.getPath()) || parsed.getUserInfo() != null) return null;
        payload = WebAppActions.object("card", WebAppActions.object("number", number, "expiration_month", String.format(Locale.US, "%02d", month),
          "expiration_year", Integer.toString(year), "security_code", cvc)).toString();
        contentType = "application/json";
      } else return null;
      current = (HttpURLConnection) new URL(endpoint).openConnection(); connection = current;
      current.setInstanceFollowRedirects(false); current.setConnectTimeout(20000); current.setReadTimeout(30000);
      current.setUseCaches(false); current.setRequestMethod("POST"); current.setDoOutput(true);
      current.setRequestProperty("Content-Type", contentType);
      if (provider instanceof TdApi.PaymentProviderStripe) current.setRequestProperty("Authorization", "Bearer " + ((TdApi.PaymentProviderStripe) provider).publishableKey);
      else current.setRequestProperty("X-PUBLIC-TOKEN", ((TdApi.PaymentProviderSmartGlocal) provider).publicToken);
      try (OutputStream output = current.getOutputStream()) { output.write(payload.getBytes(StandardCharsets.UTF_8)); }
      if (current.getResponseCode() < 200 || current.getResponseCode() >= 300) return null;
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (InputStream input = current.getInputStream()) {
        byte[] buffer = new byte[4096]; int read;
        while ((read = input.read(buffer)) != -1) { if (bytes.size() + read > 262144) return null; bytes.write(buffer, 0, read); }
      }
      JSONObject response = new JSONObject(bytes.toString("UTF-8"));
      return provider instanceof TdApi.PaymentProviderStripe ? WebAppActions.object("type", "card", "id", response.getString("id")).toString() :
        WebAppActions.object("type", "card", "token", response.getJSONObject("data").getString("token")).toString();
    } catch (Exception ignored) { return null; }
    finally { if (current != null) current.disconnect(); connection = null; }
  }
  private static String encode (String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }

  private void provider (String url) {
    Uri parsed = Uri.parse(url);
    if (!"https".equals(parsed.getScheme()) || parsed.getHost() == null || parsed.getUserInfo() != null) { complete("failed"); return; }
    cleanupProvider();
    if (!createProviderView()) return;
    WebSettings settings = providerView.getSettings();
    settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
    if (android.os.Build.VERSION.SDK_INT >= 21) settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    providerBridge = new WebAppBridge(providerView, url, (event, data) -> {
      if (!active() || !"payment_form_submit".equals(event)) return;
      JSONObject value = data.optJSONObject("credentials");
      if (value == null) {
        try { value = new JSONObject(data.optString("credentials")); } catch (org.json.JSONException ignored) { return; }
      }
      if (value.toString().length() > 65536) return;
      credentials = new TdApi.InputCredentialsNew(value.toString(), false);
      cleanupProvider(); checkout();
    }, true);
    if (!providerBridge.install()) { complete("failed"); return; }
    providerView.setWebViewClient(new WebViewClient() {
      @Override public void onPageStarted (WebView view, String url, android.graphics.Bitmap icon) {
        if (providerBridge != null) providerBridge.newDocument();
      }
      @Override public boolean shouldOverrideUrlLoading (WebView view, String url) { return blocked(url); }
      @Override public boolean shouldOverrideUrlLoading (WebView view, WebResourceRequest request) { return blocked(request.getUrl().toString()); }
      private boolean blocked (String url) {
        return !"https".equals(Uri.parse(url).getScheme());
      }
    });
    providerDialog = actions.show(new AlertDialog.Builder(actions.host.activity()).setTitle(R.string.WebAppPaymentMethodTitle)
      .setView(providerView).setNegativeButton(android.R.string.cancel, (d, w) -> cancel()), this::cancel);
    providerView.loadUrl(url);
  }
  private void checkout () {
    if (!active()) return;
    String title = form.productInfo.title;
    String description = form.productInfo.description == null ? "" : form.productInfo.description.text;
    StringBuilder summary = new StringBuilder(title).append("\n").append(description).append("\n\n");
    LinearLayout fields = actions.column();
    EditText tip = null;
    CheckBox terms = null;
    if (form.type instanceof TdApi.PaymentFormTypeRegular) {
      TdApi.Invoice info = ((TdApi.PaymentFormTypeRegular) form.type).invoice;
      for (TdApi.LabeledPricePart part : info.priceParts) summary.append(part.label).append(": ").append(amount(part.amount, info.currency)).append('\n');
      if (shippingAmount != 0) summary.append(actions.host.context().getString(R.string.WebAppShippingTitle)).append(": ").append(amount(shippingAmount, info.currency)).append('\n');
      summary.append(actions.host.context().getString(R.string.WebAppTotal, amount(sum(info.priceParts) + shippingAmount, info.currency)));
      if (info.isTest) summary.append("\n\n").append(actions.host.context().getString(R.string.WebAppTestPayment));
      if (info.maxTipAmount > 0) {
        tip = actions.field(fields, R.string.WebAppTip, "0"); tip.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
      }
      String termsUrl = info.recurringPaymentTermsOfServiceUrl;
      if (termsUrl == null || termsUrl.isEmpty()) termsUrl = info.termsOfServiceUrl;
      if (termsUrl != null && !termsUrl.isEmpty()) {
        android.widget.TextView link = new android.widget.TextView(actions.host.activity());
        final String target = termsUrl;
        link.setText(R.string.WebAppPaymentTerms); link.setPadding(0, 16, 0, 16);
        link.setOnClickListener(view -> { if (active()) actions.host.openExternalUrl(target); }); fields.addView(link);
        terms = new CheckBox(actions.host.activity()); terms.setText(R.string.WebAppAcceptPaymentTerms); fields.addView(terms);
      }
      if (info.subscriptionPeriod > 0) summary.append("\n\n").append(actions.host.context().getString(R.string.WebAppSubscriptionPeriod, info.subscriptionPeriod));
    } else {
      long count = form.type instanceof TdApi.PaymentFormTypeStars ? ((TdApi.PaymentFormTypeStars) form.type).starCount :
        ((TdApi.PaymentFormTypeStarSubscription) form.type).pricing.starCount;
      summary.append(actions.host.context().getString(R.string.WebAppStarsTotal, count));
      if (form.type instanceof TdApi.PaymentFormTypeStarSubscription) {
        summary.append("\n\n").append(actions.host.context().getString(R.string.WebAppSubscriptionPeriod, ((TdApi.PaymentFormTypeStarSubscription) form.type).pricing.period));
        terms = new CheckBox(actions.host.activity()); terms.setText(R.string.WebAppAcceptSubscription); fields.addView(terms);
      }
    }
    final EditText tipField = tip; final CheckBox termsField = terms;
    AlertDialog dialog = actions.show(new AlertDialog.Builder(actions.host.activity()).setTitle(R.string.WebAppCheckoutTitle)
      .setMessage(summary).setView(fields).setPositiveButton(R.string.WebAppPay, null)
      .setNegativeButton(android.R.string.cancel, (d, w) -> cancel()), this::cancel);
    if (tipField != null) {
      tipField.addTextChangedListener(new android.text.TextWatcher() {
        @Override public void beforeTextChanged (CharSequence s, int start, int count, int after) { }
        @Override public void afterTextChanged (android.text.Editable s) { }
        @Override public void onTextChanged (CharSequence s, int start, int before, int count) {
          if (!active()) return;
          TdApi.Invoice info = ((TdApi.PaymentFormTypeRegular) form.type).invoice;
          try {
            long tip = new BigDecimal(s.toString()).movePointRight(fractionDigits(info.currency)).longValueExact();
            if (tip >= 0 && tip <= info.maxTipAmount) dialog.setMessage(summary.toString() + "\n" +
              actions.host.context().getString(R.string.WebAppTotal, amount(sum(info.priceParts) + shippingAmount + tip, info.currency)));
          } catch (Exception ignored) { }
        }
      });
    }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
      if (!active() || submitting || termsField != null && !termsField.isChecked()) return;
      long tipAmount = 0;
      if (tipField != null) {
        TdApi.Invoice info = ((TdApi.PaymentFormTypeRegular) form.type).invoice;
        try { tipAmount = new BigDecimal(text(tipField)).movePointRight(fractionDigits(info.currency)).longValueExact(); }
        catch (Exception ignored) { tipAmount = -1; }
        if (tipAmount < 0 || tipAmount > info.maxTipAmount) { tipField.setError(actions.host.context().getString(R.string.WebAppInvalidTip)); return; }
      }
      dialog.dismiss(); submitting = true;
      send(new TdApi.SendPaymentForm(invoice, form.id, orderInfoId, shippingId, credentials, tipAmount), result -> {
        if (!active()) return;
        credentials = null;
        if (result instanceof TdApi.PaymentResult) {
          TdApi.PaymentResult payment = (TdApi.PaymentResult) result;
          if (payment.success) complete("paid");
          else if (payment.verificationUrl != null && !payment.verificationUrl.isEmpty()) verification(payment.verificationUrl);
          else complete("pending");
        } else fail(result);
      });
    });
  }
  private void verification (String url) {
    Uri parsed = Uri.parse(url);
    if (!"https".equals(parsed.getScheme()) || parsed.getHost() == null) { complete("pending"); return; }
    cleanupProvider();
    if (!createProviderView()) return;
    providerView.getSettings().setJavaScriptEnabled(true);
    providerView.getSettings().setDomStorageEnabled(true);
    providerView.getSettings().setAllowFileAccess(false);
    providerView.getSettings().setAllowContentAccess(false);
    if (android.os.Build.VERSION.SDK_INT >= 21) providerView.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    providerView.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading (WebView view, String target) { return !"https".equals(Uri.parse(target).getScheme()); }
      @Override public boolean shouldOverrideUrlLoading (WebView view, WebResourceRequest request) { return !"https".equals(request.getUrl().getScheme()); }
    });
    providerDialog = actions.show(new AlertDialog.Builder(actions.host.activity()).setTitle(R.string.WebAppPaymentVerification)
      .setView(providerView).setNegativeButton(R.string.WebAppClose, (d, w) -> complete("pending")), () -> complete("pending"));
    providerView.loadUrl(url);
  }
  private static long sum (TdApi.LabeledPricePart[] parts) { long sum = 0; for (TdApi.LabeledPricePart part : parts) sum += part.amount; return sum; }
  private static int fractionDigits (String currency) { try { return Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits()); } catch (Exception ignored) { return 2; } }
  static String amount (long amount, String currency) { return BigDecimal.valueOf(amount, fractionDigits(currency)).toPlainString() + " " + currency; }
  private boolean createProviderView () {
    providerView = new WebView(actions.host.activity());
    long profileBotId = actions.botId() != 0 ? actions.botId() : form.sellerBotUserId;
    try {
      if (paymentProfile == null) paymentProfile = WebAppDevice.namespace(actions.host.tdlib(), profileBotId) + "_payment_" + java.util.UUID.randomUUID();
      WebAppBridge.configureProfile(providerView, paymentProfile);
      return true;
    } catch (RuntimeException ignored) {
      android.widget.Toast.makeText(actions.host.context(), R.string.WebAppRuntimeWebViewUpdate, android.widget.Toast.LENGTH_LONG).show();
      complete("failed"); return false;
    }
  }
  private void forgetProfile () {
    if (paymentProfile != null) { WebAppBridge.forgetProfile(paymentProfile); paymentProfile = null; }
  }
  private void cleanupProvider () {
    if (providerBridge != null) { providerBridge.destroy(); providerBridge = null; }
    if (providerDialog != null) { providerDialog.dismiss(); providerDialog = null; }
    if (providerView != null) { providerView.stopLoading(); providerView.destroy(); providerView = null; }
  }
  void destroy () {
    slug = null; generation++; credentials = null; cleanupProvider(); forgetProfile();
    if (topUp != null) { topUp.destroy(); topUp = null; }
    if (receiptChatId != 0) actions.host.tdlib().listeners().unsubscribeFromMessageUpdates(receiptChatId, receiptListener);
    HttpURLConnection current = connection; if (current != null) current.disconnect(); worker.shutdownNow();
  }
}
