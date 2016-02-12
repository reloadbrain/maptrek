package mobi.maptrek;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.LoaderManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Loader;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.VectorDrawable;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.Snackbar;
import android.transition.Slide;
import android.transition.TransitionSet;
import android.transition.TransitionValues;
import android.transition.Visibility;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.oscim.android.MapScaleBar;
import org.oscim.android.MapView;
import org.oscim.android.cache.TileCache;
import org.oscim.android.canvas.AndroidBitmap;
import org.oscim.backend.canvas.Bitmap;
import org.oscim.core.GeoPoint;
import org.oscim.core.MapPosition;
import org.oscim.event.Event;
import org.oscim.layers.Layer;
import org.oscim.layers.TileGridLayer;
import org.oscim.layers.marker.ItemizedLayer;
import org.oscim.layers.marker.MarkerItem;
import org.oscim.layers.marker.MarkerSymbol;
import org.oscim.layers.tile.buildings.BuildingLayer;
import org.oscim.layers.tile.vector.OsmTileLayer;
import org.oscim.layers.tile.vector.VectorTileLayer;
import org.oscim.layers.tile.vector.labeling.LabelLayer;
import org.oscim.map.Layers;
import org.oscim.map.Map;
import org.oscim.theme.VtmThemes;
import org.oscim.tiling.CombinedTileSource;
import org.oscim.tiling.source.UrlTileSource;
import org.oscim.tiling.source.mapfile.MultiMapFileTileSource;
import org.oscim.tiling.source.oscimap4.OSciMap4TileSource;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import mobi.maptrek.data.DataSource;
import mobi.maptrek.data.Track;
import mobi.maptrek.fragments.DataSourceList;
import mobi.maptrek.fragments.LocationInformation;
import mobi.maptrek.fragments.TrackProperties;
import mobi.maptrek.io.Manager;
import mobi.maptrek.layers.CurrentTrackLayer;
import mobi.maptrek.layers.LocationOverlay;
import mobi.maptrek.layers.TrackLayer;
import mobi.maptrek.location.BaseLocationService;
import mobi.maptrek.location.ILocationListener;
import mobi.maptrek.location.ILocationService;
import mobi.maptrek.location.LocationService;
import mobi.maptrek.util.ProgressHandler;

import static org.oscim.android.canvas.AndroidGraphics.drawableToBitmap;

