package mobi.maptrek.maps;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.oscim.android.cache.TileCache;
import org.oscim.core.BoundingBox;
import org.oscim.tiling.TileSource;
import org.oscim.tiling.source.mapfile.MapFileTileSource;
import org.oscim.tiling.source.sqlite.SQLiteMapInfo;
import org.oscim.tiling.source.sqlite.SQLiteTileSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

import mobi.maptrek.BasePaymentActivity;
import mobi.maptrek.R;
import mobi.maptrek.maps.online.OnlineTileSource;
import mobi.maptrek.maps.online.TileSourceFactory;
import mobi.maptrek.util.FileList;
import mobi.maptrek.util.MapFilenameFilter;
import mobi.maptrek.util.NativeMapFilenameFilter;

public class MapIndex implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(MapIndex.class);

    private static final long serialVersionUID = 1L;
    private static final BoundingBox WORLD_BOUNDING_BOX = new BoundingBox(-85.0511d, -180d, 85.0511d, 180d);

    public enum ACTION {NONE, DOWNLOAD, CANCEL, REMOVE}

    private final Context mContext;
    private final DownloadManager mDownloadManager;
    private File mRootDir;
    private HashSet<MapFile> mMaps;
    private MapFile[][] mNativeMaps = new MapFile[128][128];
    private boolean mHasDownloadSizes;
    private int mMapsLimit = BasePaymentActivity.MAPS_LIMIT;
    private int mLoadedMaps = 0;

    private final Set<WeakReference<MapStateListener>> mMapStateListeners = new HashSet<>();

    public MapIndex(@NonNull Context context, @Nullable File root) {
        mContext = context;
        mDownloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        mRootDir = root;
        mMaps = new HashSet<>();
        if (mRootDir != null) {
            logger.debug("MapIndex({})", mRootDir.getAbsolutePath());
            List<File> files = FileList.getFileListing(mRootDir, new MapFilenameFilter());
            for (File file : files) {
                loadMap(file);
            }
            files = FileList.getFileListing(mRootDir, new NativeMapFilenameFilter());
            for (File file : files) {
                loadNativeMap(file);
            }
        }
    }

    private void loadMap(@NonNull File file) {
        String fileName = file.getName();
        logger.debug("load({})", fileName);
        byte[] buffer = new byte[13];
        try {
            FileInputStream is = new FileInputStream(file);
            if (is.read(buffer) != buffer.length) {
                throw new IOException("Unknown map file format");
            }
            is.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        MapFile mapFile = new MapFile();
        if (Arrays.equals(SQLiteTileSource.MAGIC, buffer)) {
            SQLiteTileSource tileSource = new SQLiteTileSource();
            if (tileSource.setMapFile(file.getAbsolutePath())) {
                TileSource.OpenResult result = tileSource.open();
                if (result.isSuccess()) {
                    SQLiteMapInfo info = tileSource.getMapInfo();
                    mapFile.name = info.name;
                    mapFile.boundingBox = info.boundingBox;
                    mapFile.tileSource = tileSource;
                    mapFile.fileName = file.getAbsolutePath();
                    tileSource.close();
                }
            }
        }

        if (mapFile.tileSource == null)
            return;

        logger.debug("  added {}", mapFile.boundingBox);
        mMaps.add(mapFile);
    }

    private void loadNativeMap(@NonNull File file) {
        String fileName = file.getName();
        logger.debug("load({})", fileName);
        if (!file.canRead())
            return;
        String[] parts = fileName.split("[\\-\\.]");
        try {
            if (parts.length < 3 || parts.length > 4)
                throw new NumberFormatException("unexpected name");
            int x = Integer.valueOf(parts[0]);
            int y = Integer.valueOf(parts[1]);
            if (x > 127 || y > 127)
                throw new NumberFormatException("out of range");
            MapFile mapFile = getNativeMap(x, y);
            if (fileName.endsWith(".map")) {
                if (mapFile.created > 0L) {
                    logger.debug("  already loaded");
                    return;
                }
                mapFile.fileName = file.getAbsolutePath();
                if (setNativeMapTileSource(mapFile))
                    logger.debug("  indexed");
                else
                    logger.debug("  skipped");
            } else {
                Scanner scanner = new Scanner(file);
                String enqueue = scanner.useDelimiter("\\A").next();
                scanner.close();
                mapFile.downloading = Long.valueOf(enqueue);
                int status = checkDownloadStatus(mapFile.downloading);
                if (status == DownloadManager.STATUS_PAUSED
                        || status == DownloadManager.STATUS_PENDING
                        || status == DownloadManager.STATUS_RUNNING) {
                    logger.debug("  downloading: {}", mapFile.downloading);
                } else {
                    mapFile.downloading = 0L;
                    if (file.delete())
                        logger.debug("  cleared");
                }
            }
        } catch (NumberFormatException | FileNotFoundException e) {
            logger.warn("  skipped: {}", e.getMessage());
        }
    }

    private int checkDownloadStatus(long enqueue) {
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(enqueue);
        Cursor c = mDownloadManager.query(query);
        int status = 0;
        if (c.moveToFirst())
            status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
        c.close();
        return status;
    }

    public void initializeOnlineMapProviders() {
        PackageManager packageManager = mContext.getPackageManager();

        Intent initializationIntent = new Intent("mobi.maptrek.maps.online.provider.action.INITIALIZE");
        // enumerate online map providers
        List<ResolveInfo> providers = packageManager.queryBroadcastReceivers(initializationIntent, 0);
        for (ResolveInfo provider : providers) {
            // send initialization broadcast, we send it directly instead of sending
            // one broadcast for all plugins to wake up stopped plugins:
            // http://developer.android.com/about/versions/android-3.1.html#launchcontrols
            Intent intent = new Intent();
            intent.setClassName(provider.activityInfo.packageName, provider.activityInfo.name);
            intent.setAction(initializationIntent.getAction());
            mContext.sendBroadcast(intent);

            List<OnlineTileSource> tileSources = TileSourceFactory.fromPlugin(mContext, packageManager, provider);
            for (OnlineTileSource tileSource : tileSources) {
                MapFile mapFile = new MapFile(tileSource.getName());
                mapFile.tileSource = tileSource;
                mapFile.boundingBox = WORLD_BOUNDING_BOX;
                //TODO Implement tile cache expiration
                //tileProvider.tileExpiration = onlineMapTileExpiration;
                mMaps.add(mapFile);
            }
        }
    }

    @Nullable
    public MapFile getMap(@Nullable String filename) {
        if (filename == null)
            return null;
        for (MapFile map : mMaps) {
            if (filename.equals(map.fileName))
                return map;
        }
        return null;
    }

    @NonNull
    public Collection<MapFile> getMaps() {
        return mMaps;
    }

    @SuppressWarnings("unused")
    public void removeMap(MapFile map) {
        mMaps.remove(map);
        map.tileSource.close();
    }

    /**
     * Returns native map for a specified square.
     */
    @SuppressLint("DefaultLocale")
    @NonNull
    public MapFile getNativeMap(int x, int y) {
        if (mNativeMaps[x][y] == null) {
            mNativeMaps[x][y] = new MapFile("7-" + x + "-" + y);
            mNativeMaps[x][y].fileName = mRootDir.getAbsolutePath() + File.separator + getLocalPath(x, y);
        }
        return mNativeMaps[x][y];
    }

    private void removeNativeMap(int x, int y) {
        if (mNativeMaps[x][y] == null)
            return;
        File file = new File(mNativeMaps[x][y].fileName);
        if (file.exists() && file.delete()) {
            mNativeMaps[x][y].downloaded = false;
        }
    }

    public void clear() {
        for (MapFile map : mMaps) {
            map.tileSource.close();
            if (map.tileSource.tileCache != null && map.tileSource.tileCache instanceof TileCache)
                ((TileCache) map.tileSource.tileCache).dispose();
        }
        mMaps.clear();
    }

    public void setLimit(int limit) {
        logger.debug("setLimit({})", limit);
        mMapsLimit = limit;
        if (mRootDir != null) {
            List<File> files = FileList.getFileListing(mRootDir, new NativeMapFilenameFilter());
            for (File file : files) {
                loadNativeMap(file);
            }
        }
        for (WeakReference<MapStateListener> weakRef : mMapStateListeners) {
            MapStateListener mapStateListener = weakRef.get();
            if (mapStateListener != null) {
                mapStateListener.onStatsChanged(getMapStats());
            }
        }
    }

    public void setNativeMapStatus(int x, int y, short date, long size) {
        if (mNativeMaps[x][y] == null)
            getNativeMap(x, y);
        mNativeMaps[x][y].downloadCreated = date;
        mNativeMaps[x][y].downloadSize = size;
    }

    public void selectNativeMap(int x, int y, ACTION action) {
        IndexStats stats = getMapStats();
        MapFile mapFile = getNativeMap(x, y);
        if (mapFile.action == action) {
            mapFile.action = ACTION.NONE;
            if (action == ACTION.DOWNLOAD) {
                stats.download--;
                if (mHasDownloadSizes)
                    stats.downloadSize -= mapFile.downloadSize;
                if (stats.remaining >= 0)
                    stats.remaining++;
            }
            if (action == ACTION.REMOVE) {
                stats.remove--;
                if (stats.remaining > 0)
                    stats.remaining--;
            }
        }
        else if (action != ACTION.DOWNLOAD || stats.remaining != 0) {
            mapFile.action = action;
            if (action == ACTION.DOWNLOAD) {
                stats.download++;
                if (mHasDownloadSizes)
                    stats.downloadSize += mapFile.downloadSize;
                if (stats.remaining > 0)
                    stats.remaining--;
            }
            if (action == ACTION.REMOVE) {
                stats.remove++;
                if (stats.remaining >= 0)
                    stats.remaining++;
            }
        }
        for (WeakReference<MapStateListener> weakRef : mMapStateListeners) {
            MapStateListener mapStateListener = weakRef.get();
            if (mapStateListener != null) {
                mapStateListener.onMapSelected(x, y, mapFile.action, stats);
            }
        }
    }

    public void clearSelections() {
        for (int x = 0; x < 128; x++)
            for (int y = 0; y < 128; y++)
                if (mNativeMaps[x][y] != null)
                    mNativeMaps[x][y].action = ACTION.NONE;
    }

    public boolean manageNativeMaps() {
        boolean removed = false;
        for (int x = 0; x < 128; x++)
            for (int y = 0; y < 128; y++) {
                MapFile mapFile = getNativeMap(x, y);
                if (mapFile.action == MapIndex.ACTION.NONE)
                    continue;
                if (mapFile.action == MapIndex.ACTION.REMOVE) {
                    removeNativeMap(x, y);
                    mapFile.action = MapIndex.ACTION.NONE;
                    removed = true;
                    continue;
                }
                Uri uri = MapIndex.getDownloadUri(x, y);
                DownloadManager.Request request = new DownloadManager.Request(uri);
                request.setTitle(mContext.getString(R.string.mapTitle, x, y));
                request.setDescription(mContext.getString(R.string.app_name));
                String mapPath = mapFile.fileName + ".part";
                File file = new File(mapPath);
                if (file.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
                request.setDestinationInExternalFilesDir(mContext, "maps", MapIndex.getLocalPath(x, y) + ".part");
                request.setVisibleInDownloadsUi(false);
                setDownloading(mapFile, mDownloadManager.enqueue(request));
                mapFile.action = MapIndex.ACTION.NONE;
            }
        return removed;
    }

    public void setNativeMapTileSource(int key) {
        int x = key >>> 7;
        int y = key & 0x7f;
        setNativeMapTileSource(mNativeMaps[x][y]);
        mNativeMaps[x][y].downloading = 0L;
        selectNativeMap(x, y, MapIndex.ACTION.NONE);
    }

    private boolean setNativeMapTileSource(MapFile mapFile) {
        //TODO Check if tile source exists and close it
        if (mapFile.created > 0L) {
            mLoadedMaps--;
        }
        if (mLoadedMaps >= mMapsLimit)
            return false;
        MapFileTileSource tileSource = new MapFileTileSource();
        if (tileSource.setMapFile(mapFile.fileName)) {
            TileSource.OpenResult openResult = tileSource.open();
            if (openResult.isSuccess()) {
                mapFile.created = tileSource.getMapInfo().mapDate;
                mapFile.downloaded = true;
                mapFile.tileSource = tileSource;
                mLoadedMaps++;
            } else {
                logger.warn("Failed to open file: {}", openResult.getErrorMessage());
                mapFile.downloaded = false;
            }
            tileSource.close();
        }
        return mapFile.downloaded;
    }

    private void setDownloading(MapFile map, long enqueue) {
        map.downloading = enqueue;
        File enqueueFile = new File(map.fileName + ".enqueue");
        try {
            if (!enqueueFile.getParentFile().exists())
                //noinspection ResultOfMethodCallIgnored
                enqueueFile.getParentFile().mkdirs();
            FileWriter writer = new FileWriter(enqueueFile, false);
            writer.write(String.valueOf(enqueue));
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void cancelDownload(int x, int y) {
        MapFile map = getNativeMap(x, y);
        mDownloadManager.remove(map.downloading);
        map.downloading = 0L;
        File enqueueFile = new File(map.fileName + ".enqueue");
        //noinspection ResultOfMethodCallIgnored
        enqueueFile.delete();
        selectNativeMap(x, y, MapIndex.ACTION.NONE);
    }

    public boolean isDownloading(int x, int y) {
        return mNativeMaps[x][y] != null && mNativeMaps[x][y].downloading != 0L;
    }

    public static int processDownloadedMap(String filePath) {
        File srcFile = new File(filePath);
        File mapFile = new File(filePath.replace(".part", ""));
        String fileName = mapFile.getName();
        String[] parts = fileName.split("[\\-\\.]");
        try {
            if (parts.length != 3)
                throw new NumberFormatException("unexpected name");
            int x = Integer.valueOf(parts[0]);
            int y = Integer.valueOf(parts[1]);
            if (x > 127 || y > 127)
                throw new NumberFormatException("out of range");
            if ((!mapFile.exists() || mapFile.delete()) && srcFile.renameTo(mapFile))
                return getNativeKey(x, y);
            File enqueueFile = new File(mapFile.getAbsolutePath() + ".enqueue");
            //noinspection ResultOfMethodCallIgnored
            enqueueFile.delete();
        } catch (NumberFormatException e) {
            logger.error(e.getMessage());
        }
        return 0;
    }

    public boolean hasDownloadSizes() {
        return mHasDownloadSizes;
    }

    public void setHasDownloadSizes(boolean hasSizes) {
        mHasDownloadSizes = hasSizes;
        if (hasSizes) {
            for (int x = 0; x < 128; x++)
                for (int y = 0; y < 128; y++) {
                    MapFile mapFile = getNativeMap(x, y);
                    if (mapFile.action == MapIndex.ACTION.DOWNLOAD) {
                        if (mapFile.downloadSize == 0L)
                            selectNativeMap(x, y, MapIndex.ACTION.NONE);
                    }
                }
            for (WeakReference<MapStateListener> weakRef : mMapStateListeners) {
                MapStateListener mapStateListener = weakRef.get();
                if (mapStateListener != null) {
                    mapStateListener.onHasDownloadSizes();
                }
            }
        }
    }

    public IndexStats getMapStats() {
        IndexStats stats = new MapIndex.IndexStats();
        for (int x = 0; x < 128; x++)
            for (int y = 0; y < 128; y++) {
                MapFile mapFile = getNativeMap(x, y);
                if (mapFile.action == MapIndex.ACTION.DOWNLOAD) {
                    stats.download++;
                    if (mHasDownloadSizes)
                        stats.downloadSize += mapFile.downloadSize;
                }
                if (mapFile.action == MapIndex.ACTION.REMOVE)
                    stats.remove++;
                if (mapFile.downloading != 0L)
                    stats.downloading++;
            }
        stats.loaded = mLoadedMaps;
        if (mMapsLimit != Integer.MAX_VALUE)
            stats.remaining = mMapsLimit - stats.loaded - stats.download - stats.downloading + stats.remove;
        else
            stats.remaining = -1;

        return stats;
    }

    public void addMapStateListener(MapStateListener listener) {
        mMapStateListeners.add(new WeakReference<>(listener));
    }

    public void removeMapStateListener(MapStateListener listener) {
        for (Iterator<WeakReference<MapStateListener>> iterator = mMapStateListeners.iterator();
             iterator.hasNext(); ) {
            WeakReference<MapStateListener> weakRef = iterator.next();
            if (weakRef.get() == listener) {
                iterator.remove();
            }
        }
    }

    public static int getNativeKey(int x, int y) {
        return (x << 7) + y;
    }

    @SuppressLint("DefaultLocale")
    public static Uri getIndexUri() {
        return new Uri.Builder()
                .scheme("http")
                .authority("maptrek.mobi")
                .appendPath("maps")
                .appendPath("index")
                .build();
    }

    @SuppressLint("DefaultLocale")
    private static Uri getDownloadUri(int x, int y) {
        return new Uri.Builder()
                .scheme("http")
                .authority("maptrek.mobi")
                .appendPath("maps")
                .appendPath(String.valueOf(x))
                .appendPath(String.format("%d-%d.map", x, y))
                .build();
    }

    @SuppressLint("DefaultLocale")
    private static String getLocalPath(int x, int y) {
        return String.format("native/%d/%d-%d.map", x, x, y);
    }

    public static class IndexStats {
        public int loaded = 0;
        public int download = 0;
        public int remove = 0;
        public int downloading = 0;
        public int remaining = 0;
        public long downloadSize = 0L;
    }
}
