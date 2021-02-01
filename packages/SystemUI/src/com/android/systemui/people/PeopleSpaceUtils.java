/*
 * Copyright (C) 2020 The Android Open Source Project
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
 */

package com.android.systemui.people;

import static android.app.Notification.EXTRA_MESSAGES;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.people.ConversationChannel;
import android.app.people.IPeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.database.Cursor;
import android.database.SQLException;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.service.notification.ConversationChannelWrapper;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import androidx.preference.PreferenceManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.ArrayUtils;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.R;
import com.android.systemui.people.widget.LaunchConversationActivity;
import com.android.systemui.people.widget.PeopleSpaceWidgetProvider;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Utils class for People Space. */
public class PeopleSpaceUtils {
    /** Turns on debugging information about People Space. */
    public static final boolean DEBUG = true;
    private static final String TAG = "PeopleSpaceUtils";
    private static final int DAYS_IN_A_WEEK = 7;
    private static final int MIN_HOUR = 1;
    private static final int ONE_DAY = 1;
    public static final String OPTIONS_PEOPLE_SPACE_TILE = "options_people_space_tile";

    private static final Pattern DOUBLE_EXCLAMATION_PATTERN = Pattern.compile("[!][!]+");
    private static final Pattern DOUBLE_QUESTION_PATTERN = Pattern.compile("[?][?]+");
    private static final Pattern ANY_DOUBLE_MARK_PATTERN = Pattern.compile("[!?][!?]+");
    private static final Pattern MIXED_MARK_PATTERN = Pattern.compile("![?].*|.*[?]!");

    /** Represents whether {@link StatusBarNotification} was posted or removed. */
    public enum NotificationAction {
        POSTED,
        REMOVED
    }

