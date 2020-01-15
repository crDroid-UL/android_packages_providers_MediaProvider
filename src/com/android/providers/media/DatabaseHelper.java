/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.providers.media;

import static com.android.providers.media.util.DatabaseUtils.bindList;
import static com.android.providers.media.util.Logging.LOGV;
import static com.android.providers.media.util.Logging.TAG;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.mtp.MtpConstants;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Downloads;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.MediaColumns;
import android.provider.MediaStore.Video;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.providers.media.util.BackgroundThread;
import com.android.providers.media.util.DatabaseUtils;
import com.android.providers.media.util.FileUtils;
import com.android.providers.media.util.Logging;

import java.io.File;
import java.io.FilenameFilter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;

/**
 * Wrapper class for a specific database (associated with one particular
 * external card, or with internal storage).  Can open the actual database
 * on demand, create and upgrade the schema, etc.
 */
public class DatabaseHelper extends SQLiteOpenHelper implements AutoCloseable {
    static final String INTERNAL_DATABASE_NAME = "internal.db";
    static final String EXTERNAL_DATABASE_NAME = "external.db";

    /**
     * Raw SQL clause that can be used to obtain the current generation, which
     * is designed to be populated into {@link MediaColumns#GENERATION_ADDED} or
     * {@link MediaColumns#GENERATION_MODIFIED}.
     */
    public static final String CURRENT_GENERATION_CLAUSE = "SELECT generation FROM local_metadata";

    final Context mContext;
    final String mName;
    final int mVersion;
    final String mVolumeName;
    final boolean mInternal;  // True if this is the internal database
    final boolean mEarlyUpgrade;
    final boolean mLegacyProvider;
    final Class<? extends Annotation> mColumnAnnotation;
    final OnSchemaChangeListener mListener;
    final Set<String> mFilterVolumeNames = new ArraySet<>();
    long mScanStartTime;
    long mScanStopTime;

    public interface OnSchemaChangeListener {
        public void onSchemaChange(@NonNull String volumeName, int versionFrom, int versionTo,
                long itemCount, long durationMillis);
    }

    public DatabaseHelper(Context context, String name,
            boolean internal, boolean earlyUpgrade, boolean legacyProvider,
            Class<? extends Annotation> columnAnnotation, OnSchemaChangeListener listener) {
        this(context, name, getDatabaseVersion(context),
                internal, earlyUpgrade, legacyProvider, columnAnnotation, listener);
    }

