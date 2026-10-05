/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted for Pulsargram X from AOSP DefaultCrossActivityBackAnimation,
 * CrossActivityBackAnimation and Interpolators at commit
 * 99b01a65cc4c104933788b3143285ab6bae65827.
 */
package org.thunderdog.challegram.navigation;

import android.graphics.Path;
import android.graphics.RectF;
import android.view.animation.Interpolator;

import androidx.core.view.animation.PathInterpolatorCompat;

final class PredictiveBackAnimation {
  static final long COMMIT_DURATION = 450L;
  static final long CANCEL_DURATION = 320L;

  private static final float SCALE = .85f;
  private static final Interpolator GESTURE_INTERPOLATOR =
    PathInterpolatorCompat.create(.1f, .1f, 0f, 1f);
  private static final Interpolator COMMIT_INTERPOLATOR = createCommitInterpolator();

  private final RectF closingRect = new RectF();
  private final RectF enteringRect = new RectF();
  private final RectF startClosingRect = new RectF();
  private final RectF targetClosingRect = new RectF();
  private final RectF startEnteringRect = new RectF();
  private final RectF targetEnteringRect = new RectF();
  private float width, height, displayMargin, enteringOffset, flingVelocity;

  void start (float width, float height, float displayMargin, float enteringOffset,
              boolean fromRight) {
    this.width = width;
    this.height = height;
    this.displayMargin = displayMargin;
    this.enteringOffset = enteringOffset;
    flingVelocity = 0f;

    startClosingRect.set(0f, 0f, width, height);
    targetClosingRect.set(startClosingRect);
    scaleCentered(targetClosingRect, SCALE);
    if (!fromRight) {
      // Android's left edge aligns to the right margin; its right edge stays centered.
      targetClosingRect.offset(width - targetClosingRect.right - displayMargin, 0f);
    }
    startEnteringRect.set(startClosingRect);
    startEnteringRect.offset(-enteringOffset, 0f);
    targetEnteringRect.set(startEnteringRect);
    scaleCentered(targetEnteringRect, SCALE);
    updateGesture(0f, 0f);
  }

  static float interpolateProgress (float progress) {
    return GESTURE_INTERPOLATOR.getInterpolation(progress);
  }

  void updateGesture (float progress, float touchDeltaY) {
    interpolate(closingRect, startClosingRect, targetClosingRect, progress);
    interpolate(enteringRect, startEnteringRect, targetEnteringRect, progress);
    float yRatio = height > 0f ? Math.min(1f, Math.abs(touchDeltaY) / (height * .5f)) : 0f;
    float yOffset = Math.max(0f, (height - closingRect.height()) * .5f - displayMargin) *
      (1f - (1f - yRatio) * (1f - yRatio));
    if (touchDeltaY < 0f) {
      yOffset = -yOffset;
    }
    closingRect.offset(0f, yOffset);
    enteringRect.offset(0f, yOffset);
  }

  void startCommit (float progress, float progressVelocity) {
    startClosingRect.set(closingRect);
    startEnteringRect.set(enteringRect);
    targetClosingRect.set(0f, 0f, width, height);
    targetClosingRect.offset(closingRect.left + enteringOffset, 0f);
    targetEnteringRect.set(0f, 0f, width, height);

    // Scale velocity follows AOSP's gesture velocity factor and 100-unit spring normalization.
    flingVelocity = Math.max(0f, Math.min(10f, progressVelocity * (1f - SCALE) * 2f));
    if (progress < .1f) {
      flingVelocity = Math.max(1.2f, flingVelocity);
    }
  }

  float updateCommit (float fraction, float elapsedSeconds) {
    float progress = COMMIT_INTERPOLATOR.getInterpolation(fraction);
    interpolate(closingRect, startClosingRect, targetClosingRect, progress);
    interpolate(enteringRect, startEnteringRect, targetEnteringRect, progress);

    // The closed-form spring avoids another animator/dependency. Match AOSP stiffness 200,
    // damping ratio .75 and FLING_BOUNCE's clamp at 1: the cards never grow past their bounds.
    double decay = Math.sqrt(200.0) * .75;
    double frequency = Math.sqrt(200.0 - decay * decay);
    float springOffset = (float) (-flingVelocity / frequency *
      Math.exp(-decay * elapsedSeconds) * Math.sin(frequency * elapsedSeconds));
    float scale = fraction >= 1f ? 1f : Math.max(.5f, Math.min(1f, 1f + springOffset));
    scaleCentered(closingRect, scale);
    scaleCentered(enteringRect, scale);
    return progress;
  }

  static float cancelProgress (float fraction) {
    // Native back callbacks normally finish their cancellation spring before onBackCancelled.
    // A critically damped fallback also handles cancellation by a controller or older callback.
    double decay = Math.sqrt(1500.0) * CANCEL_DURATION / 1000.0;
    double end = (1.0 + decay) * Math.exp(-decay);
    double t = decay * fraction;
    return (float) (((1.0 + t) * Math.exp(-t) - end) / (1.0 - end));
  }

  RectF getClosingRect () {
    return closingRect;
  }

  RectF getEnteringRect () {
    return enteringRect;
  }

  private static void interpolate (RectF rect, RectF start, RectF end, float progress) {
    rect.set(start.left + (end.left - start.left) * progress,
      start.top + (end.top - start.top) * progress,
      start.right + (end.right - start.right) * progress,
      start.bottom + (end.bottom - start.bottom) * progress);
  }

  private static void scaleCentered (RectF rect, float scale) {
    rect.inset(rect.width() * (1f - scale) * .5f, rect.height() * (1f - scale) * .5f);
  }

  private static Interpolator createCommitInterpolator () {
    Path path = new Path();
    path.moveTo(0f, 0f);
    path.cubicTo(.05f, 0f, .133333f, .06f, .166666f, .4f);
    path.cubicTo(.208333f, .82f, .25f, 1f, 1f, 1f);
    return PathInterpolatorCompat.create(path);
  }
}
