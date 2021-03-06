package mobi.maptrek;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.LongSparseArray;

import org.greenrobot.eventbus.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Iterator;

import mobi.maptrek.data.MapObject;
import mobi.maptrek.util.LongSparseArrayIterator;
import mobi.maptrek.util.StringFormatter;

public class MapTrek extends Application {
    private static final Logger logger = LoggerFactory.getLogger(MapTrek.class);
    public static final String EXCEPTION_PATH = "exception.log";

    private static MapTrek mSelf;
    private File mExceptionLog;

    public static float density = 1f;
    public static float ydpi = 160f;

    public static boolean isMainActivityRunning = false;

    private static final LongSparseArray<MapObject> mapObjects = new LongSparseArray<>();

    // Configure global defaults
    static {
        org.oscim.map.Map.NEW_GESTURES = true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mSelf = this;
        File cacheDir = getExternalCacheDir();
        File exportDir = new File(cacheDir, "export");
        if (!exportDir.exists())
            //noinspection ResultOfMethodCallIgnored
            exportDir.mkdir();
        mExceptionLog = new File(exportDir, EXCEPTION_PATH);
        Thread.setDefaultUncaughtExceptionHandler(new DefaultExceptionHandler());
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        Configuration.initialize(PreferenceManager.getDefaultSharedPreferences(this));
        initializeSettings();
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        density = metrics.density;
        ydpi = metrics.ydpi;
        mapObjects.clear();
    }

    private void initializeSettings() {
        Resources resources = getResources();
        int unit = Configuration.getSpeedUnit();
        StringFormatter.speedFactor = Float.parseFloat(resources.getStringArray(R.array.speed_factors)[unit]);
        StringFormatter.speedAbbr = resources.getStringArray(R.array.speed_abbreviations)[unit];
        unit = Configuration.getDistanceUnit();
        StringFormatter.distanceFactor = Double.parseDouble(resources.getStringArray(R.array.distance_factors)[unit]);
        StringFormatter.distanceAbbr = resources.getStringArray(R.array.distance_abbreviations)[unit];
        StringFormatter.distanceShortFactor = Double.parseDouble(resources.getStringArray(R.array.distance_factors_short)[unit]);
        StringFormatter.distanceShortAbbr = resources.getStringArray(R.array.distance_abbreviations_short)[unit];
        unit = Configuration.getElevationUnit();
        StringFormatter.elevationFactor = Float.parseFloat(resources.getStringArray(R.array.elevation_factors)[unit]);
        StringFormatter.elevationAbbr = resources.getStringArray(R.array.elevation_abbreviations)[unit];
        unit = Configuration.getAngleUnit();
        StringFormatter.angleFactor = Double.parseDouble(resources.getStringArray(R.array.angle_factors)[unit]);
        StringFormatter.angleAbbr = resources.getStringArray(R.array.angle_abbreviations)[unit];
        boolean precision = Configuration.getUnitPrecision();
        StringFormatter.precisionFormat = precision ? "%.1f" : "%.0f";
        StringFormatter.coordinateFormat = Configuration.getCoordinatesFormat();
    }

    public static MapTrek getApplication() {
        return mSelf;
    }

    public boolean hasPreviousRunsExceptions() {
        long size = Configuration.getExceptionSize();
        if (mExceptionLog.exists() && mExceptionLog.length() > 0L) {
            if (size != mExceptionLog.length()) {
                Configuration.setExceptionSize(mExceptionLog.length());
                return true;
            }
        } else {
            if (size > 0L) {
                Configuration.setExceptionSize(0L);
            }
        }
        return false;
    }

    /****************************
     * Map objects management
     */

    public static long getNewUID() {
        return Configuration.getUID();
    }

    public static long addMapObject(MapObject mapObject) {
        mapObject._id = getNewUID();
        logger.debug("addMapObject({})", mapObject._id);
        synchronized (mapObjects) {
            mapObjects.put(mapObject._id, mapObject);
        }
        EventBus.getDefault().post(new MapObject.AddedEvent(mapObject));
        return mapObject._id;
    }

    public static boolean removeMapObject(long id) {
        synchronized (mapObjects) {
            logger.debug("removeMapObject({})", id);
            MapObject mapObject = mapObjects.get(id);
            mapObjects.delete(id);
            if (mapObject != null) {
                mapObject.setBitmap(null);
                EventBus.getDefault().post(new MapObject.RemovedEvent(mapObject));
            }
            return mapObject != null;
        }
    }

    @Nullable
    public static MapObject getMapObject(long id) {
        return mapObjects.get(id);
    }

    @NonNull
    public static Iterator<MapObject> getMapObjects() {
        //noinspection unchecked
        return LongSparseArrayIterator.iterate(mapObjects);
    }

    /****************************
     * Exception handling
     */

    public File getExceptionLog() {
        return mExceptionLog;
    }

    private class DefaultExceptionHandler implements Thread.UncaughtExceptionHandler {
        private Thread.UncaughtExceptionHandler defaultHandler;

        DefaultExceptionHandler() {
            defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        }

        @Override
        public void uncaughtException(final Thread thread, final Throwable ex) {
            try {
                StringBuilder msg = new StringBuilder();
                msg.append(DateFormat.format("dd.MM.yyyy hh:mm:ss", System.currentTimeMillis()));
                try {
                    PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                    if (info != null) {
                        msg.append("\nVersion : ")
                                .append(info.versionCode).append(" ").append(info.versionName);
                    }
                } catch (Throwable ignore) {
                }
                msg.append("\n")
                        .append("Thread : ")
                        .append(thread.toString())
                        .append("\nException :\n\n");

                if (mExceptionLog.getParentFile().canWrite()) {
                    BufferedWriter writer = new BufferedWriter(new FileWriter(mExceptionLog, false));
                    writer.write(msg.toString());
                    ex.printStackTrace(new PrintWriter(writer));
                    writer.write("\n\n");
                    writer.close();
                }
                defaultHandler.uncaughtException(thread, ex);
            } catch (Exception e) {
                // swallow all exceptions
                logger.error("Exception while handle other exception", e);
            }
        }
    }

}