    /**
     * The UiEvent enums that this class can log.
     */
    public enum PeopleSpaceWidgetEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "People space widget deleted")
        PEOPLE_SPACE_WIDGET_DELETED(666),
        @UiEvent(doc = "People space widget added")
        PEOPLE_SPACE_WIDGET_ADDED(667),
        @UiEvent(doc = "People space widget clicked to launch conversation")
        PEOPLE_SPACE_WIDGET_CLICKED(668);

        private final int mId;

        PeopleSpaceWidgetEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    /** Returns a list of map entries corresponding to user's conversations. */
    public static List<PeopleSpaceTile> getTiles(
            Context context, INotificationManager notificationManager, IPeopleManager peopleManager,
            LauncherApps launcherApps)
            throws Exception {
        boolean showOnlyPriority = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0) == 1;
        List<ConversationChannelWrapper> conversations =
                notificationManager.getConversations(
                        false).getList();

        // Add priority conversations to tiles list.
        Stream<ShortcutInfo> priorityConversations = conversations.stream()
                .filter(c -> c.getNotificationChannel() != null
                        && c.getNotificationChannel().isImportantConversation())
                .map(c -> c.getShortcutInfo());
        List<PeopleSpaceTile> tiles = getSortedTiles(peopleManager, launcherApps,
                priorityConversations);

        // Sort and then add recent and non priority conversations to tiles list.
        if (!showOnlyPriority) {
            if (DEBUG) Log.d(TAG, "Add recent conversations");
            Stream<ShortcutInfo> nonPriorityConversations = conversations.stream()
                    .filter(c -> c.getNotificationChannel() == null
                            || !c.getNotificationChannel().isImportantConversation())
                    .map(c -> c.getShortcutInfo());

            List<ConversationChannel> recentConversationsList =
                    peopleManager.getRecentConversations().getList();
            Stream<ShortcutInfo> recentConversations = recentConversationsList
                    .stream()
                    .map(c -> c.getShortcutInfo());

            Stream<ShortcutInfo> mergedStream = Stream.concat(nonPriorityConversations,
                    recentConversations);
            List<PeopleSpaceTile> recentTiles =
                    getSortedTiles(peopleManager, launcherApps, mergedStream);
            tiles.addAll(recentTiles);
        }
        return tiles;
    }

    /**
     * Updates {@code appWidgetIds} with their associated conversation stored, handling a
     * notification being posted or removed.
     */
    public static void updateSingleConversationWidgets(Context context, int[] appWidgetIds,
            AppWidgetManager appWidgetManager, INotificationManager notificationManager) {
        IPeopleManager peopleManager = IPeopleManager.Stub.asInterface(
                ServiceManager.getService(Context.PEOPLE_SERVICE));
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        Map<Integer, PeopleSpaceTile> widgetIdToTile = new HashMap<>();
        try {
            List<PeopleSpaceTile> tiles =
                    PeopleSpaceUtils.getTiles(context, notificationManager,
                            peopleManager, launcherApps);
            for (int appWidgetId : appWidgetIds) {
                String shortcutId = sp.getString(String.valueOf(appWidgetId), null);
                if (DEBUG) {
                    Log.d(TAG, "Widget ID: " + appWidgetId + " Shortcut ID: " + shortcutId);
                }

                Optional<PeopleSpaceTile> entry = tiles.stream().filter(
                        e -> e.getId().equals(shortcutId)).findFirst();

                if (!entry.isPresent() || shortcutId == null) {
                    if (DEBUG) Log.d(TAG, "Matching conversation not found for shortcut ID");
                    //TODO: Delete app widget id when crash is fixed (b/175486868)
                    continue;
                }
                // Augment current tile based on stored fields.
                PeopleSpaceTile tile = augmentTileFromStorage(entry.get(), appWidgetManager,
                        appWidgetId);

                RemoteViews views = createRemoteViews(context, tile, appWidgetId);

                // Tell the AppWidgetManager to perform an update on the current app widget.
                appWidgetManager.updateAppWidget(appWidgetId, views);

                widgetIdToTile.put(appWidgetId, tile);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to retrieve conversations to set tiles: " + e);
        }
        getBirthdaysOnBackgroundThread(context, appWidgetManager, widgetIdToTile, appWidgetIds);
    }

    /** Augment {@link PeopleSpaceTile} with fields from stored tile. */
    @VisibleForTesting
    static PeopleSpaceTile augmentTileFromStorage(PeopleSpaceTile tile,
            AppWidgetManager appWidgetManager, int appWidgetId) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        PeopleSpaceTile storedTile = options.getParcelable(OPTIONS_PEOPLE_SPACE_TILE);
        if (storedTile == null) {
            return tile;
        }
        return tile.toBuilder()
                .setBirthdayText(storedTile.getBirthdayText())
                .setNotificationKey(storedTile.getNotificationKey())
                .setNotificationContent(storedTile.getNotificationContent())
                .setNotificationDataUri(storedTile.getNotificationDataUri())
                .build();
    }

    /** If incoming notification changed tile, store the changes in the tile options. */
    public static void storeNotificationChange(StatusBarNotification sbn,
            NotificationAction notificationAction, AppWidgetManager appWidgetManager,
            int appWidgetId) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        PeopleSpaceTile storedTile = options.getParcelable(OPTIONS_PEOPLE_SPACE_TILE);
        if (storedTile == null) {
            if (DEBUG) Log.d(TAG, "Could not find stored tile to add notification to");
            return;
        }
        if (notificationAction == PeopleSpaceUtils.NotificationAction.POSTED) {
            if (DEBUG) Log.i(TAG, "Adding notification to storage, appWidgetId: " + appWidgetId);
            Notification.MessagingStyle.Message message = getLastMessagingStyleMessage(sbn);
            if (message == null) {
                if (DEBUG) Log.i(TAG, "Notification doesn't have content, skipping.");
                return;
            }
            storedTile = storedTile
                    .toBuilder()
                    .setNotificationKey(sbn.getKey())
                    .setNotificationContent(message.getText())
                    .setNotificationDataUri(message.getDataUri())
                    .build();
        } else {
            if (DEBUG) {
                Log.i(TAG, "Removing notification from storage, appWidgetId: " + appWidgetId);
            }
            storedTile = storedTile
                    .toBuilder()
                    .setNotificationKey(null)
                    .setNotificationContent(null)
                    .setNotificationDataUri(null)
                    .build();
        }
        updateAppWidgetOptions(appWidgetManager, appWidgetId, storedTile);
    }

    private static void updateAppWidgetOptions(AppWidgetManager appWidgetManager, int appWidgetId,
            PeopleSpaceTile tile) {
        if (tile == null) {
            if (DEBUG) Log.d(TAG, "Requested to store null tile");
            return;
        }
        Bundle newOptions = new Bundle();
        newOptions.putParcelable(OPTIONS_PEOPLE_SPACE_TILE, tile);
        appWidgetManager.updateAppWidgetOptions(appWidgetId, newOptions);
    }

    /** Creates a {@link RemoteViews} for {@code tile}. */
    private static RemoteViews createRemoteViews(Context context,
            PeopleSpaceTile tile, int appWidgetId) {
        RemoteViews views;
        if (tile.getNotificationKey() != null) {
            views = createNotificationRemoteViews(context, tile);
        } else if (tile.getBirthdayText() != null) {
            views = createStatusRemoteViews(context, tile);
        } else {
            views = createLastInteractionRemoteViews(context, tile);
        }
        return setCommonRemoteViewsFields(context, views, tile, appWidgetId);
    }

    private static RemoteViews setCommonRemoteViewsFields(Context context, RemoteViews views,
            PeopleSpaceTile tile, int appWidgetId) {
        try {
            views.setTextViewText(R.id.name, tile.getUserName().toString());
            views.setImageViewBitmap(
                    R.id.package_icon,
                    PeopleSpaceUtils.convertDrawableToBitmap(
                            context.getPackageManager().getApplicationIcon(
                                    tile.getPackageName())
                    )
            );
            views.setImageViewIcon(R.id.person_icon, tile.getUserIcon());
            views.setBoolean(R.id.content_background, "setClipToOutline", true);

            Intent activityIntent = new Intent(context, LaunchConversationActivity.class);
            activityIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_HISTORY
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            activityIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_TILE_ID, tile.getId());
            activityIntent.putExtra(
                    PeopleSpaceWidgetProvider.EXTRA_PACKAGE_NAME, tile.getPackageName());
            activityIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_UID, tile.getUid());
            views.setOnClickPendingIntent(R.id.item, PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
            return views;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set common fields: " + e);
        }
        return null;
    }

    private static RemoteViews createNotificationRemoteViews(Context context,
            PeopleSpaceTile tile) {
        RemoteViews views = new RemoteViews(
                context.getPackageName(), R.layout.people_space_small_avatar_tile);
        Uri image = tile.getNotificationDataUri();
        if (image != null) {
            //TODO: Use NotificationInlineImageCache
            views.setImageViewUri(R.id.image, image);
            views.setViewVisibility(R.id.image, View.VISIBLE);
            views.setViewVisibility(R.id.content, View.GONE);
        } else {
            CharSequence content = tile.getNotificationContent();
            views = setPunctuationRemoteViewsFields(views, content);
            views.setTextViewText(R.id.content, content);
            views.setViewVisibility(R.id.content, View.VISIBLE);
            views.setViewVisibility(R.id.image, View.GONE);
        }
        views.setTextViewText(R.id.time, PeopleSpaceUtils.getLastInteractionString(
                context, tile.getLastInteractionTimestamp(), false));
        return views;
    }

    private static RemoteViews createStatusRemoteViews(Context context,
            PeopleSpaceTile tile) {
        RemoteViews views = new RemoteViews(
                context.getPackageName(), R.layout.people_space_large_avatar_tile);
        views.setTextViewText(R.id.status, tile.getBirthdayText());
        return views;
    }

    private static RemoteViews createLastInteractionRemoteViews(Context context,
            PeopleSpaceTile tile) {
        RemoteViews views = new RemoteViews(
                context.getPackageName(), R.layout.people_space_large_avatar_tile);
        String status = PeopleSpaceUtils.getLastInteractionString(
                context, tile.getLastInteractionTimestamp(), true);
        views.setTextViewText(R.id.status, status);
        return views;
    }

    private static RemoteViews setPunctuationRemoteViewsFields(
            RemoteViews views, CharSequence content) {
        String punctuation = getBackgroundTextFromMessage(content.toString());
        int visibility = View.GONE;
        if (punctuation != null) {
            visibility = View.VISIBLE;
        }
        views.setTextViewText(R.id.punctuation1, punctuation);
        views.setTextViewText(R.id.punctuation2, punctuation);
        views.setTextViewText(R.id.punctuation3, punctuation);
        views.setTextViewText(R.id.punctuation4, punctuation);
        views.setTextViewText(R.id.punctuation5, punctuation);
        views.setTextViewText(R.id.punctuation6, punctuation);

        views.setViewVisibility(R.id.punctuation1, visibility);
        views.setViewVisibility(R.id.punctuation2, visibility);
        views.setViewVisibility(R.id.punctuation3, visibility);
        views.setViewVisibility(R.id.punctuation4, visibility);
        views.setViewVisibility(R.id.punctuation5, visibility);
        views.setViewVisibility(R.id.punctuation6, visibility);

        return views;
    }

    /** Gets character for tile background decoration based on notification content. */
    @VisibleForTesting
    static String getBackgroundTextFromMessage(String message) {
        if (!ANY_DOUBLE_MARK_PATTERN.matcher(message).find()) {
            return null;
        }
        if (MIXED_MARK_PATTERN.matcher(message).find()) {
            return "!?";
        }
        Matcher doubleQuestionMatcher = DOUBLE_QUESTION_PATTERN.matcher(message);
        if (!doubleQuestionMatcher.find()) {
            return "!";
        }
        Matcher doubleExclamationMatcher = DOUBLE_EXCLAMATION_PATTERN.matcher(message);
        if (!doubleExclamationMatcher.find()) {
            return "?";
        }
        // If we have both "!!" and "??", return the one that comes first.
        if (doubleQuestionMatcher.start() < doubleExclamationMatcher.start()) {
            return "?";
        }
        return "!";
    }

    /** Gets the most recent {@link Notification.MessagingStyle.Message} from the notification. */
    @VisibleForTesting
    public static Notification.MessagingStyle.Message getLastMessagingStyleMessage(
            StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) {
            return null;
        }
        if (Notification.MessagingStyle.class.equals(notification.getNotificationStyle())
                && notification.extras != null) {
            final Parcelable[] messages = notification.extras.getParcelableArray(EXTRA_MESSAGES);
            if (!ArrayUtils.isEmpty(messages)) {
                List<Notification.MessagingStyle.Message> sortedMessages =
                        Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages);
                sortedMessages.sort(Collections.reverseOrder(
                        Comparator.comparing(Notification.MessagingStyle.Message::getTimestamp)));
                return sortedMessages.get(0);
            }
        }
        return null;
    }

    /** Returns a list sorted by ascending last interaction time from {@code stream}. */
    private static List<PeopleSpaceTile> getSortedTiles(IPeopleManager peopleManager,
            LauncherApps launcherApps,
            Stream<ShortcutInfo> stream) {
        return stream
                .filter(Objects::nonNull)
                .map(c -> new PeopleSpaceTile.Builder(c, launcherApps).build())
                .filter(c -> shouldKeepConversation(c))
                .map(c -> c.toBuilder().setLastInteractionTimestamp(
                        getLastInteraction(peopleManager, c)).build())
                .sorted((c1, c2) -> new Long(c2.getLastInteractionTimestamp()).compareTo(
                        new Long(c1.getLastInteractionTimestamp())))
                .collect(Collectors.toList());
    }

    /** Returns the last interaction time with the user specified by {@code PeopleSpaceTile}. */
    private static Long getLastInteraction(IPeopleManager peopleManager,
            PeopleSpaceTile tile) {
        try {
            int userId = UserHandle.getUserHandleForUid(tile.getUid()).getIdentifier();
            String pkg = tile.getPackageName();
            return peopleManager.getLastInteraction(pkg, userId, tile.getId());
        } catch (Exception e) {
            Log.e(TAG, "Couldn't retrieve last interaction time", e);
            return 0L;
        }
    }

    /** Converts {@code drawable} to a {@link Bitmap}. */
    public static Bitmap convertDrawableToBitmap(Drawable drawable) {
        if (drawable == null) {
            return null;
        }

        if (drawable instanceof BitmapDrawable) {
            BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if (bitmapDrawable.getBitmap() != null) {
                return bitmapDrawable.getBitmap();
            }
        }

        Bitmap bitmap;
        if (drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            // Single color bitmap will be created of 1x1 pixel
        } else {
            bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        }

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    /** Returns a readable status describing the {@code lastInteraction}. */
    public static String getLastInteractionString(Context context, long lastInteraction,
            boolean includeLastChatted) {
        if (lastInteraction == 0L) {
            Log.e(TAG, "Could not get valid last interaction");
            return context.getString(R.string.basic_status);
        }
        long now = System.currentTimeMillis();
        Duration durationSinceLastInteraction = Duration.ofMillis(now - lastInteraction);
        MeasureFormat formatter = MeasureFormat.getInstance(Locale.getDefault(),
                MeasureFormat.FormatWidth.WIDE);
        if (durationSinceLastInteraction.toHours() < MIN_HOUR) {
            return context.getString(includeLastChatted ? R.string.last_interaction_status_less_than
                            : R.string.less_than_timestamp,
                    formatter.formatMeasures(new Measure(MIN_HOUR, MeasureUnit.HOUR)));
        } else if (durationSinceLastInteraction.toDays() < ONE_DAY) {
            return context.getString(
                    includeLastChatted ? R.string.last_interaction_status : R.string.timestamp,
                    formatter.formatMeasures(
                            new Measure(durationSinceLastInteraction.toHours(), MeasureUnit.HOUR)));
        } else if (durationSinceLastInteraction.toDays() < DAYS_IN_A_WEEK) {
            return context.getString(
                    includeLastChatted ? R.string.last_interaction_status : R.string.timestamp,
                    formatter.formatMeasures(
                            new Measure(durationSinceLastInteraction.toDays(), MeasureUnit.DAY)));
        } else {
            return context.getString(durationSinceLastInteraction.toDays() == DAYS_IN_A_WEEK
                            ? (includeLastChatted ? R.string.last_interaction_status :
                            R.string.timestamp) :
                            (includeLastChatted ? R.string.last_interaction_status_over
                                    : R.string.over_timestamp),
                    formatter.formatMeasures(
                            new Measure(durationSinceLastInteraction.toDays() / DAYS_IN_A_WEEK,
                                    MeasureUnit.WEEK)));
        }
    }

    /**
     * Returns whether the {@code conversation} should be kept for display in the People Space.
     *
     * <p>A valid {@code conversation} must:
     *     <ul>
     *         <li>Have a non-null {@link PeopleSpaceTile}
     *         <li>Have an associated label in the {@link PeopleSpaceTile}
     *     </ul>
     * </li>
     */
    public static boolean shouldKeepConversation(PeopleSpaceTile tile) {
        return tile != null && tile.getUserName().length() != 0;
    }

    private static boolean hasBirthdayStatus(PeopleSpaceTile tile, Context context) {
        return tile.getBirthdayText() != null && tile.getBirthdayText().equals(
                context.getString(R.string.birthday_status));
    }

    /** Calls to retrieve birthdays on a background thread. */
    private static void getBirthdaysOnBackgroundThread(Context context,
            AppWidgetManager appWidgetManager,
            Map<Integer, PeopleSpaceTile> peopleSpaceTiles, int[] appWidgetIds) {
        ThreadUtils.postOnBackgroundThread(
                () -> getBirthdays(context, appWidgetManager, peopleSpaceTiles, appWidgetIds));
    }

    /** Queries the Contacts DB for any birthdays today. */
    @VisibleForTesting
    public static void getBirthdays(Context context, AppWidgetManager appWidgetManager,
            Map<Integer, PeopleSpaceTile> widgetIdToTile, int[] appWidgetIds) {
        if (DEBUG) Log.d(TAG, "Get birthdays");
        if (appWidgetIds.length == 0) return;
        List<String> lookupKeysWithBirthdaysToday = getContactLookupKeysWithBirthdaysToday(context);
        for (int appWidgetId : appWidgetIds) {
            PeopleSpaceTile storedTile = widgetIdToTile.get(appWidgetId);
            if (storedTile == null || storedTile.getContactUri() == null) {
                if (DEBUG) Log.d(TAG, "No contact uri for: " + storedTile);
                removeBirthdayStatusIfPresent(appWidgetManager, context, storedTile, appWidgetId);
                continue;
            }
            if (lookupKeysWithBirthdaysToday.isEmpty()) {
                if (DEBUG) Log.d(TAG, "No birthdays today");
                removeBirthdayStatusIfPresent(appWidgetManager, context, storedTile, appWidgetId);
                continue;
            }
            updateTileWithBirthday(context, appWidgetManager, lookupKeysWithBirthdaysToday,
                    storedTile,
                    appWidgetId);
        }
    }

    /** Removes the birthday status if present in {@code storedTile} and pushes the update. */
    private static void removeBirthdayStatusIfPresent(AppWidgetManager appWidgetManager,
            Context context, PeopleSpaceTile storedTile, int appWidgetId) {
        if (hasBirthdayStatus(storedTile, context)) {
            if (DEBUG) Log.d(TAG, "Remove " + storedTile.getUserName() + "'s birthday");
            updateAppWidgetOptionsAndView(appWidgetManager, context, appWidgetId,
                    storedTile.toBuilder()
                            .setBirthdayText(null)
                            .build());
        }
    }

    /**
     * Update {@code storedTile} if the contact has a lookup key matched to any {@code
     * lookupKeysWithBirthdays}.
     */
    private static void updateTileWithBirthday(Context context, AppWidgetManager appWidgetManager,
            List<String> lookupKeysWithBirthdaysToday, PeopleSpaceTile storedTile,
            int appWidgetId) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(storedTile.getContactUri(),
                    null, null, null, null);
            while (cursor != null && cursor.moveToNext()) {
                String storedLookupKey = cursor.getString(
                        cursor.getColumnIndex(ContactsContract.CommonDataKinds.Event.LOOKUP_KEY));
                if (!storedLookupKey.isEmpty() && lookupKeysWithBirthdaysToday.contains(
                        storedLookupKey)) {
                    if (DEBUG) Log.d(TAG, storedTile.getUserName() + "'s birthday today!");
                    updateAppWidgetOptionsAndView(appWidgetManager, context, appWidgetId,
                            storedTile.toBuilder()
                                    .setBirthdayText(context.getString(R.string.birthday_status))
                                    .build());
                    return;
                }
            }
        } catch (SQLException e) {
            Log.e(TAG, "Failed to query contact: " + e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        removeBirthdayStatusIfPresent(appWidgetManager, context, storedTile, appWidgetId);
    }

    /** Update app widget options and the current view. */
    private static void updateAppWidgetOptionsAndView(AppWidgetManager appWidgetManager,
            Context context, int appWidgetId, PeopleSpaceTile tile) {
        updateAppWidgetOptions(appWidgetManager, appWidgetId, tile);
        RemoteViews views = createRemoteViews(context,
                tile, appWidgetId);
        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    /**
     * Returns lookup keys for all contacts with a birthday today.
     *
     * <p>Birthdays are queried from a different table within the Contacts DB than the table for
     * the Contact Uri provided by most messaging apps. Matching by the contact ID is then quite
     * fragile as the row IDs across the different tables are not guaranteed to stay aligned, so we
     * match the data by {@link ContactsContract.ContactsColumns#LOOKUP_KEY} key to ensure proper
     * matching across all the Contacts DB tables.
     */
    private static List<String> getContactLookupKeysWithBirthdaysToday(Context context) {
        List<String> lookupKeysWithBirthdaysToday = new ArrayList<>(1);
        String today = new SimpleDateFormat("MM-dd").format(new Date());
        String[] projection = new String[]{
                ContactsContract.CommonDataKinds.Event.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Event.START_DATE};
        String where =
                ContactsContract.Data.MIMETYPE
                        + "= ? AND " + ContactsContract.CommonDataKinds.Event.TYPE + "="
                        + ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY + " AND substr("
                        + ContactsContract.CommonDataKinds.Event.START_DATE + ",6) = ?";
        String[] selection =
                new String[]{ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, today};
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                    projection, where, selection, null);
            while (cursor != null && cursor.moveToNext()) {
                String lookupKey = cursor.getString(
                        cursor.getColumnIndex(ContactsContract.CommonDataKinds.Event.LOOKUP_KEY));
                lookupKeysWithBirthdaysToday.add(lookupKey);
            }
        } catch (SQLException e) {
            Log.e(TAG, "Failed to query birthdays: " + e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return lookupKeysWithBirthdaysToday;
    }
}