    public DatabaseHelper(Context context, String name, int version,
            boolean internal, boolean earlyUpgrade, boolean legacyProvider,
            Class<? extends Annotation> columnAnnotation, OnSchemaChangeListener listener) {
        super(context, name, null, version);
        mContext = context;
        mName = name;
        mVersion = version;
        mVolumeName = internal ? MediaStore.VOLUME_INTERNAL : MediaStore.VOLUME_EXTERNAL;
        mInternal = internal;
        mEarlyUpgrade = earlyUpgrade;
        mLegacyProvider = legacyProvider;
        mColumnAnnotation = columnAnnotation;
        mListener = listener;

        // Configure default filters until we hear differently
        if (mInternal) {
            mFilterVolumeNames.add(MediaStore.VOLUME_INTERNAL);
        } else {
            mFilterVolumeNames.add(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        }

        setWriteAheadLoggingEnabled(true);
    }

    /**
     * Configure the set of {@link MediaColumns#VOLUME_NAME} that we should use
     * for filtering query results.
     * <p>
     * This is typically set to the list of storage volumes which are currently
     * mounted, so that we don't leak cached indexed metadata from volumes which
     * are currently ejected.
     */
    public void setFilterVolumeNames(@NonNull Set<String> filterVolumeNames) {
        synchronized (mFilterVolumeNames) {
            // Skip update if identical, to help avoid database churn
            if (mFilterVolumeNames.equals(filterVolumeNames)) {
                return;
            }

            mFilterVolumeNames.clear();
            mFilterVolumeNames.addAll(filterVolumeNames);
        }

        // Recreate all views to apply this filter
        final SQLiteDatabase db = getWritableDatabase();
        try {
            db.beginTransaction();
            createLatestViews(db, mInternal);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public SQLiteDatabase getReadableDatabase() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.wtf(TAG, "Database operations must not happen on main thread", new Throwable());
        }
        return super.getReadableDatabase();
    }

    @Override
    public SQLiteDatabase getWritableDatabase() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.wtf(TAG, "Database operations must not happen on main thread", new Throwable());
        }
        return super.getWritableDatabase();
    }

    @Override
    public void onCreate(final SQLiteDatabase db) {
        Log.v(TAG, "onCreate() for " + mName);
        updateDatabase(db, 0, mVersion);
    }

    @Override
    public void onUpgrade(final SQLiteDatabase db, final int oldV, final int newV) {
        Log.v(TAG, "onUpgrade() for " + mName + " from " + oldV + " to " + newV);
        updateDatabase(db, oldV, newV);
    }

    @Override
    public void onDowngrade(final SQLiteDatabase db, final int oldV, final int newV) {
        Log.v(TAG, "onDowngrade() for " + mName + " from " + oldV + " to " + newV);
        downgradeDatabase(db, oldV, newV);
    }

    @GuardedBy("mProjectionMapCache")
    private final ArrayMap<Class<?>[], ArrayMap<String, String>>
            mProjectionMapCache = new ArrayMap<>();

    /**
     * Return a projection map that represents the valid columns that can be
     * queried the given contract class. The mapping is built automatically
     * using the {@link android.provider.Column} annotation, and is designed to
     * ensure that we always support public API commitments.
     */
    public ArrayMap<String, String> getProjectionMap(Class<?>... clazzes) {
        synchronized (mProjectionMapCache) {
            ArrayMap<String, String> map = mProjectionMapCache.get(clazzes);
            if (map == null) {
                map = new ArrayMap<>();
                mProjectionMapCache.put(clazzes, map);
                try {
                    for (Class<?> clazz : clazzes) {
                        for (Field field : clazz.getFields()) {
                            if (Objects.equals(field.getName(), "_ID")
                                    || field.isAnnotationPresent(mColumnAnnotation)) {
                                final String column = (String) field.get(null);
                                map.put(column, column);
                            }
                        }
                    }
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }
            return map;
        }
    }

    /**
     * List of {@link Uri} that would have been sent directly via
     * {@link ContentResolver#notifyChange}, but are instead being collected
     * due to an ongoing transaction.
     */
    private final ThreadLocal<List<Uri>> mNotifyChanges = new ThreadLocal<>();

    public void beginTransaction() {
        getWritableDatabase().beginTransaction();
        getWritableDatabase().execSQL("UPDATE local_metadata SET generation=generation+1;");
        mNotifyChanges.set(new ArrayList<>());
    }

    public void setTransactionSuccessful() {
        getWritableDatabase().setTransactionSuccessful();
        final List<Uri> uris = mNotifyChanges.get();
        if (uris != null) {
            BackgroundThread.getExecutor().execute(() -> {
                notifyChangeInternal(uris);
            });
        }
        mNotifyChanges.remove();
    }

    public void endTransaction() {
        getWritableDatabase().endTransaction();
    }

    /**
     * Execute the given runnable inside a transaction. If the calling thread is
     * not already in an active transaction, this method will wrap the given
     * runnable inside a new transaction.
     */
    public long runWithTransaction(@NonNull LongSupplier s) {
        if (mNotifyChanges.get() != null) {
            // Already inside a transaction, so we can run directly
            return s.getAsLong();
        } else {
            // Not inside a transaction, so we need to make one
            beginTransaction();
            try {
                final long res = s.getAsLong();
                setTransactionSuccessful();
                return res;
            } finally {
                endTransaction();
            }
        }
    }

    /**
     * Notify that the given {@link Uri} has changed. This enqueues the
     * notification if currently inside a transaction, and they'll be
     * clustered and sent when the transaction completes.
     */
    public void notifyChange(Uri uri) {
        if (LOGV) Log.v(TAG, "Notifying " + uri);
        final List<Uri> uris = mNotifyChanges.get();
        if (uris != null) {
            uris.add(uri);
        } else {
            BackgroundThread.getExecutor().execute(() -> {
                notifySingleChangeInternal(uri);
            });
        }
    }

    private void notifySingleChangeInternal(Uri uri) {
        Trace.beginSection("notifySingleChange");
        try {
            mContext.getContentResolver().notifyChange(uri, null, 0);
        } finally {
            Trace.endSection();
        }
    }

    private void notifyChangeInternal(Iterable<Uri> uris) {
        Trace.beginSection("notifyChange");
        try {
            mContext.getContentResolver().notifyChange(uris, null, 0);
        } finally {
            Trace.endSection();
        }
    }

    /**
     * This method cleans up any files created by android.media.MiniThumbFile, removed after P.
     * It's triggered during database update only, in order to run only once.
     */
    private static void deleteLegacyThumbnailData() {
        File directory = new File(Environment.getExternalStorageDirectory(), "/DCIM/.thumbnails");

        final FilenameFilter filter = (dir, filename) -> filename.startsWith(".thumbdata");
        final File[] files = directory.listFiles(filter);
        for (File f : (files != null) ? files : new File[0]) {
            if (!f.delete()) {
                Log.e(TAG, "Failed to delete legacy thumbnail data " + f.getAbsolutePath());
            }
        }
    }

    @Deprecated
    public static int getDatabaseVersion(Context context) {
        // We now use static versions defined internally instead of the
        // versionCode from the manifest
        return VERSION_LATEST;
    }

    @VisibleForTesting
    static void makePristineSchema(SQLiteDatabase db) {
        // drop all triggers
        Cursor c = db.query("sqlite_master", new String[] {"name"}, "type is 'trigger'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP TRIGGER IF EXISTS " + c.getString(0));
        }
        c.close();

        // drop all views
        c = db.query("sqlite_master", new String[] {"name"}, "type is 'view'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP VIEW IF EXISTS " + c.getString(0));
        }
        c.close();

        // drop all indexes
        c = db.query("sqlite_master", new String[] {"name"}, "type is 'index'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP INDEX IF EXISTS " + c.getString(0));
        }
        c.close();

        // drop all tables
        c = db.query("sqlite_master", new String[] {"name"}, "type is 'table'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP TABLE IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    private void createLatestSchema(SQLiteDatabase db) {
        // We're about to start all ID numbering from scratch, so revoke any
        // outstanding permission grants to ensure we don't leak data
        try {
            final PackageInfo pkg = mContext.getPackageManager().getPackageInfo(
                    mContext.getPackageName(), PackageManager.GET_PROVIDERS);
            if (pkg != null && pkg.providers != null) {
                for (ProviderInfo provider : pkg.providers) {
                    mContext.revokeUriPermission(Uri.parse("content://" + provider.authority),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to revoke permissions", e);
        }

        makePristineSchema(db);

        db.execSQL("CREATE TABLE local_metadata (generation INTEGER DEFAULT 0)");
        db.execSQL("INSERT INTO local_metadata VALUES (0)");

        db.execSQL("CREATE TABLE android_metadata (locale TEXT)");
        db.execSQL("CREATE TABLE thumbnails (_id INTEGER PRIMARY KEY,_data TEXT,image_id INTEGER,"
                + "kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE album_art (album_id INTEGER PRIMARY KEY,_data TEXT)");
        db.execSQL("CREATE TABLE videothumbnails (_id INTEGER PRIMARY KEY,_data TEXT,"
                + "video_id INTEGER,kind INTEGER,width INTEGER,height INTEGER)");
        db.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "_data TEXT UNIQUE COLLATE NOCASE,_size INTEGER,format INTEGER,parent INTEGER,"
                + "date_added INTEGER,date_modified INTEGER,mime_type TEXT,title TEXT,"
                + "description TEXT,_display_name TEXT,picasa_id TEXT,orientation INTEGER,"
                + "latitude DOUBLE,longitude DOUBLE,datetaken INTEGER,mini_thumb_magic INTEGER,"
                + "bucket_id TEXT,bucket_display_name TEXT,isprivate INTEGER,title_key TEXT,"
                + "artist_id INTEGER,album_id INTEGER,composer TEXT,track INTEGER,"
                + "year INTEGER CHECK(year!=0),is_ringtone INTEGER,is_music INTEGER,"
                + "is_alarm INTEGER,is_notification INTEGER,is_podcast INTEGER,album_artist TEXT,"
                + "duration INTEGER,bookmark INTEGER,artist TEXT,album TEXT,resolution TEXT,"
                + "tags TEXT,category TEXT,language TEXT,mini_thumb_data TEXT,name TEXT,"
                + "media_type INTEGER,old_id INTEGER,is_drm INTEGER,"
                + "width INTEGER, height INTEGER, title_resource_uri TEXT,"
                + "owner_package_name TEXT DEFAULT NULL,"
                + "color_standard INTEGER, color_transfer INTEGER, color_range INTEGER,"
                + "_hash BLOB DEFAULT NULL, is_pending INTEGER DEFAULT 0,"
                + "is_download INTEGER DEFAULT 0, download_uri TEXT DEFAULT NULL,"
                + "referer_uri TEXT DEFAULT NULL, is_audiobook INTEGER DEFAULT 0,"
                + "date_expires INTEGER DEFAULT NULL,is_trashed INTEGER DEFAULT 0,"
                + "group_id INTEGER DEFAULT NULL,primary_directory TEXT DEFAULT NULL,"
                + "secondary_directory TEXT DEFAULT NULL,document_id TEXT DEFAULT NULL,"
                + "instance_id TEXT DEFAULT NULL,original_document_id TEXT DEFAULT NULL,"
                + "relative_path TEXT DEFAULT NULL,volume_name TEXT DEFAULT NULL,"
                + "artist_key TEXT DEFAULT NULL,album_key TEXT DEFAULT NULL,"
                + "genre TEXT DEFAULT NULL,genre_key TEXT DEFAULT NULL,genre_id INTEGER,"
                + "author TEXT DEFAULT NULL, bitrate INTEGER DEFAULT NULL,"
                + "capture_framerate REAL DEFAULT NULL, cd_track_number TEXT DEFAULT NULL,"
                + "compilation INTEGER DEFAULT NULL, disc_number TEXT DEFAULT NULL,"
                + "is_favorite INTEGER DEFAULT 0, num_tracks INTEGER DEFAULT NULL,"
                + "writer TEXT DEFAULT NULL, exposure_time TEXT DEFAULT NULL,"
                + "f_number TEXT DEFAULT NULL, iso INTEGER DEFAULT NULL,"
                + "scene_capture_type INTEGER DEFAULT NULL, generation_added INTEGER DEFAULT 0,"
                + "generation_modified INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE log (time DATETIME, message TEXT)");
        if (!mInternal) {
            db.execSQL("CREATE TABLE audio_playlists_map (_id INTEGER PRIMARY KEY,"
                    + "audio_id INTEGER NOT NULL,playlist_id INTEGER NOT NULL,"
                    + "play_order INTEGER NOT NULL)");
        }

        db.execSQL("CREATE INDEX image_id_index on thumbnails(image_id)");
        db.execSQL("CREATE INDEX video_id_index on videothumbnails(video_id)");
        db.execSQL("CREATE INDEX album_id_idx ON files(album_id)");
        db.execSQL("CREATE INDEX artist_id_idx ON files(artist_id)");
        db.execSQL("CREATE INDEX genre_id_idx ON files(genre_id)");
        db.execSQL("CREATE INDEX bucket_index on files(bucket_id,media_type,datetaken, _id)");
        db.execSQL("CREATE INDEX bucket_name on files(bucket_id,media_type,bucket_display_name)");
        db.execSQL("CREATE INDEX format_index ON files(format)");
        db.execSQL("CREATE INDEX media_type_index ON files(media_type)");
        db.execSQL("CREATE INDEX parent_index ON files(parent)");
        db.execSQL("CREATE INDEX path_index ON files(_data)");
        db.execSQL("CREATE INDEX sort_index ON files(datetaken ASC, _id ASC)");
        db.execSQL("CREATE INDEX title_idx ON files(title)");
        db.execSQL("CREATE INDEX titlekey_index ON files(title_key)");

        createLatestViews(db, mInternal);
        createLatestTriggers(db, mInternal);

        // Since this code is used by both the legacy and modern providers, we
        // only want to migrate when we're running as the modern provider
        if (!mLegacyProvider) {
            migrateFromLegacy(db);
        }
    }

    /**
     * Migrate important information from {@link MediaStore#AUTHORITY_LEGACY},
     * if present on this device. We only do this once during early database
     * creation, to help us preserve information like {@link MediaColumns#_ID}
     * and {@link MediaColumns#IS_FAVORITE}.
     */
    private void migrateFromLegacy(SQLiteDatabase db) {
        // TODO: focus this migration on secondary volumes once we have separate
        // databases for each volume; for now only migrate primary storage

        try (ContentProviderClient client = mContext.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY_LEGACY)) {
            if (client == null) {
                Log.d(TAG, "No legacy provider available for migration");
                return;
            }

            final Uri queryUri = MediaStore
                    .rewriteToLegacy(MediaStore.Files.getContentUri(mVolumeName));

            final Bundle extras = new Bundle();
            extras.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
            extras.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
            extras.putInt(MediaStore.QUERY_ARG_MATCH_FAVORITE, MediaStore.MATCH_INCLUDE);

            db.execSQL("SAVEPOINT before_migrate");
            Log.d(TAG, "Starting migration from legacy provider for " + mName);
            try (Cursor c = client.query(queryUri, sMigrateColumns.toArray(new String[0]),
                    extras, null)) {
                final ContentValues values = new ContentValues();
                while (c.moveToNext()) {
                    values.clear();
                    for (String column : sMigrateColumns) {
                        DatabaseUtils.copyFromCursorToContentValues(column, c, values);
                    }

                    if (db.insert("files", null, values) == -1) {
                        // We only have one shot to migrate data, so log and
                        // keep marching forward
                        Log.w(TAG, "Failed to insert " + values + "; continuing");
                    }
                }

                db.execSQL("RELEASE before_migrate");
                Log.d(TAG, "Finished migration from legacy provider for " + mName);
            } catch (Exception e) {
                // We have to guard ourselves against any weird behavior of the
                // legacy provider by trying to catch everything
                db.execSQL("ROLLBACK TO before_migrate");
                Log.w(TAG, "Failed migration from legacy provider: " + e);
            }
        }
    }

    /**
     * Set of columns that should be migrated from the legacy provider,
     * including core information to identify each media item, followed by
     * columns that can be edited by users. (We omit columns here that are
     * marked as "readOnly" in the {@link MediaStore} annotations, since those
     * will be regenerated by the first scan after upgrade.)
     */
    private static final ArraySet<String> sMigrateColumns = new ArraySet<>();

    {
        sMigrateColumns.add(MediaStore.MediaColumns._ID);
        sMigrateColumns.add(MediaStore.MediaColumns.DATA);
        sMigrateColumns.add(MediaStore.MediaColumns.VOLUME_NAME);
        sMigrateColumns.add(MediaStore.Files.FileColumns.MEDIA_TYPE);

        sMigrateColumns.add(MediaStore.MediaColumns.DATE_ADDED);
        sMigrateColumns.add(MediaStore.MediaColumns.DATE_EXPIRES);
        sMigrateColumns.add(MediaStore.MediaColumns.IS_PENDING);
        sMigrateColumns.add(MediaStore.MediaColumns.IS_TRASHED);
        sMigrateColumns.add(MediaStore.MediaColumns.IS_FAVORITE);
        sMigrateColumns.add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME);

        sMigrateColumns.add(MediaStore.Audio.AudioColumns.BOOKMARK);

        sMigrateColumns.add(MediaStore.Video.VideoColumns.TAGS);
        sMigrateColumns.add(MediaStore.Video.VideoColumns.CATEGORY);
        sMigrateColumns.add(MediaStore.Video.VideoColumns.BOOKMARK);

        sMigrateColumns.add(MediaStore.DownloadColumns.DOWNLOAD_URI);
        sMigrateColumns.add(MediaStore.DownloadColumns.REFERER_URI);
    }

    private static void makePristineViews(SQLiteDatabase db) {
        // drop all views
        Cursor c = db.query("sqlite_master", new String[] {"name"}, "type is 'view'",
                null, null, null, null);
        while (c.moveToNext()) {
            db.execSQL("DROP VIEW IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    private void createLatestViews(SQLiteDatabase db, boolean internal) {
        makePristineViews(db);

        if (mColumnAnnotation == null) {
            Log.w(TAG, "No column annotation provided; not creating views");
            return;
        }

        final String filterVolumeNames;
        synchronized (mFilterVolumeNames) {
            filterVolumeNames = bindList(mFilterVolumeNames.toArray());
        }

        if (!internal) {
            db.execSQL("CREATE VIEW audio_playlists AS SELECT _id,_data,name,date_added,"
                    + "date_modified,owner_package_name,_hash,is_pending,date_expires,is_trashed,"
                    + "volume_name FROM files WHERE media_type=4");
        }

        db.execSQL("CREATE VIEW searchhelpertitle AS SELECT * FROM audio ORDER BY title_key");
        db.execSQL("CREATE VIEW search AS SELECT _id,'artist' AS mime_type,artist,NULL AS album,"
                + "NULL AS title,artist AS text1,NULL AS text2,number_of_albums AS data1,"
                + "number_of_tracks AS data2,artist_key AS match,"
                + "'content://media/external/audio/artists/'||_id AS suggest_intent_data,"
                + "1 AS grouporder FROM artist_info WHERE (artist!='<unknown>')"
                + " UNION ALL SELECT _id,'album' AS mime_type,artist,album,"
                + "NULL AS title,album AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key AS match,"
                + "'content://media/external/audio/albums/'||_id AS suggest_intent_data,"
                + "2 AS grouporder FROM album_info"
                + " WHERE (album!='<unknown>')"
                + " UNION ALL SELECT searchhelpertitle._id AS _id,mime_type,artist,album,title,"
                + "title AS text1,artist AS text2,NULL AS data1,"
                + "NULL AS data2,artist_key||' '||album_key||' '||title_key AS match,"
                + "'content://media/external/audio/media/'||searchhelpertitle._id"
                + " AS suggest_intent_data,"
                + "3 AS grouporder FROM searchhelpertitle WHERE (title != '')");

        db.execSQL("CREATE VIEW audio AS SELECT "
                + String.join(",", getProjectionMap(Audio.Media.class).keySet())
                + " FROM files WHERE media_type=2");
        db.execSQL("CREATE VIEW video AS SELECT "
                + String.join(",", getProjectionMap(Video.Media.class).keySet())
                + " FROM files WHERE media_type=3");
        db.execSQL("CREATE VIEW images AS SELECT "
                + String.join(",", getProjectionMap(Images.Media.class).keySet())
                + " FROM files WHERE media_type=1");
        db.execSQL("CREATE VIEW downloads AS SELECT "
                + String.join(",", getProjectionMap(Downloads.class).keySet())
                + " FROM files WHERE is_download=1");

        db.execSQL("CREATE VIEW audio_artists AS SELECT "
                + "  artist_id AS " + Audio.Artists._ID
                + ", artist AS " + Audio.Artists.ARTIST
                + ", artist_key AS " + Audio.Artists.ARTIST_KEY
                + ", COUNT(DISTINCT album_id) AS " + Audio.Artists.NUMBER_OF_ALBUMS
                + ", COUNT(DISTINCT _id) AS " + Audio.Artists.NUMBER_OF_TRACKS
                + " FROM audio"
                + " WHERE volume_name IN " + filterVolumeNames
                + " GROUP BY artist_id");

        db.execSQL("CREATE VIEW audio_albums AS SELECT "
                + "  album_id AS " + Audio.Albums._ID
                + ", album_id AS " + Audio.Albums.ALBUM_ID
                + ", album AS " + Audio.Albums.ALBUM
                + ", album_key AS " + Audio.Albums.ALBUM_KEY
                + ", artist_id AS " + Audio.Albums.ARTIST_ID
                + ", artist AS " + Audio.Albums.ARTIST
                + ", artist_key AS " + Audio.Albums.ARTIST_KEY
                + ", COUNT(DISTINCT _id) AS " + Audio.Albums.NUMBER_OF_SONGS
                + ", COUNT(DISTINCT _id) AS " + Audio.Albums.NUMBER_OF_SONGS_FOR_ARTIST
                + ", MIN(year) AS " + Audio.Albums.FIRST_YEAR
                + ", MAX(year) AS " + Audio.Albums.LAST_YEAR
                + ", NULL AS " + Audio.Albums.ALBUM_ART
                + " FROM audio"
                + " WHERE volume_name IN " + filterVolumeNames
                + " GROUP BY album_id");

        db.execSQL("CREATE VIEW audio_genres AS SELECT "
                + "  genre_id AS " + Audio.Genres._ID
                + ", genre AS " + Audio.Genres.NAME
                + " FROM audio"
                + " WHERE volume_name IN " + filterVolumeNames
                + " GROUP BY genre_id");
    }

    private static void makePristineTriggers(SQLiteDatabase db) {
        // drop all triggers
        Cursor c = db.query("sqlite_master", new String[] {"name"}, "type is 'trigger'",
                null, null, null, null);
        while (c.moveToNext()) {
            if (c.getString(0).startsWith("sqlite_")) continue;
            db.execSQL("DROP TRIGGER IF EXISTS " + c.getString(0));
        }
        c.close();
    }

    private static void createLatestTriggers(SQLiteDatabase db, boolean internal) {
        makePristineTriggers(db);
    }

    private static void updateCollationKeys(SQLiteDatabase db) {
        // Delete albums and artists, then clear the modification time on songs, which
        // will cause the media scanner to rescan everything, rebuilding the artist and
        // album tables along the way, while preserving playlists.
        // We need this rescan because ICU also changed, and now generates different
        // collation keys
        db.execSQL("DELETE from albums");
        db.execSQL("DELETE from artists");
        db.execSQL("UPDATE files SET date_modified=0;");
    }

    private static void updateAddTitleResource(SQLiteDatabase db) {
        // Add the column used for title localization, and force a rescan of any
        // ringtones, alarms and notifications that may be using it.
        db.execSQL("ALTER TABLE files ADD COLUMN title_resource_uri TEXT");
        db.execSQL("UPDATE files SET date_modified=0"
                + " WHERE (is_alarm IS 1) OR (is_ringtone IS 1) OR (is_notification IS 1)");
    }

    private static void updateAddOwnerPackageName(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN owner_package_name TEXT DEFAULT NULL");

        // Derive new column value based on well-known paths
        try (Cursor c = db.query("files", new String[] { FileColumns._ID, FileColumns.DATA },
                FileColumns.DATA + " REGEXP '" + FileUtils.PATTERN_OWNED_PATH.pattern() + "'",
                null, null, null, null, null)) {
            Log.d(TAG, "Updating " + c.getCount() + " entries with well-known owners");

            final Matcher m = FileUtils.PATTERN_OWNED_PATH.matcher("");
            final ContentValues values = new ContentValues();

            while (c.moveToNext()) {
                final long id = c.getLong(0);
                final String data = c.getString(1);
                m.reset(data);
                if (m.matches()) {
                    final String packageName = m.group(1);
                    values.clear();
                    values.put(FileColumns.OWNER_PACKAGE_NAME, packageName);
                    db.update("files", values, "_id=" + id, null);
                }
            }
        }
    }

    private static void updateAddColorSpaces(SQLiteDatabase db) {
        // Add the color aspects related column used for HDR detection etc.
        db.execSQL("ALTER TABLE files ADD COLUMN color_standard INTEGER;");
        db.execSQL("ALTER TABLE files ADD COLUMN color_transfer INTEGER;");
        db.execSQL("ALTER TABLE files ADD COLUMN color_range INTEGER;");
    }

    private static void updateAddHashAndPending(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN _hash BLOB DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN is_pending INTEGER DEFAULT 0;");
    }

    private static void updateAddDownloadInfo(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN is_download INTEGER DEFAULT 0;");
        db.execSQL("ALTER TABLE files ADD COLUMN download_uri TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN referer_uri TEXT DEFAULT NULL;");
    }

    private static void updateAddAudiobook(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN is_audiobook INTEGER DEFAULT 0;");
    }

    private static void updateClearLocation(SQLiteDatabase db, boolean internal) {
        db.execSQL("UPDATE files SET latitude=NULL, longitude=NULL;");
    }

    private static void updateSetIsDownload(SQLiteDatabase db, boolean internal) {
        db.execSQL("UPDATE files SET is_download=1 WHERE _data REGEXP '"
                + FileUtils.PATTERN_DOWNLOADS_FILE + "'");
    }

    private static void updateAddExpiresAndTrashed(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN date_expires INTEGER DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN is_trashed INTEGER DEFAULT 0;");
    }

    private static void updateAddGroupId(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN group_id INTEGER DEFAULT NULL;");
    }

    private static void updateAddDirectories(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN primary_directory TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN secondary_directory TEXT DEFAULT NULL;");
    }

    private static void updateAddXmp(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN document_id TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN instance_id TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN original_document_id TEXT DEFAULT NULL;");
    }

    private static void updateAddPath(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN relative_path TEXT DEFAULT NULL;");
    }

    private static void updateAddVolumeName(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN volume_name TEXT DEFAULT NULL;");
    }

    private static void updateDirsMimeType(SQLiteDatabase db, boolean internal) {
        db.execSQL("UPDATE files SET mime_type=NULL WHERE format="
                + MtpConstants.FORMAT_ASSOCIATION);
    }

    private static void updateRelativePath(SQLiteDatabase db, boolean internal) {
        db.execSQL("UPDATE files"
                + " SET " + MediaColumns.RELATIVE_PATH + "=" + MediaColumns.RELATIVE_PATH + "||'/'"
                + " WHERE " + MediaColumns.RELATIVE_PATH + " IS NOT NULL"
                + " AND " + MediaColumns.RELATIVE_PATH + " NOT LIKE '%/';");
    }

    private static void updateClearDirectories(SQLiteDatabase db, boolean internal) {
        db.execSQL("UPDATE files SET primary_directory=NULL, secondary_directory=NULL;");
    }

    private static void updateRestructureAudio(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN artist_key TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN album_key TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN genre TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN genre_key TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN genre_id INTEGER;");

        db.execSQL("DROP TABLE IF EXISTS artists;");
        db.execSQL("DROP TABLE IF EXISTS albums;");
        db.execSQL("DROP TABLE IF EXISTS audio_genres;");
        db.execSQL("DROP TABLE IF EXISTS audio_genres_map;");

        db.execSQL("CREATE INDEX genre_id_idx ON files(genre_id)");

        db.execSQL("DROP INDEX IF EXISTS album_idx");
        db.execSQL("DROP INDEX IF EXISTS albumkey_index");
        db.execSQL("DROP INDEX IF EXISTS artist_idx");
        db.execSQL("DROP INDEX IF EXISTS artistkey_index");

        // Since we're radically changing how the schema is defined, the
        // simplest path forward is to rescan all audio files
        db.execSQL("UPDATE files SET date_modified=0 WHERE media_type=2;");
    }

    private static void updateAddMetadata(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN author TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN bitrate INTEGER DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN capture_framerate REAL DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN cd_track_number TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN compilation INTEGER DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN disc_number TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN is_favorite INTEGER DEFAULT 0;");
        db.execSQL("ALTER TABLE files ADD COLUMN num_tracks INTEGER DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN writer TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN exposure_time TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN f_number TEXT DEFAULT NULL;");
        db.execSQL("ALTER TABLE files ADD COLUMN iso INTEGER DEFAULT NULL;");
    }

    private static void updateAddSceneCaptureType(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN scene_capture_type INTEGER DEFAULT NULL;");
    }

    private static void updateMigrateLogs(SQLiteDatabase db, boolean internal) {
        // Migrate any existing logs to new system
        try (Cursor c = db.query("log", new String[] { "time", "message" },
                null, null, null, null, null)) {
            while (c.moveToNext()) {
                final String time = c.getString(0);
                final String message = c.getString(1);
                Logging.logPersistent("Historical log " + time + " " + message);
            }
        }
        db.execSQL("DELETE FROM log;");
    }

    private static void updateAddLocalMetadata(SQLiteDatabase db, boolean internal) {
        db.execSQL("CREATE TABLE local_metadata (generation INTEGER DEFAULT 0)");
        db.execSQL("INSERT INTO local_metadata VALUES (0)");
    }

    private static void updateAddGeneration(SQLiteDatabase db, boolean internal) {
        db.execSQL("ALTER TABLE files ADD COLUMN generation_added INTEGER DEFAULT 0;");
        db.execSQL("ALTER TABLE files ADD COLUMN generation_modified INTEGER DEFAULT 0;");
    }

    private static void recomputeDataValues(SQLiteDatabase db, boolean internal) {
        try (Cursor c = db.query("files", new String[] { FileColumns._ID, FileColumns.DATA },
                null, null, null, null, null, null)) {
            Log.d(TAG, "Recomputing " + c.getCount() + " data values");

            final ContentValues values = new ContentValues();
            while (c.moveToNext()) {
                values.clear();
                final long id = c.getLong(0);
                final String data = c.getString(1);
                values.put(FileColumns.DATA, data);
                FileUtils.computeDataValues(values);
                values.remove(FileColumns.DATA);
                if (!values.isEmpty()) {
                    db.update("files", values, "_id=" + id, null);
                }
            }
        }
    }

    static final int VERSION_J = 509;
    static final int VERSION_K = 700;
    static final int VERSION_L = 700;
    static final int VERSION_M = 800;
    static final int VERSION_N = 800;
    static final int VERSION_O = 800;
    static final int VERSION_P = 900;
    static final int VERSION_Q = 1023;
    static final int VERSION_R = 1109;
    static final int VERSION_LATEST = VERSION_R;

    /**
     * This method takes care of updating all the tables in the database to the
     * current version, creating them if necessary.
     * This method can only update databases at schema 700 or higher, which was
     * used by the KitKat release. Older database will be cleared and recreated.
     * @param db Database
     */
    private void updateDatabase(SQLiteDatabase db, int fromVersion, int toVersion) {
        final long startTime = SystemClock.elapsedRealtime();
        final boolean internal = mInternal;

        if (fromVersion < 700) {
            // Anything older than KK is recreated from scratch
            createLatestSchema(db);
        } else {
            boolean recomputeDataValues = false;
            if (fromVersion < 800) {
                updateCollationKeys(db);
            }
            if (fromVersion < 900) {
                updateAddTitleResource(db);
            }
            if (fromVersion < 1000) {
                updateAddOwnerPackageName(db, internal);
            }
            if (fromVersion < 1003) {
                updateAddColorSpaces(db);
            }
            if (fromVersion < 1004) {
                updateAddHashAndPending(db, internal);
            }
            if (fromVersion < 1005) {
                updateAddDownloadInfo(db, internal);
            }
            if (fromVersion < 1006) {
                updateAddAudiobook(db, internal);
            }
            if (fromVersion < 1007) {
                updateClearLocation(db, internal);
            }
            if (fromVersion < 1008) {
                updateSetIsDownload(db, internal);
            }
            if (fromVersion < 1009) {
                // This database version added "secondary_bucket_id", but that
                // column name was refactored in version 1013 below, so this
                // update step is no longer needed.
            }
            if (fromVersion < 1010) {
                updateAddExpiresAndTrashed(db, internal);
            }
            if (fromVersion < 1012) {
                recomputeDataValues = true;
            }
            if (fromVersion < 1013) {
                updateAddGroupId(db, internal);
                updateAddDirectories(db, internal);
                recomputeDataValues = true;
            }
            if (fromVersion < 1014) {
                updateAddXmp(db, internal);
            }
            if (fromVersion < 1015) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1016) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1017) {
                updateSetIsDownload(db, internal);
                recomputeDataValues = true;
            }
            if (fromVersion < 1018) {
                updateAddPath(db, internal);
                recomputeDataValues = true;
            }
            if (fromVersion < 1019) {
                // Only trigger during "external", so that it runs only once.
                if (!internal) {
                    deleteLegacyThumbnailData();
                }
            }
            if (fromVersion < 1020) {
                updateAddVolumeName(db, internal);
                recomputeDataValues = true;
            }
            if (fromVersion < 1021) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1022) {
                updateDirsMimeType(db, internal);
            }
            if (fromVersion < 1023) {
                updateRelativePath(db, internal);
            }
            if (fromVersion < 1100) {
                // Empty version bump to ensure triggers are recreated
            }
            if (fromVersion < 1101) {
                updateClearDirectories(db, internal);
            }
            if (fromVersion < 1102) {
                updateRestructureAudio(db, internal);
            }
            if (fromVersion < 1103) {
                updateAddMetadata(db, internal);
            }
            if (fromVersion < 1104) {
                // Empty version bump to ensure views are recreated
            }
            if (fromVersion < 1105) {
                recomputeDataValues = true;
            }
            if (fromVersion < 1106) {
                updateMigrateLogs(db, internal);
            }
            if (fromVersion < 1107) {
                updateAddSceneCaptureType(db, internal);
            }
            if (fromVersion < 1108) {
                updateAddLocalMetadata(db, internal);
            }
            if (fromVersion < 1109) {
                updateAddGeneration(db, internal);
            }

            if (recomputeDataValues) {
                recomputeDataValues(db, internal);
            }
        }

        // Always recreate latest views and triggers during upgrade; they're
        // cheap and it's an easy way to ensure they're defined consistently
        createLatestViews(db, internal);
        createLatestTriggers(db, internal);

        getOrCreateUuid(db);

        final long elapsedMillis = (SystemClock.elapsedRealtime() - startTime);
        if (mListener != null) {
            mListener.onSchemaChange(mVolumeName, fromVersion, toVersion,
                    getItemCount(db), elapsedMillis);
        }
    }

    private void downgradeDatabase(SQLiteDatabase db, int fromVersion, int toVersion) {
        final long startTime = SystemClock.elapsedRealtime();

        // The best we can do is wipe and start over
        createLatestSchema(db);

        final long elapsedMillis = (SystemClock.elapsedRealtime() - startTime);
        if (mListener != null) {
            mListener.onSchemaChange(mVolumeName, fromVersion, toVersion,
                    getItemCount(db), elapsedMillis);
        }
    }

    private static final String XATTR_UUID = "user.uuid";

    /**
     * Return a UUID for the given database. If the database is deleted or
     * otherwise corrupted, then a new UUID will automatically be generated.
     */
    public static @NonNull String getOrCreateUuid(@NonNull SQLiteDatabase db) {
        try {
            return new String(Os.getxattr(db.getPath(), XATTR_UUID));
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENODATA) {
                // Doesn't exist yet, so generate and persist a UUID
                final String uuid = UUID.randomUUID().toString();
                try {
                    Os.setxattr(db.getPath(), XATTR_UUID, uuid.getBytes(), 0);
                } catch (ErrnoException e2) {
                    throw new RuntimeException(e);
                }
                return uuid;
            } else {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Return the current generation that will be populated into
     * {@link MediaColumns#GENERATION_ADDED} or
     * {@link MediaColumns#GENERATION_MODIFIED}.
     */
    public long getGeneration() {
        return android.database.DatabaseUtils.longForQuery(getReadableDatabase(),
                CURRENT_GENERATION_CLAUSE + ";", null);
    }

    /**
     * Return total number of items tracked inside this database. This includes
     * only real media items, and does not include directories.
     */
    public long getItemCount() {
        return getItemCount(getReadableDatabase());
    }

    /**
     * Return total number of items tracked inside this database. This includes
     * only real media items, and does not include directories.
     */
    private long getItemCount(SQLiteDatabase db) {
        return android.database.DatabaseUtils.longForQuery(db,
                "SELECT COUNT(_id) FROM files WHERE " + FileColumns.MIME_TYPE + " IS NOT NULL",
                null);
    }
}
