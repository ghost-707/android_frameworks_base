/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar.phone;

import static com.android.systemui.statusbar.phone.HeadsUpAppearanceController.CONTENT_FADE_DELAY;
import static com.android.systemui.statusbar.phone.HeadsUpAppearanceController.CONTENT_FADE_DURATION;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.util.Property;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;

import androidx.annotation.VisibleForTesting;
import androidx.collection.ArrayMap;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.stack.AnimationFilter;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.ViewState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;

/**
 * A container for notification icons. It handles overflowing icons properly and positions them
 * correctly on the screen.
 */
public class NotificationIconContainer extends ViewGroup {
    /**
     * A float value indicating how much before the overflow start the icons should transform into
     * a dot. A value of 0 means that they are exactly at the end and a value of 1 means it starts
     * 1 icon width early.
     */
    public static final float OVERFLOW_EARLY_AMOUNT = 0.2f;
    private static final int NO_VALUE = Integer.MIN_VALUE;
    private static final String TAG = "NotificationIconContainer";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_OVERFLOW = false;
    private static final int CANNED_ANIMATION_DURATION = 100;
    private static final AnimationProperties DOT_ANIMATION_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateX();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200);

    private static final AnimationProperties ICON_ANIMATION_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter()
                .animateX()
                .animateY()
                .animateAlpha()
                .animateScale();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }

    }.setDuration(CANNED_ANIMATION_DURATION);

    /**
     * Temporary AnimationProperties to avoid unnecessary allocations.
     */
    private static final AnimationProperties sTempProperties = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    };

    private static final AnimationProperties ADD_ICON_PROPERTIES = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateAlpha();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(200).setDelay(50);

    /**
     * The animation property used for all icons that were not isolated, when the isolation ends.
     * This just fades the alpha and doesn't affect the movement and has a delay.
     */
    private static final AnimationProperties UNISOLATION_PROPERTY_OTHERS
            = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateAlpha();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(CONTENT_FADE_DURATION);

    /**
     * The animation property used for the icon when its isolation ends.
     * This animates the translation back to the right position.
     */
    private static final AnimationProperties UNISOLATION_PROPERTY = new AnimationProperties() {
        private AnimationFilter mAnimationFilter = new AnimationFilter().animateX();

        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }
    }.setDuration(CONTENT_FADE_DURATION);

    private final int MAX_ICONS_ON_AOD =
            getResources().getInteger(R.integer.config_maxVisibleNotificationIconsOnLock);

    /* Maximum number of icons in short shelf on lockscreen when also showing overflow dot. */
    public final int MAX_ICONS_ON_LOCKSCREEN =
            getResources().getInteger(R.integer.config_maxVisibleNotificationIconsOnLock);
    public final int MAX_STATIC_ICONS =
            getResources().getInteger(R.integer.config_maxVisibleNotificationIcons);

    private boolean mIsStaticLayout = true;
    private final HashMap<View, IconState> mIconStates = new HashMap<>();
    private int mDotPadding;
    private int mStaticDotRadius;
    private int mStaticDotDiameter;
    private int mActualLayoutWidth = NO_VALUE;
    private float mActualPaddingEnd = NO_VALUE;
    private float mActualPaddingStart = NO_VALUE;
    private boolean mDozing;
    private boolean mOnLockScreen;
    private boolean mInNotificationIconShelf;
    private boolean mChangingViewPositions;
    private int mAddAnimationStartIndex = -1;
    private int mCannedAnimationStartIndex = -1;
    private int mSpeedBumpIndex = -1;
    private int mIconSize;
    private boolean mDisallowNextAnimation;
    private boolean mAnimationsEnabled = true;
    private ArrayMap<String, ArrayList<StatusBarIcon>> mReplacingIcons;
    // Keep track of the last visible icon so collapsed container can report on its location
    private IconState mLastVisibleIconState;
    private IconState mFirstVisibleIconState;
    private float mVisualOverflowStart;
    private boolean mIsShowingOverflowDot;
    private StatusBarIconView mIsolatedIcon;
    private Rect mIsolatedIconLocation;
    private int[] mAbsolutePosition = new int[2];
    private View mIsolatedIconForAnimation;
    private int mThemedTextColorPrimary;

    private SettingsObserver mSettingsObserver;

    public NotificationIconContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        initDimens();
        setWillNotDraw(!(DEBUG || DEBUG_OVERFLOW));

        mSettingsObserver = new SettingsObserver(new Handler());
        mSettingsObserver.observe();
    }

    private void initDimens() {
        mDotPadding = getResources().getDimensionPixelSize(R.dimen.overflow_icon_dot_padding);
        mStaticDotRadius = getResources().getDimensionPixelSize(R.dimen.overflow_dot_radius);
        mStaticDotDiameter = 2 * mStaticDotRadius;
        final Context themedContext = new ContextThemeWrapper(getContext(),
                com.android.internal.R.style.Theme_DeviceDefault_DayNight);
        mThemedTextColorPrimary = Utils.getColorAttr(themedContext,
                com.android.internal.R.attr.textColorPrimary).getDefaultColor();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(getActualPaddingStart(), 0, getLayoutEnd(), getHeight(), paint);

        if (DEBUG_OVERFLOW) {
            if (mLastVisibleIconState == null) {
                return;
            }

            int height = getHeight();
            int end = getFinalTranslationX();

            // Visualize the "end" of the layout
            paint.setColor(Color.BLUE);
            canvas.drawLine(end, 0, end, height, paint);

            paint.setColor(Color.GREEN);
            int lastIcon = (int) mLastVisibleIconState.getXTranslation();
            canvas.drawLine(lastIcon, 0, lastIcon, height, paint);

            if (mFirstVisibleIconState != null) {
                int firstIcon = (int) mFirstVisibleIconState.getXTranslation();
                canvas.drawLine(firstIcon, 0, firstIcon, height, paint);
            }

            paint.setColor(Color.RED);
            canvas.drawLine(mVisualOverflowStart, 0, mVisualOverflowStart, height, paint);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        initDimens();
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            final ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.MAX_VISIBLE_NOTIFICATION_ICONS), false, this,
                    UserHandle.USER_ALL);
            //updateState();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateState();
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        // Does the same as "AlphaOptimizedFrameLayout".
        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int childCount = getChildCount();
        final int maxVisibleIcons = getMaxVisibleIcons(childCount);
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int childWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.UNSPECIFIED);
        int totalWidth = (int) (getActualPaddingStart() + getActualPaddingEnd());
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            measureChild(child, childWidthSpec, heightMeasureSpec);
            if (i <= maxVisibleIcons) {
                totalWidth += child.getMeasuredWidth();
            }
        }
        final int measuredWidth = resolveSize(totalWidth, widthMeasureSpec);
        final int measuredHeight = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float centerY = getHeight() / 2.0f;
        // we layout all our children on the left at the top
        mIconSize = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            // We need to layout all children even the GONE ones, such that the heights are
            // calculated correctly as they are used to calculate how many we can fit on the screen
            int width = child.getMeasuredWidth();
            int height = child.getMeasuredHeight();
            int top = (int) (centerY - height / 2.0f);
            child.layout(0, top, width, top + height);
            if (i == 0) {
                setIconSize(child.getWidth());
            }
        }
        getLocationOnScreen(mAbsolutePosition);
        if (mIsStaticLayout) {
            updateState();
        }
    }

    @Override
    public String toString() {
        return "NotificationIconContainer("
                + "dozing=" + mDozing + " onLockScreen=" + mOnLockScreen
                + " inNotificationIconShelf=" + mInNotificationIconShelf
                + " speedBumpIndex=" + mSpeedBumpIndex
                + " themedTextColorPrimary=#" + Integer.toHexString(mThemedTextColorPrimary) + ')';
    }

    @VisibleForTesting
    public void setIconSize(int size) {
        mIconSize = size;
    }

    public void updateState() {
        resetViewStates();
        calculateIconXTranslations();
        applyIconStates();
    }

    public void applyIconStates() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            ViewState childState = mIconStates.get(child);
            if (childState != null) {
                childState.applyToView(child);
            }
        }
        mAddAnimationStartIndex = -1;
        mCannedAnimationStartIndex = -1;
        mDisallowNextAnimation = false;
        mIsolatedIconForAnimation = null;
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        boolean isReplacingIcon = isReplacingIcon(child);
        if (!mChangingViewPositions) {
            IconState v = new IconState(child);
            if (isReplacingIcon) {
                v.justAdded = false;
                v.justReplaced = true;
            }
            mIconStates.put(child, v);
        }
        int childIndex = indexOfChild(child);
        if (childIndex < getChildCount() - 1 && !isReplacingIcon
            && mIconStates.get(getChildAt(childIndex + 1)).iconAppearAmount > 0.0f) {
            if (mAddAnimationStartIndex < 0) {
                mAddAnimationStartIndex = childIndex;
            } else {
                mAddAnimationStartIndex = Math.min(mAddAnimationStartIndex, childIndex);
            }
        }
        if (child instanceof StatusBarIconView) {
            ((StatusBarIconView) child).setDozing(mDozing, false, 0);
        }
    }

    private boolean isReplacingIcon(View child) {
        if (mReplacingIcons == null) {
            return false;
        }
        if (!(child instanceof StatusBarIconView)) {
            return false;
        }
        StatusBarIconView iconView = (StatusBarIconView) child;
        Icon sourceIcon = iconView.getSourceIcon();
        String groupKey = iconView.getNotification().getGroupKey();
        ArrayList<StatusBarIcon> statusBarIcons = mReplacingIcons.get(groupKey);
        if (statusBarIcons != null) {
            StatusBarIcon replacedIcon = statusBarIcons.get(0);
            if (sourceIcon.sameAs(replacedIcon.icon)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);

        if (child instanceof StatusBarIconView) {
            boolean isReplacingIcon = isReplacingIcon(child);
            final StatusBarIconView icon = (StatusBarIconView) child;
            if (areAnimationsEnabled(icon) && icon.getVisibleState() != StatusBarIconView.STATE_HIDDEN
                    && child.getVisibility() == VISIBLE && isReplacingIcon) {
                int animationStartIndex = findFirstViewIndexAfter(icon.getTranslationX());
                if (mAddAnimationStartIndex < 0) {
                    mAddAnimationStartIndex = animationStartIndex;
                } else {
                    mAddAnimationStartIndex = Math.min(mAddAnimationStartIndex, animationStartIndex);
                }
            }
            if (!mChangingViewPositions) {
                mIconStates.remove(child);
                if (areAnimationsEnabled(icon) && !isReplacingIcon) {
                    addTransientView(icon, 0);
                    boolean isIsolatedIcon = child == mIsolatedIcon;
                    icon.setVisibleState(StatusBarIconView.STATE_HIDDEN, true /* animate */,
                            () -> removeTransientView(icon),
                            isIsolatedIcon ? CONTENT_FADE_DURATION : 0);
                }
            }
        }
    }

    public boolean areIconsOverflowing() {
        return mIsShowingOverflowDot;
    }

    private boolean areAnimationsEnabled(StatusBarIconView icon) {
        return mAnimationsEnabled || icon == mIsolatedIcon;
    }

    /**
     * Finds the first view with a translation bigger then a given value
     */
    private int findFirstViewIndexAfter(float translationX) {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view.getTranslationX() > translationX) {
                return i;
            }
        }
        return getChildCount();
    }

    public void resetViewStates() {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            ViewState iconState = mIconStates.get(view);
            iconState.initFrom(view);
            iconState.setAlpha(mIsolatedIcon == null || view == mIsolatedIcon ? 1.0f : 0.0f);
            iconState.hidden = false;
        }
    }

    /**
     * @return Width of shelf for the given number of icons
     */
    public float calculateWidthFor(float numIcons) {
        if (numIcons == 0) {
            return 0f;
        }
        final float contentWidth =
                mIconSize * MathUtils.min(numIcons, MAX_ICONS_ON_LOCKSCREEN + 1);
        return getActualPaddingStart()
                + contentWidth
                + getActualPaddingEnd();
    }

    @VisibleForTesting
    boolean shouldForceOverflow(int i, int speedBumpIndex, float iconAppearAmount,
            int maxVisibleIcons) {
        return speedBumpIndex != -1 && i >= speedBumpIndex
                && iconAppearAmount > 0.0f || i >= maxVisibleIcons;
    }

    @VisibleForTesting
    boolean isOverflowing(boolean isLastChild, float translationX, float layoutEnd,
            float iconSize) {
        // Layout end, as used here, does not include padding end.
        final float overflowX = isLastChild ? layoutEnd : layoutEnd - iconSize;
        return translationX >= overflowX;
    }

    /**
     * Calculate the horizontal translations for each notification based on how much the icons
     * are inserted into the notification container.
     * If this is not a whole number, the fraction means by how much the icon is appearing.
     */
    public void calculateIconXTranslations() {
        float translationX = getActualPaddingStart();
        int firstOverflowIndex = -1;
        int childCount = getChildCount();
        int maxStatusVisibleIcons = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.MAX_VISIBLE_NOTIFICATION_ICONS, MAX_STATIC_ICONS, UserHandle.USER_CURRENT);
        int maxVisibleIcons = mOnLockScreen ? MAX_ICONS_ON_AOD :
                mIsStaticLayout ? maxStatusVisibleIcons : childCount;
        float layoutEnd = getLayoutEnd();
        mVisualOverflowStart = 0;
        mFirstVisibleIconState = null;
        for (int i = 0; i < childCount; i++) {
            View view = getChildAt(i);
            IconState iconState = mIconStates.get(view);
            if (iconState.iconAppearAmount == 1.0f) {
                // We only modify the xTranslation if it's fully inside of the container
                // since during the transition to the shelf, the translations are controlled
                // from the outside
                iconState.setXTranslation(translationX);
            }
            if (mFirstVisibleIconState == null) {
                mFirstVisibleIconState = iconState;
            }
            iconState.visibleState = iconState.hidden
                    ? StatusBarIconView.STATE_HIDDEN
                    : StatusBarIconView.STATE_ICON;

            final boolean forceOverflow = shouldForceOverflow(i, mSpeedBumpIndex,
                    iconState.iconAppearAmount, maxVisibleIcons);
            final boolean isOverflowing = forceOverflow || isOverflowing(
                    /* isLastChild= */ i == childCount - 1, translationX, layoutEnd, mIconSize);

            // First icon to overflow.
            if (firstOverflowIndex == -1 && isOverflowing) {
                firstOverflowIndex = i;
                mVisualOverflowStart = layoutEnd - mIconSize;
                if (forceOverflow || mIsStaticLayout) {
                    mVisualOverflowStart = Math.min(translationX, mVisualOverflowStart);
                }
            }
            final float drawingScale = mOnLockScreen && view instanceof StatusBarIconView
                    ? ((StatusBarIconView) view).getIconScaleIncreased()
                    : 1f;
            translationX += iconState.iconAppearAmount * view.getWidth() * drawingScale;
        }
        mIsShowingOverflowDot = false;
        if (firstOverflowIndex != -1) {
            translationX = mVisualOverflowStart;
            for (int i = firstOverflowIndex; i < childCount; i++) {
                View view = getChildAt(i);
                IconState iconState = mIconStates.get(view);
                int dotWidth = mStaticDotDiameter + mDotPadding;
                iconState.setXTranslation(translationX);
                if (!mIsShowingOverflowDot) {
                    if (iconState.iconAppearAmount < 0.8f) {
                        iconState.visibleState = StatusBarIconView.STATE_ICON;
                    } else {
                        iconState.visibleState = StatusBarIconView.STATE_DOT;
                        mIsShowingOverflowDot = true;
                    }
                    translationX += dotWidth * iconState.iconAppearAmount;
                    mLastVisibleIconState = iconState;
                } else {
                    iconState.visibleState = StatusBarIconView.STATE_HIDDEN;
                }
            }
        } else if (childCount > 0) {
            View lastChild = getChildAt(childCount - 1);
            mLastVisibleIconState = mIconStates.get(lastChild);
            mFirstVisibleIconState = mIconStates.get(getChildAt(0));
        }
        if (isLayoutRtl()) {
            for (int i = 0; i < childCount; i++) {
                View view = getChildAt(i);
                IconState iconState = mIconStates.get(view);
                iconState.setXTranslation(
                        getWidth() - iconState.getXTranslation() - view.getWidth());
            }
        }
        if (mIsolatedIcon != null) {
            IconState iconState = mIconStates.get(mIsolatedIcon);
            if (iconState != null) {
                // Most of the time the icon isn't yet added when this is called but only happening
                // later
                iconState.setXTranslation(mIsolatedIconLocation.left - mAbsolutePosition[0]
                        - (1 - mIsolatedIcon.getIconScale()) * mIsolatedIcon.getWidth() / 2.0f);
                iconState.visibleState = StatusBarIconView.STATE_ICON;
            }
        }
    }

    private int getMaxVisibleIcons(int childCount) {
        return mOnLockScreen ? MAX_ICONS_ON_AOD :
                mIsStaticLayout ? MAX_STATIC_ICONS : childCount;
    }

    private float getLayoutEnd() {
        return getActualWidth() - getActualPaddingEnd();
    }

    private float getActualPaddingEnd() {
        if (mActualPaddingEnd == NO_VALUE) {
            return getPaddingEnd();
        }
        return mActualPaddingEnd;
    }

    /**
     * @return the actual startPadding of this view
     */
    public float getActualPaddingStart() {
        if (mActualPaddingStart == NO_VALUE) {
            return getPaddingStart();
        }
        return mActualPaddingStart;
    }

    /**
     * Sets whether the layout should always show the same number of icons.
     * If this is true, the icon positions will be updated on layout.
     * If this if false, the layout is managed from the outside and layouting won't trigger a
     * repositioning of the icons.
     */
    public void setIsStaticLayout(boolean isStaticLayout) {
        mIsStaticLayout = isStaticLayout;
    }

    public void setActualLayoutWidth(int actualLayoutWidth) {
        mActualLayoutWidth = actualLayoutWidth;
        if (DEBUG) {
            invalidate();
        }
    }

    public void setActualPaddingEnd(float paddingEnd) {
        mActualPaddingEnd = paddingEnd;
        if (DEBUG) {
            invalidate();
        }
    }

    public void setActualPaddingStart(float paddingStart) {
        mActualPaddingStart = paddingStart;
        if (DEBUG) {
            invalidate();
        }
    }

    public int getActualWidth() {
        if (mActualLayoutWidth == NO_VALUE) {
            return getWidth();
        }
        return mActualLayoutWidth;
    }

    public int getFinalTranslationX() {
        if (mLastVisibleIconState == null) {
            return 0;
        }

        int translation = (int) (isLayoutRtl()
                ? getWidth() - mLastVisibleIconState.getXTranslation()
                : mLastVisibleIconState.getXTranslation() + mIconSize);

        // There's a chance that last translation goes beyond the edge maybe
        return Math.min(getWidth(), translation);
    }

    public void setChangingViewPositions(boolean changingViewPositions) {
        mChangingViewPositions = changingViewPositions;
    }

    public void setDozing(boolean dozing, boolean fade, long delay) {
        mDozing = dozing;
        mDisallowNextAnimation |= !fade;
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view instanceof StatusBarIconView) {
                ((StatusBarIconView) view).setDozing(dozing, fade, delay);
            }
        }
    }

    public IconState getIconState(StatusBarIconView icon) {
        return mIconStates.get(icon);
    }

    public void setSpeedBumpIndex(int speedBumpIndex) {
        mSpeedBumpIndex = speedBumpIndex;
    }

    public int getIconSize() {
        return mIconSize;
    }

    public void setAnimationsEnabled(boolean enabled) {
        if (!enabled && mAnimationsEnabled) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                ViewState childState = mIconStates.get(child);
                if (childState != null) {
                    childState.cancelAnimations(child);
                    childState.applyToView(child);
                }
            }
        }
        mAnimationsEnabled = enabled;
    }

    public void setReplacingIcons(ArrayMap<String, ArrayList<StatusBarIcon>> replacingIcons) {
        mReplacingIcons = replacingIcons;
    }

    public void showIconIsolated(StatusBarIconView icon, boolean animated) {
        if (animated) {
            mIsolatedIconForAnimation = icon != null ? icon : mIsolatedIcon;
        }
        mIsolatedIcon = icon;
        updateState();
    }

    public void setIsolatedIconLocation(Rect isolatedIconLocation, boolean requireUpdate) {
        mIsolatedIconLocation = isolatedIconLocation;
        if (requireUpdate) {
            updateState();
        }
    }

    /**
     * Set whether the device is on the lockscreen and which lockscreen mode the device is
     * configured to. Depending on these values, the layout of the AOD icons change.
     */
    public void setOnLockScreen(boolean onLockScreen) {
        mOnLockScreen = onLockScreen;
    }

    public void setInNotificationIconShelf(boolean inShelf) {
        mInNotificationIconShelf = inShelf;
    }

    public class IconState extends ViewState {
        public static final int NO_VALUE = NotificationIconContainer.NO_VALUE;
        public float iconAppearAmount = 1.0f;
        public float clampedAppearAmount = 1.0f;
        public int visibleState;
        public boolean justAdded = true;
        private boolean justReplaced;
        public boolean needsCannedAnimation;
        public int iconColor = StatusBarIconView.NO_COLOR;
        public boolean noAnimations;
        private final View mView;

        private final Consumer<Property> mCannedAnimationEndListener;

        public IconState(View child) {
            mView = child;
            mCannedAnimationEndListener = (property) -> {
                // If we finished animating out of the shelf
                if (property == View.TRANSLATION_Y && iconAppearAmount == 0.0f
                        && mView.getVisibility() == VISIBLE) {
                    mView.setVisibility(INVISIBLE);
                }
            };
        }

        @Override
        public void applyToView(View view) {
            if (view instanceof StatusBarIconView) {
                StatusBarIconView icon = (StatusBarIconView) view;
                boolean animate = false;
                AnimationProperties animationProperties = null;
                final boolean isLowPriorityIconChange =
                        (visibleState == StatusBarIconView.STATE_HIDDEN
                                && icon.getVisibleState() == StatusBarIconView.STATE_DOT)
                        || (visibleState == StatusBarIconView.STATE_DOT
                            && icon.getVisibleState() == StatusBarIconView.STATE_HIDDEN);
                final boolean animationsAllowed = areAnimationsEnabled(icon)
                        && !mDisallowNextAnimation
                        && !noAnimations
                        && !isLowPriorityIconChange;
                if (animationsAllowed) {
                    if (justAdded || justReplaced) {
                        super.applyToView(icon);
                        if (justAdded && iconAppearAmount != 0.0f) {
                            icon.setAlpha(0.0f);
                            icon.setVisibleState(StatusBarIconView.STATE_HIDDEN,
                                    false /* animate */);
                            animationProperties = ADD_ICON_PROPERTIES;
                            animate = true;
                        }
                    } else if (visibleState != icon.getVisibleState()) {
                        animationProperties = DOT_ANIMATION_PROPERTIES;
                        animate = true;
                    }
                    if (!animate && mAddAnimationStartIndex >= 0
                            && indexOfChild(view) >= mAddAnimationStartIndex
                            && (icon.getVisibleState() != StatusBarIconView.STATE_HIDDEN
                            || visibleState != StatusBarIconView.STATE_HIDDEN)) {
                        animationProperties = DOT_ANIMATION_PROPERTIES;
                        animate = true;
                    }
                    if (needsCannedAnimation) {
                        AnimationFilter animationFilter = sTempProperties.getAnimationFilter();
                        animationFilter.reset();
                        animationFilter.combineFilter(
                                ICON_ANIMATION_PROPERTIES.getAnimationFilter());
                        sTempProperties.resetCustomInterpolators();
                        sTempProperties.combineCustomInterpolators(ICON_ANIMATION_PROPERTIES);
                        Interpolator interpolator;
                        if (icon.showsConversation()) {
                            interpolator = Interpolators.ICON_OVERSHOT_LESS;
                        } else {
                            interpolator = Interpolators.ICON_OVERSHOT;
                        }
                        sTempProperties.setCustomInterpolator(View.TRANSLATION_Y, interpolator);
                        sTempProperties.setAnimationEndAction(mCannedAnimationEndListener);
                        if (animationProperties != null) {
                            animationFilter.combineFilter(animationProperties.getAnimationFilter());
                            sTempProperties.combineCustomInterpolators(animationProperties);
                        }
                        animationProperties = sTempProperties;
                        animationProperties.setDuration(CANNED_ANIMATION_DURATION);
                        animate = true;
                        mCannedAnimationStartIndex = indexOfChild(view);
                    }
                    if (!animate && mCannedAnimationStartIndex >= 0
                            && indexOfChild(view) > mCannedAnimationStartIndex
                            && (icon.getVisibleState() != StatusBarIconView.STATE_HIDDEN
                            || visibleState != StatusBarIconView.STATE_HIDDEN)) {
                        AnimationFilter animationFilter = sTempProperties.getAnimationFilter();
                        animationFilter.reset();
                        animationFilter.animateX();
                        sTempProperties.resetCustomInterpolators();
                        animationProperties = sTempProperties;
                        animationProperties.setDuration(CANNED_ANIMATION_DURATION);
                        animate = true;
                    }
                    if (mIsolatedIconForAnimation != null) {
                        if (view == mIsolatedIconForAnimation) {
                            animationProperties = UNISOLATION_PROPERTY;
                            animationProperties.setDelay(
                                    mIsolatedIcon != null ? CONTENT_FADE_DELAY : 0);
                        } else {
                            animationProperties = UNISOLATION_PROPERTY_OTHERS;
                            animationProperties.setDelay(
                                    mIsolatedIcon == null ? CONTENT_FADE_DELAY : 0);
                        }
                        animate = true;
                    }
                }
                icon.setVisibleState(visibleState, animationsAllowed);
                boolean newIconStyle = Settings.System.getIntForUser(getContext().getContentResolver(),
                            Settings.System.STATUSBAR_COLORED_ICONS, 0, UserHandle.USER_CURRENT) == 1;
                if (icon.getStatusBarIcon().pkg.contains("systemui") || !newIconStyle) {
                    icon.setIconColor(mInNotificationIconShelf ? mThemedTextColorPrimary : iconColor,
                            needsCannedAnimation && animationsAllowed);
                } else {
                    icon.setIconColor(StatusBarIconView.NO_COLOR,
                            needsCannedAnimation && animationsAllowed);
                }
                if (animate) {
                    animateTo(icon, animationProperties);
                } else {
                    super.applyToView(view);
                }
                sTempProperties.setAnimationEndAction(null);
                boolean inShelf = iconAppearAmount == 1.0f;
                icon.setIsInShelf(inShelf);
            }
            justAdded = false;
            justReplaced = false;
            needsCannedAnimation = false;
        }

        @Override
        public void initFrom(View view) {
            super.initFrom(view);
            if (view instanceof StatusBarIconView) {
                StatusBarIconView icon = (StatusBarIconView) view;
                boolean newIconStyle = Settings.System.getIntForUser(getContext().getContentResolver(),
                            Settings.System.STATUSBAR_COLORED_ICONS, 0, UserHandle.USER_CURRENT) == 1;
                if (icon.getStatusBarIcon().pkg.contains("systemui") || !newIconStyle) {
                    iconColor = ((StatusBarIconView) view).getStaticDrawableColor();
                } else {
                    iconColor = StatusBarIconView.NO_COLOR;
                }
            }
        }
    }
}
