package org.thunderdog.challegram.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.util.TranslationCounterDrawable;

import java.util.HashMap;
import java.util.Objects;

import me.vkryl.core.StringUtils;
import tgx.td.Td;
import tgx.td.TdConstants;

public final class TranslationsManager {

  private final Tdlib tdlib;
  private final Translatable message;
  private final OnChangeTranslatedStatus statusDelegate;
  private final OnChangeTranslatedResult resultDelegate;
  private final OnNewTranslatedError errorDelegate;
  private String currentTranslatedLanguage;
  private String lastTranslatedLanguage;

  public interface OnNewTranslatedError {
    void onError (String string);
  }

  public interface OnChangeTranslatedStatus {
    void setTranslatedStatus (int status, boolean animated);
  }

  public interface OnChangeTranslatedResult {
    void setTranslationResult (@Nullable TdApi.Object translationResult);
  }

  public interface Translatable {
    @Nullable
    String getOriginalMessageLanguage ();

    TdApi.FormattedText getTextToTranslate ();

    default @Nullable TranslationAdapter getTranslationAdapter () {
      return null;
    }
  }

  public TranslationsManager (Tdlib tdlib, Translatable message, OnChangeTranslatedStatus statusDelegate, OnChangeTranslatedResult resultDelegate, OnNewTranslatedError errorDelegate) {
    this.tdlib = tdlib;
    this.message = message;
    this.statusDelegate = statusDelegate;
    this.resultDelegate = resultDelegate;
    this.errorDelegate = errorDelegate;
  }

  public void stopTranslation () {
    requestTranslation(null);
  }

  private int lastDispatchedStatus = TranslationCounterDrawable.TRANSLATE_STATUS_DEFAULT;
  private @Nullable TdApi.Object lastDispatchedResult;

  private void dispatchStatus (int status, boolean animated) {
    if (lastDispatchedStatus != status) {
      this.lastDispatchedStatus = status;
      statusDelegate.setTranslatedStatus(status, animated);
    }
  }

  private void dispatchResult (int status, @Nullable TdApi.Object result, boolean animated) {
    dispatchStatus(status, animated);
    boolean equal = lastDispatchedResult == result ||
      lastDispatchedResult instanceof TdApi.FormattedText &&
        result instanceof TdApi.FormattedText &&
        Td.equalsTo((TdApi.FormattedText) lastDispatchedResult,
          (TdApi.FormattedText) result);
    if (!equal) {
      this.lastDispatchedResult = result;
      resultDelegate.setTranslationResult(result);
    }
  }

  public void requestTranslation (String language) {
    currentTranslatedLanguage = language;
    if (language == null || StringUtils.equalsOrBothEmpty(language, message.getOriginalMessageLanguage())) {
      dispatchResult(TranslationCounterDrawable.TRANSLATE_STATUS_DEFAULT, null, true);
      currentTranslatedLanguage = null;
      return;
    }

    if (currentTranslatedLanguage != null) {
      lastTranslatedLanguage = currentTranslatedLanguage;
    }

    TranslationAdapter adapter = message.getTranslationAdapter();
    if (adapter != null) {
      requestAdaptedTranslation(adapter, language);
      return;
    }

    TdApi.FormattedText textToTranslate = prepareTextToTranslate(message.getTextToTranslate());
    if (textToTranslate == null) return;

    TdApi.FormattedText cachedText = getCachedTextTranslation(textToTranslate.text, language);
    if (cachedText != null) {
      dispatchResult(TranslationCounterDrawable.TRANSLATE_STATUS_SUCCESS, cachedText, true);
      return;
    }

    dispatchStatus(TranslationCounterDrawable.TRANSLATE_STATUS_LOADING, true);
    tdlib.ui().post(() -> requestTranslationImpl(textToTranslate, language, object -> tdlib.ui().post(() -> {
      if (object instanceof TdApi.FormattedText) {
        TdApi.FormattedText text = prepareTranslatedText((TdApi.FormattedText) object);
        saveCachedTextTranslation(textToTranslate.text, language, text);
        if (StringUtils.equalsOrBothEmpty(currentTranslatedLanguage, language)) {
          dispatchResult(TranslationCounterDrawable.TRANSLATE_STATUS_SUCCESS, text, true);
        }
      } else {
        if (StringUtils.equalsOrBothEmpty(currentTranslatedLanguage, language)) {
          dispatchStatus(TranslationCounterDrawable.TRANSLATE_STATUS_ERROR, true);
          if (object instanceof TdApi.Error) {
            errorDelegate.onError(TD.toErrorString(object));
          }
        }
      }
    })));
  }

  public String getCurrentTranslatedLanguage () {
    return currentTranslatedLanguage;
  }

  public String getLastTranslatedLanguage () {
    return lastTranslatedLanguage;
  }

  private void requestTranslationImpl (TdApi.FormattedText originalText, String toLanguage, Client.ResultHandler callback) {
    tdlib.client().send(new TdApi.TranslateText(originalText, toLanguage, TdConstants.TEXT_FORMAT_NEUTRAL), callback);
  }