public class MainActivity extends Activity implements ILocationListener,
        Map.UpdateListener,
        TrackProperties.OnTrackPropertiesChangedListener,
        ItemizedLayer.OnItemGestureListener<MarkerItem>,
        PopupMenu.OnMenuItemClickListener,
        LoaderManager.LoaderCallbacks<List<DataSource>> {
    private static final String TAG = "MailActivity";
    private static final int PERMISSIONS_REQUEST_FINE_LOCATION = 1;

    //TODO Put them in separate class
    private static final String PREF_LATITUDE = "latitude";
    private static final String PREF_LONGITUDE = "longitude";
    private static final String PREF_MAP_SCALE = "map_scale";
    private static final String PREF_MAP_BEARING = "map_bearing";
    private static final String PREF_MAP_TILT = "map_tilt";
    private static final String PREF_LOCATION_STATE = "location_state";
    public static final String PREF_TRACKING_STATE = "tracking_state";

    public static final int MAP_POSITION_ANIMATION_DURATION = 500;
    public static final int MAP_BEARING_ANIMATION_DURATION = 300;

    public enum LOCATION_STATE {
        DISABLED,
        SEARCHING,
        ENABLED,
        NORTH,
        TRACK
    }

    public enum TRACKING_STATE {
        DISABLED,
        PENDING,
        TRACKING
    }

    public enum PANEL_STATE {
        NONE,
        LOCATION,
        RECORD,
        PLACES,
        LAYERS,
        MORE
    }

    private ProgressHandler mProgressHandler;

    private ILocationService mLocationService = null;
    private boolean mIsBound = false;
    private LOCATION_STATE mLocationState;
    private LOCATION_STATE mSavedLocationState;
    private TRACKING_STATE mTrackingState;
    private MapPosition mMapPosition = new MapPosition();

    protected Map mMap;
    protected MapView mMapView;
    private TextView mSatellitesText;
    private TextView mSpeedText;
    private ImageButton mLocationButton;
    private ImageButton mRecordButton;
    private ImageButton mMoreButton;
    private View mCompassView;
    private View mGaugePanelView;
    private ProgressBar mProgressBar;
    private CoordinatorLayout mCoordinatorLayout;

    private long mLastLocationMilliseconds = 0;
    private int mMovementAnimationDuration = BaseLocationService.LOCATION_DELAY;
    private float mAveragedBearing = 0;

    private VectorDrawable mNavigationNorthDrawable;
    private VectorDrawable mNavigationTrackDrawable;
    private VectorDrawable mMyLocationDrawable;
    private VectorDrawable mLocationSearchingDrawable;

    private TileGridLayer mGridLayer;
    private CurrentTrackLayer mCurrentTrackLayer;
    private LocationOverlay mLocationOverlay;

    private TileCache mCache;

    //private DataFragment mDataFragment;
    private PANEL_STATE mPanelState;
    private boolean secondBack;
    private Toast mBackToast;

    //private MapIndex mMapIndex;
    //TODO Should we store it here?
    private List<DataSource> mData;
    private Track mEditedTrack;

    private static final boolean BILLBOARDS = true;
    //private MarkerSymbol mFocusMarker;

    @SuppressLint("ShowToast")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.e(TAG, "onCreate()");
        setContentView(R.layout.activity_main);

        /*
        // find the retained fragment on activity restarts
        FragmentManager fm = getFragmentManager();
        mDataFragment = (DataFragment) fm.findFragmentByTag("data");

        // create the fragment and data the first time
        if (mDataFragment == null) {
            // add the fragment
            mDataFragment = new DataFragment();
            fm.beginTransaction().add(mDataFragment, "data").commit();
            // load the data from the web
            File mapsDir = getExternalFilesDir("maps");
            if (mapsDir != null) {
                mMapIndex = new MapIndex(mapsDir.getAbsolutePath());
                mDataFragment.setMapIndex(mMapIndex);
            }
        } else {
            mMapIndex = mDataFragment.getMapIndex();
        }
        */

        mLocationState = LOCATION_STATE.DISABLED;
        mSavedLocationState = LOCATION_STATE.DISABLED;

        mPanelState = PANEL_STATE.NONE;

        mCoordinatorLayout = (CoordinatorLayout) findViewById(R.id.coordinatorLayout);
        mLocationButton = (ImageButton) findViewById(R.id.locationButton);
        mRecordButton = (ImageButton) findViewById(R.id.recordButton);
        mMoreButton = (ImageButton) findViewById(R.id.moreButton);

        mSatellitesText = (TextView) findViewById(R.id.satellites);
        mSpeedText = (TextView) findViewById(R.id.speed);

        mCompassView = findViewById(R.id.compass);
        mGaugePanelView = findViewById(R.id.gaugePanel);
        mProgressBar = (ProgressBar) findViewById(R.id.progressBar);

        mMapView = (MapView) findViewById(R.id.mapView);
        registerMapView(mMapView);

        Resources resources = getResources();
        Resources.Theme theme = getTheme();
        mNavigationNorthDrawable = (VectorDrawable) resources.getDrawable(R.drawable.ic_navigation_north, theme);
        mNavigationTrackDrawable = (VectorDrawable) resources.getDrawable(R.drawable.ic_navigation_track, theme);
        mMyLocationDrawable = (VectorDrawable) resources.getDrawable(R.drawable.ic_my_location, theme);
        mLocationSearchingDrawable = (VectorDrawable) resources.getDrawable(R.drawable.ic_location_searching, theme);

        UrlTileSource urlTileSource = new OSciMap4TileSource();
        File cacheDir = getExternalCacheDir();
        if (cacheDir != null) {
            mCache = new TileCache(this, cacheDir.getAbsolutePath(), "tile_cache.db");
            mCache.setCacheSize(512 * (1 << 10));
            urlTileSource.setCache(mCache);
        }

        VectorTileLayer baseLayer = new OsmTileLayer(mMap);

        File mapsDir = getExternalFilesDir("maps");
        if (mapsDir != null) {
            MultiMapFileTileSource mapFileSource = new MultiMapFileTileSource(mapsDir.getAbsolutePath());
            CombinedTileSource tileSource = new CombinedTileSource(mapFileSource, urlTileSource);
            baseLayer.setTileSource(tileSource);
        } else {
            baseLayer.setTileSource(urlTileSource);
        }

        mMap.setBaseMap(baseLayer);
        mMap.setTheme(VtmThemes.DEFAULT);

        mGridLayer = new TileGridLayer(mMap);
        mLocationOverlay = new LocationOverlay(mMap);

		/* set initial position on first run */
        MapPosition pos = new MapPosition();
        mMap.getMapPosition(pos);
        if (pos.x == 0.5 && pos.y == 0.5)
            mMap.setMapPosition(55.8194, 37.6676, Math.pow(2, 16));

        Layers layers = mMap.layers();

        //BitmapTileLayer mBitmapLayer = new BitmapTileLayer(mMap, DefaultSources.OPENSTREETMAP.build());
        //mMap.setBaseMap(mBitmapLayer);

        layers.add(new BuildingLayer(mMap, baseLayer));
        layers.add(new LabelLayer(mMap, baseLayer));
        layers.add(new MapScaleBar(mMapView));
        layers.add(mLocationOverlay);
        //layers.add(mGridLayer);

        Bitmap bitmap = drawableToBitmap(getResources(), R.drawable.marker_poi);

        MarkerSymbol symbol;
        if (BILLBOARDS)
            symbol = new MarkerSymbol(bitmap, MarkerItem.HotspotPlace.BOTTOM_CENTER);
        else
            symbol = new MarkerSymbol(bitmap, 0.5f, 0.5f, false);

        ItemizedLayer<MarkerItem> markerLayer =
                new ItemizedLayer<>(mMap, new ArrayList<MarkerItem>(), symbol, this);

        mMap.layers().add(markerLayer);

        android.graphics.Bitmap pin = BitmapFactory.decodeResource(resources, R.mipmap.marker_pin_1);
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(pin.getWidth(), pin.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);

        Paint paint = new Paint();
        paint.setColorFilter(new PorterDuffColorFilter(0xffff0000, PorterDuff.Mode.MULTIPLY));

        Canvas bc = new Canvas(image);
        bc.drawBitmap(pin, 0f, 0f, paint);

        MarkerItem marker = new MarkerItem("Home", "", new GeoPoint(55.813557, 37.645524));
        marker.setMarker(new MarkerSymbol(new AndroidBitmap(image), MarkerItem.HotspotPlace.BOTTOM_CENTER));

        List<MarkerItem> pts = new ArrayList<>();
        pts.add(marker);

        markerLayer.addItems(pts);

        //if (BuildConfig.DEBUG)
        //    StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build());

        mBackToast = Toast.makeText(this, R.string.msg_back_quit, Toast.LENGTH_SHORT);
        mProgressHandler = new ProgressHandler(mProgressBar);

        // Initialize UI event handlers
        mLocationButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onLocationClicked();
            }
        });
        mLocationButton.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                onLocationLongClicked();
                return true;
            }
        });
        mRecordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRecordClicked();
            }
        });
        mRecordButton.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                onRecordLongClicked();
                return true;
            }
        });

        // Initialize data loader
        getLoaderManager();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.e(TAG, "onStart()");

        registerReceiver(mBroadcastReceiver, new IntentFilter(BaseLocationService.BROADCAST_TRACK_SAVE));
        // Start loading user data
        DataLoader loader = (DataLoader) getLoaderManager().initLoader(0, null, this);
        loader.setProgressHandler(mProgressHandler);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume()");

        if (mSavedLocationState != LOCATION_STATE.DISABLED)
            askForPermission();
        if (mTrackingState == TRACKING_STATE.TRACKING) {
            enableTracking();
            startService(new Intent(getApplicationContext(), LocationService.class).setAction(BaseLocationService.DISABLE_BACKGROUND_TRACK));
        }

        updateMapViewArea();

        mMap.events.bind(this);
        mMapView.onResume();
        updateLocationDrawable();
        adjustCompass(mMap.getMapPosition().bearing);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.e(TAG, "onPause()");

        if (mLocationState != LOCATION_STATE.SEARCHING)
            mSavedLocationState = mLocationState;

        mMapView.onPause();
        mMap.events.unbind(this);

        // save the map position
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        MapPosition mapPosition = new MapPosition();
        mMap.viewport().getMapPosition(mapPosition);
        GeoPoint geoPoint = mapPosition.getGeoPoint();
        editor.putInt(PREF_LATITUDE, geoPoint.latitudeE6);
        editor.putInt(PREF_LONGITUDE, geoPoint.longitudeE6);
        editor.putFloat(PREF_MAP_SCALE, (float) mapPosition.scale);
        editor.putFloat(PREF_MAP_BEARING, mapPosition.bearing);
        editor.putFloat(PREF_MAP_TILT, mapPosition.tilt);
        editor.putInt(PREF_LOCATION_STATE, mSavedLocationState.ordinal());
        editor.putInt(PREF_TRACKING_STATE, mTrackingState.ordinal());
        editor.apply();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.e(TAG, "onStop()");

        unregisterReceiver(mBroadcastReceiver);

        if (isFinishing() && mTrackingState == TRACKING_STATE.TRACKING) {
            startService(new Intent(getApplicationContext(), LocationService.class).setAction(BaseLocationService.ENABLE_BACKGROUND_TRACK));
        }
        if (mLocationService != null)
            disableLocations();

        Loader<List<DataSource>> loader = getLoaderManager().getLoader(0);
        if (loader != null) {
            ((DataLoader) loader).setProgressHandler(null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.e(TAG, "onDestroy()");

        //mDataFragment.setMapIndex(mMapIndex);
        mMap.destroy();
        if (mCache != null)
            mCache.dispose();

        mProgressHandler = null;

        /*
        if (this.isFinishing()) {
            mMapIndex.clear();
        }
        */
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        Log.e(TAG, "onSaveInstanceState()");
        savedInstanceState.putSerializable("savedLocationState", mSavedLocationState);
        savedInstanceState.putLong("lastLocationMilliseconds", mLastLocationMilliseconds);
        savedInstanceState.putFloat("averagedBearing", mAveragedBearing);
        savedInstanceState.putInt("movementAnimationDuration", mMovementAnimationDuration);
        if (mProgressBar.getVisibility() == View.VISIBLE)
            savedInstanceState.putInt("progressBar", mProgressBar.getMax());
        savedInstanceState.putSerializable("panelState", mPanelState);
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        Log.e(TAG, "onRestoreInstanceState()");
        super.onRestoreInstanceState(savedInstanceState);
        mSavedLocationState = (LOCATION_STATE) savedInstanceState.getSerializable("savedLocationState");
        mLastLocationMilliseconds = savedInstanceState.getLong("lastLocationMilliseconds");
        mAveragedBearing = savedInstanceState.getFloat("averagedBearing");
        mMovementAnimationDuration = savedInstanceState.getInt("movementAnimationDuration");
        if (savedInstanceState.containsKey("progressBar")) {
            mProgressBar.setVisibility(View.VISIBLE);
            mProgressBar.setMax(savedInstanceState.getInt("progressBar"));
        }
        setPanelState((PANEL_STATE) savedInstanceState.getSerializable("panelState"));
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.theme_default:
                mMap.setTheme(VtmThemes.DEFAULT);
                item.setChecked(true);
                return true;

            case R.id.theme_tubes:
                mMap.setTheme(VtmThemes.TRONRENDER);
                item.setChecked(true);
                return true;

            case R.id.theme_osmarender:
                mMap.setTheme(VtmThemes.OSMARENDER);
                item.setChecked(true);
                return true;

            case R.id.theme_newtron:
                mMap.setTheme(VtmThemes.NEWTRON);
                item.setChecked(true);
                return true;

            case R.id.action_grid:
                if (item.isChecked()) {
                    item.setChecked(false);
                    mMap.layers().remove(mGridLayer);
                } else {
                    item.setChecked(true);
                    mMap.layers().add(mGridLayer);
                }
                mMap.updateMap(true);
                return true;
        }

        return false;
    }

    @Override
    public void onLocationChanged() {
        boolean shouldBePinned = false;
        if (mLocationState == LOCATION_STATE.SEARCHING) {
            mLocationState = mSavedLocationState;
            mMap.getEventLayer().setFixOnCenter(true);
            updateLocationDrawable();
            shouldBePinned = true;
            mLocationOverlay.setEnabled(true);
            mMap.updateMap(true);
        }

        Location location = mLocationService.getLocation();
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        float bearing = location.getBearing();
        if (bearing < mAveragedBearing - 180)
            mAveragedBearing -= 360;
        mAveragedBearing = (float) movingAverage(bearing, mAveragedBearing);
        if (mAveragedBearing < 0)
            mAveragedBearing += 360;

        updateGauges();

        if (mLocationState == LOCATION_STATE.NORTH || mLocationState == LOCATION_STATE.TRACK) {
            // Adjust map movement animation to location acquisition period to make movement smoother
            long locationDelay = SystemClock.uptimeMillis() - mLastLocationMilliseconds;
            double duration = Math.min(1500, locationDelay); // 1.5 seconds maximum
            mMovementAnimationDuration = (int) movingAverage(duration, mMovementAnimationDuration);
            // Update map position
            mMap.getMapPosition(mMapPosition);
            mMapPosition.setPosition(lat, lon);
            mMapPosition.setBearing(-mAveragedBearing);
            if (shouldBePinned) {
                mMovementAnimationDuration = (int) (mMovementAnimationDuration * 0.9);
                mMap.animator().setListener(new org.oscim.map.Animator.MapAnimationListener() {
                    @Override
                    public void onMapAnimationEnd() {
                        Log.e(TAG, "from onLocationChanged()");
                        mLocationOverlay.setPinned(true);
                    }
                });
            }
            mMap.animator().animateTo(mMovementAnimationDuration, mMapPosition, mLocationState == LOCATION_STATE.TRACK);
        }

        mLocationOverlay.setPosition(lat, lon, bearing, location.getAccuracy());
        mLastLocationMilliseconds = SystemClock.uptimeMillis();
    }

    @Override
    public void onGpsStatusChanged() {
        if (mLocationService.getStatus() == LocationService.GPS_SEARCHING) {
            int satellites = mLocationService.getSatellites();
            mSatellitesText.setText(String.format("%d / %s", satellites >> 7, satellites & 0x7f));
            if (mLocationState != LOCATION_STATE.SEARCHING) {
                mSavedLocationState = mLocationState;
                mLocationState = LOCATION_STATE.SEARCHING;
                mMap.getEventLayer().setFixOnCenter(false);
                mLocationOverlay.setPinned(false);
                mLocationOverlay.setEnabled(false);
                updateLocationDrawable();
            }
        }
    }

    public void onLocationClicked() {
        switch (mLocationState) {
            case DISABLED:
                askForPermission();
                break;
            case SEARCHING:
                mLocationState = LOCATION_STATE.DISABLED;
                disableLocations();
                break;
            case ENABLED:
                mLocationState = LOCATION_STATE.NORTH;
                mMap.getEventLayer().setFixOnCenter(true);
                mMap.getMapPosition(mMapPosition);
                mMapPosition.setPosition(mLocationService.getLocation().getLatitude(), mLocationService.getLocation().getLongitude());
                //mMapPosition.setBearing(0);
                mMap.animator().setListener(new org.oscim.map.Animator.MapAnimationListener() {
                    @Override
                    public void onMapAnimationEnd() {
                        Log.e(TAG, "from set North");
                        mLocationOverlay.setPinned(true);
                    }
                }).animateTo(MAP_POSITION_ANIMATION_DURATION, mMapPosition);
                break;
            case NORTH:
                mLocationState = LOCATION_STATE.TRACK;
                mMap.getEventLayer().enableRotation(false);
                mMap.getEventLayer().setFixOnCenter(true);
                mMap.getMapPosition(mMapPosition);
                mMapPosition.setBearing(-mLocationService.getLocation().getBearing());
                mMap.animator().animateTo(MAP_BEARING_ANIMATION_DURATION, mMapPosition);
                break;
            case TRACK:
                mLocationState = LOCATION_STATE.ENABLED;
                mMap.getEventLayer().enableRotation(true);
                mMap.getEventLayer().setFixOnCenter(false);
                mMap.getMapPosition(mMapPosition);
                mMapPosition.setBearing(0);
                mMap.animator().animateTo(MAP_BEARING_ANIMATION_DURATION, mMapPosition);
                mLocationOverlay.setPinned(false);
                break;
        }
        updateLocationDrawable();
    }

    public void onLocationLongClicked() {
        mMap.getMapPosition(mMapPosition);
        Bundle args = new Bundle(2);
        args.putDouble(LocationInformation.ARG_LATITUDE, mMapPosition.getLatitude());
        args.putDouble(LocationInformation.ARG_LONGITUDE, mMapPosition.getLongitude());
        showExtendPanel(PANEL_STATE.LOCATION, "locationInformation", LocationInformation.class.getName(), args);
    }

    public void onRecordClicked() {
        if (mLocationState == LOCATION_STATE.DISABLED) {
            mTrackingState = TRACKING_STATE.PENDING;
            askForPermission();
            return;
        }
        if (mTrackingState == TRACKING_STATE.TRACKING) {
            disableTracking();
        } else {
            enableTracking();
        }
    }

    public void onRecordLongClicked() {
        showExtendPanel(PANEL_STATE.RECORD, "trackList", DataSourceList.class.getName(), null);
    }

    public void onPlacesClicked(View view) {
    }

    public void onMoreClicked(View view) {
        PopupMenu popup = new PopupMenu(this, mMoreButton);
        mMoreButton.setOnTouchListener(popup.getDragToOpenListener());
        popup.inflate(R.menu.menu_map);
        Menu menu = popup.getMenu();
        menu.findItem(R.id.action_grid).setChecked(mMap.layers().contains(mGridLayer));
        popup.setOnMenuItemClickListener(this);
        popup.show();
    }

    public void onCompassClicked(View view) {
        if (mLocationState == LOCATION_STATE.TRACK) {
            mLocationState = LOCATION_STATE.NORTH;
            updateLocationDrawable();
            mMap.getEventLayer().enableRotation(true);
        }
        mMap.getMapPosition(mMapPosition);
        mMapPosition.setBearing(0);
        mMap.animator().animateTo(MAP_BEARING_ANIMATION_DURATION, mMapPosition);
    }

    @Override
    public boolean onItemSingleTapUp(int index, MarkerItem item) {
        Log.e(TAG, item.getTitle());
        return false;
    }

    @Override
    public boolean onItemLongPress(int index, MarkerItem item) {
        return false;
    }

    private void enableLocations() {
        mIsBound = bindService(new Intent(getApplicationContext(), LocationService.class), mLocationConnection, BIND_AUTO_CREATE);
        mLocationState = LOCATION_STATE.SEARCHING;
        if (mSavedLocationState == LOCATION_STATE.DISABLED)
            mSavedLocationState = LOCATION_STATE.NORTH;
        if (mTrackingState == TRACKING_STATE.PENDING)
            enableTracking();
        updateLocationDrawable();
    }

    private void disableLocations() {
        Log.e(TAG, "disableLocations()");
        if (mLocationService != null) {
            mLocationService.unregisterLocationCallback(this);
            mLocationService.setProgressListener(null);
        }
        if (mIsBound) {
            unbindService(mLocationConnection);
            mIsBound = false;
            mLocationOverlay.setEnabled(false);
            mMap.updateMap(true);
        }
        mLocationState = LOCATION_STATE.DISABLED;
        updateLocationDrawable();
    }

    private ServiceConnection mLocationConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            mLocationService = (ILocationService) binder;
            mLocationService.registerLocationCallback(MainActivity.this);
            mLocationService.setProgressListener(mProgressHandler);
        }

        public void onServiceDisconnected(ComponentName className) {
            mLocationService = null;
        }
    };

    private void enableTracking() {
        startService(new Intent(getApplicationContext(), LocationService.class).setAction(BaseLocationService.ENABLE_TRACK));
        mCurrentTrackLayer = new CurrentTrackLayer(mMap, org.oscim.backend.canvas.Color.fade(getColor(R.color.trackColor), 0.7), getResources().getInteger(R.integer.trackWidth), getApplicationContext());
        mMap.layers().add(mCurrentTrackLayer);
        mMap.updateMap(true);
        mTrackingState = TRACKING_STATE.TRACKING;
        updateLocationDrawable();
    }

    private void disableTracking() {
        startService(new Intent(getApplicationContext(), LocationService.class).setAction(BaseLocationService.DISABLE_TRACK));
        boolean r = mMap.layers().remove(mCurrentTrackLayer);
        Log.e(TAG, "r: " + r);
        if (mCurrentTrackLayer != null) // Can be null if called by intent
            mCurrentTrackLayer.onDetach();
        mCurrentTrackLayer = null;
        mMap.updateMap(true);
        mTrackingState = TRACKING_STATE.DISABLED;
        updateLocationDrawable();
    }

    @Override
    public void onMapEvent(Event e, MapPosition mapPosition) {
        if (e == Map.MOVE_EVENT) {
            if (mLocationState == LOCATION_STATE.NORTH || mLocationState == LOCATION_STATE.TRACK) {
                mLocationState = LOCATION_STATE.ENABLED;
                mLocationOverlay.setPinned(false);
                updateLocationDrawable();
            }
        }
        adjustCompass(mapPosition.bearing);
        /*
        List<MapFile> maps = mMapIndex.getMaps(mapPosition.getGeoPoint());
        if (maps.isEmpty()) {
            if (mOverlayMapLayer != null) {
                mMap.setBaseMap(mBaseLayer);
                mMap.updateMap(true);
                mOverlayMapLayer.onDetach();
                mOverlayMapLayer = null;
            }
        } else {
            if (mOverlayMapLayer == null) {
                MapFile mapFile = maps.get(0);
                mOverlayMapLayer = new VectorTileLayer(mMap, mapFile.tileSource);
                ((VectorTileLayer) mOverlayMapLayer).setRenderTheme(mBaseLayer.getTheme());
                mMap.setBaseMap(mOverlayMapLayer);
                mMap.updateMap(true);
            }
        }
        */
    }

    public void adjustCompass(float bearing) {
        if (mCompassView.getRotation() == bearing)
            return;
        mCompassView.setRotation(bearing);
        if (Math.abs(bearing) < 1f && mCompassView.getAlpha() == 1f) {
            mCompassView.animate().alpha(0f).setDuration(MAP_POSITION_ANIMATION_DURATION).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mCompassView.setVisibility(View.GONE);
                }
            });
        } else if (mCompassView.getVisibility() == View.GONE) {
            mCompassView.setAlpha(0f);
            mCompassView.setVisibility(View.VISIBLE);
            mCompassView.animate().alpha(1f).setDuration(MAP_POSITION_ANIMATION_DURATION).setListener(null);
        }
    }

    private void updateLocationDrawable() {
        if (mRecordButton.getTag() != mTrackingState) {
            int recordColor = getColor(mTrackingState == TRACKING_STATE.TRACKING ? R.color.colorAccent : R.color.colorPrimaryDark);
            mRecordButton.getDrawable().setTint(recordColor);
            mRecordButton.setTag(mTrackingState);
        }
        if (mLocationButton.getTag() == mLocationState)
            return;
        if (mLocationButton.getTag() == LOCATION_STATE.SEARCHING) {
            mLocationButton.clearAnimation();
            mSatellitesText.animate().translationY(-200);
        }
        switch (mLocationState) {
            case DISABLED:
                mNavigationNorthDrawable.setTint(getColor(R.color.colorPrimaryDark));
                mLocationButton.setImageDrawable(mNavigationNorthDrawable);
                break;
            case SEARCHING:
                mLocationSearchingDrawable.setTint(getColor(R.color.colorAccent));
                mLocationButton.setImageDrawable(mLocationSearchingDrawable);
                Animation rotation = new RotateAnimation(0f, 360f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                rotation.setInterpolator(new LinearInterpolator());
                rotation.setRepeatCount(Animation.INFINITE);
                rotation.setDuration(1000);
                mLocationButton.startAnimation(rotation);
                if (mGaugePanelView.getVisibility() == View.INVISIBLE) {
                    mSatellitesText.animate().translationY(8);
                } else {
                    mGaugePanelView.animate().translationX(-mGaugePanelView.getWidth()).setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (mLocationState == LOCATION_STATE.SEARCHING)
                                mSatellitesText.animate().translationY(8);
                            mGaugePanelView.animate().setListener(null);
                        }
                    });
                }
                break;
            case ENABLED:
                mMyLocationDrawable.setTint(getColor(R.color.colorPrimaryDark));
                mLocationButton.setImageDrawable(mMyLocationDrawable);
                mGaugePanelView.animate().translationX(-mGaugePanelView.getWidth());
                break;
            case NORTH:
                mNavigationNorthDrawable.setTint(getColor(R.color.colorAccent));
                mLocationButton.setImageDrawable(mNavigationNorthDrawable);
                mGaugePanelView.animate().translationX(0);
                break;
            case TRACK:
                mNavigationTrackDrawable.setTint(getColor(R.color.colorAccent));
                mLocationButton.setImageDrawable(mNavigationTrackDrawable);
                mGaugePanelView.animate().translationX(0);
        }
        mLocationButton.setTag(mLocationState);
    }

    private void updateGauges() {
        Location location = mLocationService.getLocation();
        mSpeedText.setText(String.format("%.0f", location.getSpeed() * 3.6));
    }

    private void onTrackProperties(String path) {
        //TODO Think of better way to find appropriate track
        for (DataSource source : mData) {
            if (source.path.equals(path)) {
                mEditedTrack = source.tracks.get(0);
                break;
            }
        }
        if (mEditedTrack == null)
            return;

        FragmentManager fm = getFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.setCustomAnimations(R.animator.fadein, R.animator.fadeout, R.animator.fadeout, R.animator.fadein);
        Bundle args = new Bundle(2);
        args.putString(TrackProperties.ARG_NAME, mEditedTrack.name);
        args.putInt(TrackProperties.ARG_COLOR, mEditedTrack.color);
        Fragment fragment = Fragment.instantiate(this, TrackProperties.class.getName(), args);
        ft.replace(R.id.contentPanel, fragment, "trackProperties");
        ft.addToBackStack("trackProperties");
        ft.commit();
    }

    @Override
    public void onTrackPropertiesChanged(String name, int color) {
        mEditedTrack.name = name;
        mEditedTrack.color = color;
        for (Layer layer : mMap.layers()) {
            if (layer instanceof TrackLayer && ((TrackLayer) layer).getTrack().equals(mEditedTrack)) {
                ((TrackLayer) layer).setColor(mEditedTrack.color);
            }
        }
        if (mEditedTrack.source.isSingleTrack())
            mEditedTrack.source.rename(name);

        Manager.save(getApplicationContext(), mEditedTrack.source);
        mEditedTrack = null;
    }

    /**
     * This method is called once by each MapView during its setup process.
     *
     * @param mapView the calling MapView.
     */
    public final void registerMapView(MapView mapView) {
        mMapView = mapView;
        mMap = mapView.map();

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (sharedPreferences.contains(PREF_LATITUDE) &&
                sharedPreferences.contains(PREF_LONGITUDE) &&
                sharedPreferences.contains(PREF_MAP_SCALE)) {
            // retrieve and set the map position and zoom level
            int latitudeE6 = sharedPreferences.getInt(PREF_LATITUDE, 0);
            int longitudeE6 = sharedPreferences.getInt(PREF_LONGITUDE, 0);
            float scale = sharedPreferences.getFloat(PREF_MAP_SCALE, 1);
            float bearing = sharedPreferences.getFloat(PREF_MAP_BEARING, 0);
            float tilt = sharedPreferences.getFloat(PREF_MAP_TILT, 0);

            MapPosition mapPosition = new MapPosition();
            mapPosition.setPosition(latitudeE6 / 1E6, longitudeE6 / 1E6);
            mapPosition.setScale(scale);
            mapPosition.setBearing(bearing);
            mapPosition.setTilt(tilt);

            mMap.setMapPosition(mapPosition);
        }
        int state = sharedPreferences.getInt(PREF_LOCATION_STATE, 0);
        if (state >= LOCATION_STATE.NORTH.ordinal())
            mSavedLocationState = LOCATION_STATE.values()[state];
        state = sharedPreferences.getInt(PREF_TRACKING_STATE, 0);
        mTrackingState = TRACKING_STATE.values()[state];
    }

    private void showExtendPanel(PANEL_STATE panel, String name, String fragmentName, Bundle args) {
        FragmentManager fm = getFragmentManager();

        if (mPanelState != PANEL_STATE.NONE) {
            FragmentManager.BackStackEntry bse = fm.getBackStackEntryAt(0);
            fm.popBackStackImmediate();
            if (name.equals(bse.getName())) {
                setPanelState(PANEL_STATE.NONE);
                return;
            }
        }

        FragmentTransaction ft = fm.beginTransaction();
        Fragment fragment = Fragment.instantiate(this, fragmentName, args);
        fragment.setEnterTransition(new TransitionSet().addTransition(new Slide(Gravity.BOTTOM)).addTransition(new Visibility() {
            @Override
            public Animator onAppear(ViewGroup sceneRoot, final View v, TransitionValues startValues, TransitionValues endValues) {
                return ObjectAnimator.ofObject(v, "backgroundColor", new ArgbEvaluator(), getColor(R.color.panelBackground), getColor(R.color.panelSolidBackground));
            }
        }));
        //TODO Find out why exit transition do not work
        /*
        fragment.setExitTransition(new TransitionSet().addTransition(new Slide(Gravity.BOTTOM)).addTransition(new Visibility() {
            @Override
            public Animator onDisappear(ViewGroup sceneRoot, final View v, TransitionValues startValues, TransitionValues endValues) {
                Log.e("MA", "ExitTransaction");
                return ObjectAnimator.ofObject(v, "backgroundColor", new ArgbEvaluator(), getColor(R.color.panelSolidBackground), getColor(R.color.panelBackground));
            }
        }));
        */
        ft.replace(R.id.extendPanel, fragment, name);
        ft.addToBackStack(name);
        ft.commit();

        setPanelState(panel);
    }

    private void setPanelState(PANEL_STATE state) {
        if (mPanelState == state)
            return;

        View mLBB = findViewById(R.id.locationButtonBackground);
        View mRBB = findViewById(R.id.recordButtonBackground);
        View mPBB = findViewById(R.id.placesButtonBackground);
        View mOBB = findViewById(R.id.layersButtonBackground);
        View mMBB = findViewById(R.id.moreButtonBackground);

        //TODO Search for view state animation
        // View that gains active state
        final View thisView;
        final ArrayList<View> otherViews = new ArrayList<>();

        if (mPanelState == PANEL_STATE.NONE || state == PANEL_STATE.NONE) {
            otherViews.add(mLBB);
            otherViews.add(mRBB);
            otherViews.add(mPBB);
            otherViews.add(mOBB);
            otherViews.add(mMBB);
        } else {
            // If switching from one view to another animate only that view
            switch (mPanelState) {
                case LOCATION:
                    otherViews.add(mLBB);
                    break;
                case RECORD:
                    otherViews.add(mRBB);
                    break;
                case PLACES:
                    otherViews.add(mPBB);
                    break;
                case LAYERS:
                    otherViews.add(mOBB);
                    break;
                case MORE:
                    otherViews.add(mMBB);
                    break;
            }
        }

        PANEL_STATE thisState = state == PANEL_STATE.NONE ? mPanelState : state;
        switch (thisState) {
            case LOCATION:
                thisView = mLBB;
                break;
            case RECORD:
                thisView = mRBB;
                break;
            case PLACES:
                thisView = mPBB;
                break;
            case LAYERS:
                thisView = mOBB;
                break;
            case MORE:
                thisView = mMBB;
                break;
            default:
                return;
        }
        otherViews.remove(thisView);

        int thisFrom, thisTo, otherFrom, otherTo;
        if (state == PANEL_STATE.NONE) {
            thisFrom = R.color.panelSolidBackground;
            thisTo = R.color.panelBackground;
            otherFrom = R.color.panelExtendedBackground;
            otherTo = R.color.panelBackground;
        } else {
            if (mPanelState == PANEL_STATE.NONE)
                thisFrom = R.color.panelBackground;
            else
                thisFrom = R.color.panelExtendedBackground;
            thisTo = R.color.panelSolidBackground;
            if (mPanelState == PANEL_STATE.NONE)
                otherFrom = R.color.panelBackground;
            else
                otherFrom = R.color.panelSolidBackground;
            otherTo = R.color.panelExtendedBackground;
        }
        ValueAnimator otherColorAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), getColor(otherFrom), getColor(otherTo));
        ValueAnimator thisColorAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), getColor(thisFrom), getColor(thisTo));
        thisColorAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {

            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                int color = (Integer) animator.getAnimatedValue();
                thisView.setBackgroundColor(color);
            }

        });
        otherColorAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                int color = (Integer) animator.getAnimatedValue();
                for (View otherView : otherViews)
                    otherView.setBackgroundColor(color);
            }
        });
        AnimatorSet s = new AnimatorSet();
        s.play(thisColorAnimation).with(otherColorAnimation);
        s.start();

        mPanelState = state;
    }

    final Handler mBackHandler = new Handler();

    @Override
    public void onBackPressed() {
        int count = getFragmentManager().getBackStackEntryCount();
        if (count > 0) {
            super.onBackPressed();
            if (count == 1 && mPanelState != PANEL_STATE.NONE)
                setPanelState(PANEL_STATE.NONE);
            return;
        }

        if (count == 0 || secondBack) {
            //mBackToast.cancel();
            finish();
        } else {
            secondBack = true;
            mBackToast.show();
            mBackHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    secondBack = false;
                }
            }, 2000);
        }
    }

    private void updateMapViewArea() {
        final ViewTreeObserver vto = mMapView.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                Rect area = new Rect();
                mMapView.getLocalVisibleRect(area);
                if (mGaugePanelView != null)
                    area.top = mGaugePanelView.getBottom();
                View v = findViewById(R.id.actionPanel);
                if (v != null)
                    area.bottom = v.getTop();
                /*
                if (mapLicense.isShown())
                {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB && mapLicense.getRotation() != 0f)
                        area.left = mapLicense.getHeight(); // rotated view does not correctly report it's position
                    else
                        area.bottom = mapLicense.getTop();
                }
                */
                /*
                v = root.findViewById(R.id.rightbar);
                if (v != null)
                    area.right = v.getLeft();
                if (mapButtons.isShown())
                {
                    // Landscape mode
                    if (v != null)
                        area.bottom = mapButtons.getTop();
                    else
                        area.right = mapButtons.getLeft();
                }
                */
                /*
                if (!area.isEmpty())
                    map.updateViewArea(area);
                */
                ViewTreeObserver ob;
                if (vto.isAlive())
                    ob = vto;
                else
                    ob = mMapView.getViewTreeObserver();

                ob.removeOnGlobalLayoutListener(this);

                mGaugePanelView.setTranslationX(-mGaugePanelView.getWidth());
                mGaugePanelView.setVisibility(View.VISIBLE);
            }
        });
    }

    private void askForPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Should we show an explanation?
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS_REQUEST_FINE_LOCATION);
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.

            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS_REQUEST_FINE_LOCATION);
            }
        } else {
            enableLocations();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_FINE_LOCATION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    enableLocations();
                    //} else {

                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                }
                //return;
            }

            // other 'case' lines to check for other
            // permissions this app might request
        }
    }

    @Override
    public Loader<List<DataSource>> onCreateLoader(int id, Bundle args) {
        Log.e(TAG, "onCreateLoader(" + id + ")");
        return new DataLoader(this);
    }

    @Override
    public void onLoadFinished(Loader<List<DataSource>> loader, List<DataSource> data) {
        Log.e(TAG, "onLoadFinished()");
        if (data == null)
            return;
        mData = data;
        for (DataSource source : mData) {
            for (Track track : source.tracks) {
                if (track.color == -1)
                    track.color = getColor(R.color.trackColor);
                if (track.width == -1)
                    track.width = getResources().getInteger(R.integer.trackWidth);
                for (Iterator<Layer> i = mMap.layers().iterator(); i.hasNext(); ) {
                    Layer layer = i.next();
                    if (!(layer instanceof TrackLayer))
                        continue;
                    DataSource src = ((TrackLayer) layer).getTrack().source;
                    if (src == null)
                        continue;
                    if (src.path.equals(source.path)) {
                        i.remove();
                        layer.onDetach();
                    }
                }
                TrackLayer trackLayer = new TrackLayer(mMap, track, track.color, track.width);
                mMap.layers().add(trackLayer);
            }
        }
        Fragment trackList = getFragmentManager().findFragmentByTag("trackList");
        if (trackList != null)
            ((DataSourceList) trackList).initData();
        mMap.updateMap(true);
    }

    @Override
    public void onLoaderReset(Loader<List<DataSource>> loader) {

    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Broadcast: " + action);
            if (action.equals(BaseLocationService.BROADCAST_TRACK_SAVE)) {
                final Bundle extras = intent.getExtras();
                boolean saved = extras.getBoolean("saved");
                if (saved) {
                    Log.e(TAG, "Track saved: " + extras.getString("path"));
                    Snackbar snackbar = Snackbar
                            .make(mCoordinatorLayout, R.string.msg_track_saved, Snackbar.LENGTH_LONG)
                            .setAction(R.string.action_customize, new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    onTrackProperties(extras.getString("path"));
                                }
                            });
                    snackbar.show();
                    return;
                }
                String reason = extras.getString("reason");
                Log.e(TAG, "Track not saved: " + reason);
                if ("period".equals(reason) || "distance".equals(reason)) {
                    int msg = "period".equals(reason) ? R.string.msg_track_not_saved_period : R.string.msg_track_not_saved_distance;
                    Snackbar snackbar = Snackbar
                            .make(mCoordinatorLayout, msg, Snackbar.LENGTH_LONG)
                            .setAction(R.string.action_save, new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    mLocationService.saveTrack();
                                }
                            });
                    snackbar.show();
                }
            }
        }
    };

    public Map getMap() {
        return mMap;
    }

    public List<DataSource> getData() {
        return mData;
    }

    public void setDataSourceAvailability(DataSource source, boolean available) {
        if (available) {
            if (source.isLoaded()) {
                for (Track track : source.tracks) {
                    if (track.color == -1)
                        track.color = getColor(R.color.trackColor);
                    if (track.width == -1)
                        track.width = getResources().getInteger(R.integer.trackWidth);
                    TrackLayer trackLayer = new TrackLayer(mMap, track, track.color, track.width);
                    mMap.layers().add(trackLayer);
                }
            }
        } else {
            for (Iterator<Layer> i = mMap.layers().iterator(); i.hasNext(); ) {
                Layer layer = i.next();
                if (!(layer instanceof TrackLayer))
                    continue;
                DataSource src = ((TrackLayer) layer).getTrack().source;
                if (src == null)
                    continue;
                if (src.equals(source)) {
                    i.remove();
                    layer.onDetach();
                }
            }
        }
        source.setVisible(available);
        Loader<List<DataSource>> loader = getLoaderManager().getLoader(0);
        if (loader != null)
            ((DataLoader) loader).markDataSourceLoadable(source, available);
        mMap.updateMap(true);
    }

    private double movingAverage(double current, double previous) {
        return 0.2 * previous + 0.8 * current;
    }
}