  private void requestAdaptedTranslation (@NonNull TranslationAdapter adapter,
                                          @NonNull String language) {
    final String sourceIdentity = adapter.sourceIdentity();
    final String tone = adapter.tone();
    final String targetKey = language + '\u0000' + tone;
    TdApi.Object cached = getCachedAdaptedTranslation(sourceIdentity, targetKey);
    if (cached != null) {
      dispatchResult(TranslationCounterDrawable.TRANSLATE_STATUS_SUCCESS, cached, true);
      return;
    }

    dispatchStatus(TranslationCounterDrawable.TRANSLATE_STATUS_LOADING, true);
    tdlib.ui().post(() -> adapter.request(tdlib, language, tone, object ->
      tdlib.ui().post(() -> {
        TranslationAdapter currentAdapter = message.getTranslationAdapter();
        if (currentAdapter == null ||
            !Objects.equals(sourceIdentity, currentAdapter.sourceIdentity())) {
          return;
        }
        TdApi.Object translated = adapter.validate(object);
        if (translated != null) {
          saveCachedAdaptedTranslation(sourceIdentity, targetKey, translated);
          if (StringUtils.equalsOrBothEmpty(currentTranslatedLanguage, language)) {
            dispatchResult(TranslationCounterDrawable.TRANSLATE_STATUS_SUCCESS,
              translated, true);
          }
        } else if (StringUtils.equalsOrBothEmpty(currentTranslatedLanguage, language)) {
          dispatchStatus(TranslationCounterDrawable.TRANSLATE_STATUS_ERROR, true);
          if (object instanceof TdApi.Error) {
            errorDelegate.onError(TD.toErrorString(object));
          }
        }
      })));
  }

  private final HashMap<String, HashMap<String, TdApi.Object>> adaptedTranslations =
    new HashMap<>();

  private @Nullable TdApi.Object getCachedAdaptedTranslation (
    @NonNull String sourceIdentity, @NonNull String targetKey) {
    HashMap<String, TdApi.Object> values = adaptedTranslations.get(sourceIdentity);
    return values != null ? values.get(targetKey) : null;
  }

  private void saveCachedAdaptedTranslation (@NonNull String sourceIdentity,
                                              @NonNull String targetKey,
                                              @NonNull TdApi.Object value) {
    HashMap<String, TdApi.Object> values = adaptedTranslations.get(sourceIdentity);
    if (values == null) {
      values = new HashMap<>();
      adaptedTranslations.put(sourceIdentity, values);
    }
    values.put(targetKey, value);
  }




  private final HashMap<String, TranslatedCachedValue> mTranslationsCache2 = new HashMap<>();

  private static class TranslatedCachedValue {
    public final String originalLanguage;
    public final HashMap<String, TdApi.FormattedText> translationsCache;

    public TranslatedCachedValue (String originalLanguage) {
      this.originalLanguage = originalLanguage;
      this.translationsCache = new HashMap<>();
    }
  }

  public @Nullable String getCachedTextLanguage (String text) {
    TranslatedCachedValue cachedValue = mTranslationsCache2.get(text);
    return (cachedValue != null ? cachedValue.originalLanguage : null);
  }

  public void saveCachedTextLanguage (String text, String language) {
    if (!mTranslationsCache2.containsKey(text)) {
      mTranslationsCache2.put(text, new TranslatedCachedValue(language));
    }
  }

  public @Nullable TdApi.FormattedText getCachedTextTranslation (String text, String language) {
    TranslatedCachedValue cachedValue = mTranslationsCache2.get(text);
    return (cachedValue != null ? cachedValue.translationsCache.get(language) : null);
  }

  public void saveCachedTextTranslation (String text, String language, TdApi.FormattedText translated) {
    TranslatedCachedValue cachedValue = mTranslationsCache2.get(text);
    if (cachedValue != null) {
      cachedValue.translationsCache.put(language, translated);
    }
  }



  public static TdApi.FormattedText prepareTextToTranslate (TdApi.FormattedText text) {
    if (text == null || text.entities == null || text.entities.length == 0) {
      return text;
    }
    try {
      TdApi.TextEntity[] entities = new TdApi.TextEntity[text.entities.length];
      for (int a = 0; a < entities.length; a++) {
        TdApi.TextEntity entity = text.entities[a];
        if (entity.type instanceof TdApi.TextEntityTypeUrl) {
          String url = text.text.substring(entity.offset, entity.offset + entity.length);
          TdApi.TextEntityType newEntityTypeUrl = new TdApi.TextEntityTypeTextUrl(url);
          entities[a] = new TdApi.TextEntity(entity.offset, entity.length, newEntityTypeUrl);
        } else if (entity.type instanceof TdApi.TextEntityTypeMention) {
          String username = text.text.substring(entity.offset + 1, entity.offset + entity.length);
          TdApi.TextEntityType newEntityTypeUrl = new TdApi.TextEntityTypeTextUrl("https://t.me/" + username);
          entities[a] = new TdApi.TextEntity(entity.offset, entity.length, newEntityTypeUrl);
        }  else if (entity.type instanceof TdApi.TextEntityTypeHashtag) {
          TdApi.TextEntityType newEntityTypeUrl = new TdApi.TextEntityTypeCode();
          entities[a] = new TdApi.TextEntity(entity.offset, entity.length, newEntityTypeUrl);
        } else {
          entities[a] = entity;
        }
      }

      return new TdApi.FormattedText(text.text, entities);
    } catch (Exception e) {
      return text;
    }
  }

  public static TdApi.FormattedText prepareTranslatedText (TdApi.FormattedText text) {
    if (text == null || text.entities == null || text.entities.length == 0) {
      return text;
    }
    try {
      TdApi.TextEntity[] entities = new TdApi.TextEntity[text.entities.length];
      for (int a = 0; a < entities.length; a++) {
        TdApi.TextEntity entity = entities[a] = text.entities[a];
        if (entity.type instanceof TdApi.TextEntityTypeCode) {
          String code = text.text.substring(entity.offset, entity.offset + entity.length);
          if (code.startsWith("#")) {
            TdApi.TextEntityType newEntityType = new TdApi.TextEntityTypeHashtag();
            entities[a] = new TdApi.TextEntity(entity.offset, entity.length, newEntityType);
          }
        }
      }

      return new TdApi.FormattedText(text.text, entities);
    } catch (Exception e) {
      return text;
    }
  }
}
