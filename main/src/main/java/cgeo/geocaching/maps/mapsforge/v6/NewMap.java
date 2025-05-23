package cgeo.geocaching.maps.mapsforge.v6;

import cgeo.geocaching.AbstractDialogFragment;
import cgeo.geocaching.AbstractDialogFragment.TargetInfo;
import cgeo.geocaching.CacheListActivity;
import cgeo.geocaching.CompassActivity;
import cgeo.geocaching.Intents;
import cgeo.geocaching.R;
import cgeo.geocaching.SearchResult;
import cgeo.geocaching.activity.AbstractNavigationBarMapActivity;
import cgeo.geocaching.activity.ActivityMixin;
import cgeo.geocaching.activity.FilteredActivity;
import cgeo.geocaching.databinding.MapMapsforgeV6Binding;
import cgeo.geocaching.downloader.DownloaderUtils;
import cgeo.geocaching.enumerations.CoordinateType;
import cgeo.geocaching.enumerations.LoadFlags;
import cgeo.geocaching.filters.core.GeocacheFilter;
import cgeo.geocaching.filters.core.GeocacheFilterContext;
import cgeo.geocaching.filters.gui.GeocacheFilterActivity;
import cgeo.geocaching.location.GeoItemHolder;
import cgeo.geocaching.location.Geopoint;
import cgeo.geocaching.location.ProximityNotification;
import cgeo.geocaching.location.Viewport;
import cgeo.geocaching.log.LoggingUI;
import cgeo.geocaching.maps.MapMode;
import cgeo.geocaching.maps.MapOptions;
import cgeo.geocaching.maps.MapProviderFactory;
import cgeo.geocaching.maps.MapSettingsUtils;
import cgeo.geocaching.maps.MapState;
import cgeo.geocaching.maps.MapUtils;
import cgeo.geocaching.maps.RouteTrackUtils;
import cgeo.geocaching.maps.Tracks;
import cgeo.geocaching.maps.interfaces.MapSource;
import cgeo.geocaching.maps.interfaces.OnMapDragListener;
import cgeo.geocaching.maps.mapsforge.AbstractMapsforgeMapSource;
import cgeo.geocaching.maps.mapsforge.MapsforgeMapProvider;
import cgeo.geocaching.maps.mapsforge.v6.caches.CachesBundle;
import cgeo.geocaching.maps.mapsforge.v6.caches.GeoitemLayer;
import cgeo.geocaching.maps.mapsforge.v6.caches.GeoitemRef;
import cgeo.geocaching.maps.mapsforge.v6.layers.CoordsMarkerLayer;
import cgeo.geocaching.maps.mapsforge.v6.layers.GeoObjectLayer;
import cgeo.geocaching.maps.mapsforge.v6.layers.HistoryLayer;
import cgeo.geocaching.maps.mapsforge.v6.layers.ITileLayer;
import cgeo.geocaching.maps.mapsforge.v6.layers.NavigationLayer;
import cgeo.geocaching.maps.mapsforge.v6.layers.PositionLayer;
import cgeo.geocaching.maps.mapsforge.v6.layers.RouteLayer;
import cgeo.geocaching.maps.mapsforge.v6.layers.TapHandlerLayer;
import cgeo.geocaching.maps.mapsforge.v6.layers.TrackLayer;
import cgeo.geocaching.maps.routing.Routing;
import cgeo.geocaching.maps.routing.RoutingMode;
import cgeo.geocaching.models.Geocache;
import cgeo.geocaching.models.IndividualRoute;
import cgeo.geocaching.models.Route;
import cgeo.geocaching.models.RouteItem;
import cgeo.geocaching.models.TrailHistoryElement;
import cgeo.geocaching.models.Waypoint;
import cgeo.geocaching.models.geoitem.IGeoItemSupplier;
import cgeo.geocaching.sensors.GeoDirHandler;
import cgeo.geocaching.sensors.LocationDataProvider;
import cgeo.geocaching.service.CacheDownloaderService;
import cgeo.geocaching.service.GeocacheChangedBroadcastReceiver;
import cgeo.geocaching.settings.Settings;
import cgeo.geocaching.storage.DataStore;
import cgeo.geocaching.storage.LocalStorage;
import cgeo.geocaching.ui.GeoItemSelectorUtils;
import cgeo.geocaching.ui.RepeatOnHoldListener;
import cgeo.geocaching.ui.ToggleItemType;
import cgeo.geocaching.ui.ViewUtils;
import cgeo.geocaching.ui.dialog.Dialogs;
import cgeo.geocaching.ui.dialog.SimpleDialog;
import cgeo.geocaching.unifiedmap.UnifiedMapViewModel;
import cgeo.geocaching.utils.AndroidRxUtils;
import cgeo.geocaching.utils.AngleUtils;
import cgeo.geocaching.utils.ApplicationSettings;
import cgeo.geocaching.utils.CompactIconModeUtils;
import cgeo.geocaching.utils.FilterUtils;
import cgeo.geocaching.utils.Formatter;
import cgeo.geocaching.utils.HideActionBarUtils;
import cgeo.geocaching.utils.HistoryTrackUtils;
import cgeo.geocaching.utils.LifecycleAwareBroadcastReceiver;
import cgeo.geocaching.utils.Log;
import cgeo.geocaching.utils.MapLineUtils;
import cgeo.geocaching.utils.MenuUtils;
import cgeo.geocaching.utils.functions.Func1;
import static cgeo.geocaching.Intents.ACTION_INDIVIDUALROUTE_CHANGED;
import static cgeo.geocaching.Intents.ACTION_INVALIDATE_MAPLIST;
import static cgeo.geocaching.filters.core.GeocacheFilterContext.FilterType.LIVE;
import static cgeo.geocaching.filters.gui.GeocacheFilterActivity.EXTRA_FILTER_CONTEXT;
import static cgeo.geocaching.maps.MapProviderFactory.MAP_LANGUAGE_DEFAULT_ID;
import static cgeo.geocaching.maps.mapsforge.v6.caches.CachesBundle.NO_OVERLAY_ID;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources.NotFoundException;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.core.text.HtmlCompat;
import androidx.core.util.Supplier;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.MapPosition;
import org.mapsforge.core.model.Point;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.core.util.Parameters;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.graphics.AndroidResourceBitmap;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.layer.Layer;
import org.mapsforge.map.layer.Layers;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.model.common.Observer;

@SuppressLint("ClickableViewAccessibility")
// This is definitely a valid issue, but can't be refactored in one step
@SuppressWarnings("PMD.ExcessiveClassLength")
public class NewMap extends AbstractNavigationBarMapActivity implements Observer, FilteredActivity, AbstractDialogFragment.TargetUpdateReceiver {
    private static final String STATE_ROUTETRACKUTILS = "routetrackutils";

    private static final String ROUTING_SERVICE_KEY = "NewMap";

    private MfMapView mapView;
    private TileCache tileCache;
    private MapSource mapSource;
    private ITileLayer tileLayer;
    private HistoryLayer historyLayer;
    private PositionLayer positionLayer;
    private NavigationLayer navigationLayer;
    private TapHandlerLayer tapHandlerLayer;
    private RouteLayer routeLayer;
    private TrackLayer trackLayer;
    private GeoObjectLayer geoObjectLayer;
    private CoordsMarkerLayer coordsMarkerLayer;
    private Geopoint coordsMarkerPosition;
    private CachesBundle caches;
    private final MapHandlers mapHandlers = new MapHandlers(new TapHandler(this), new DisplayHandler(this), new ShowProgressHandler(this));

    private RenderThemeHelper renderThemeHelper = null; //must be initialized in onCreate();

    private DistanceView distanceView;
    private View mapAttribution;

    private final ArrayList<TrailHistoryElement> trailHistory = null;

    private String targetGeocode = null;
    private Geopoint lastNavTarget = null;
    private final Queue<String> popupGeocodes = new ConcurrentLinkedQueue<>();

    private ProgressBar spinner;

    private final UpdateLoc geoDirUpdate = new UpdateLoc(this);
    /**
     * initialization with an empty subscription to make static code analysis tools more happy
     */
    private ProximityNotification proximityNotification;
    private final CompositeDisposable resumeDisposables = new CompositeDisposable();
    private MapOptions mapOptions;
    private TargetView targetView;
    private IndividualRoute individualRoute = null;
    private Tracks tracks = null;
    private UnifiedMapViewModel.SheetInfo sheetInfo = null;

    private static boolean followMyLocationSwitch = Settings.getFollowMyLocation();
    private final int[] inFollowMyLocation = { 0 };

    private static final String BUNDLE_MAP_STATE = "mapState";
    private static final String BUNDLE_PROXIMITY_NOTIFICATION = "proximityNotification";
    private static final String BUNDLE_FILTERCONTEXT = "filterContext";
    private static final String BUNDLE_SHEETINFO = "sheetInfo";

    // Handler messages
    // DisplayHandler
    public static final int UPDATE_TITLE = 0;
    // ShowProgressHandler
    public static final int HIDE_PROGRESS = 0;
    public static final int SHOW_PROGRESS = 1;

    private Viewport lastViewport = null;
    private boolean lastCompactIconMode = false;

    private MapMode mapMode;

    private RouteTrackUtils routeTrackUtils = null;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        ApplicationSettings.setLocale(this);
        super.onCreate(savedInstanceState);

        Log.d("NewMap: onCreate");

        routeTrackUtils = new RouteTrackUtils(this, savedInstanceState == null ? null : savedInstanceState.getBundle(STATE_ROUTETRACKUTILS), this::centerOnPosition, this::clearIndividualRoute, this::reloadIndividualRoute, this::setTrack, this::isTargetSet);
        tracks = new Tracks(routeTrackUtils, this::setTrack);

        ResourceBitmapCacheMonitor.addRef();
        AndroidGraphicFactory.createInstance(this.getApplication());
        AndroidGraphicFactory.INSTANCE.setSvgCacheDir(LocalStorage.getMapsforgeSvgCacheDir());

        MapsforgeMapProvider.getInstance().updateOfflineMaps();

        this.renderThemeHelper = new RenderThemeHelper(this);

        // Support for multi-threaded map painting
        Parameters.NUMBER_OF_THREADS = Settings.getMapOsmThreads();
        Log.i("OSM #threads=" + Parameters.NUMBER_OF_THREADS);

        // Use fast parent tile rendering to increase performance when zooming in
        Parameters.PARENT_TILES_RENDERING = Parameters.ParentTilesRendering.SPEED;

        // Get parameters from the intent
        mapOptions = new MapOptions(this, getIntent().getExtras());

        // Get fresh map information from the bundle if any
        if (savedInstanceState != null) {
            mapOptions.mapState = savedInstanceState.getParcelable(BUNDLE_MAP_STATE);
            mapOptions.filterContext = savedInstanceState.getParcelable(BUNDLE_FILTERCONTEXT);
            proximityNotification = savedInstanceState.getParcelable(BUNDLE_PROXIMITY_NOTIFICATION);
            followMyLocationSwitch = mapOptions.mapState.followsMyLocation();
            sheetInfo = savedInstanceState.getParcelable(BUNDLE_SHEETINFO);
        } else {
            if (mapOptions.mapState != null) {
                followMyLocationSwitch = mapOptions.mapState.followsMyLocation();
            } else {
                followMyLocationSwitch = followMyLocationSwitch && mapOptions.mapMode == MapMode.LIVE;
            }
            configureProximityNotifications();
        }
        individualRoute = null;
        if (null != proximityNotification) {
            proximityNotification.setTextNotifications(this);
        }

        ActivityMixin.onCreate(this, true);

        // set layout
        ActivityMixin.setTheme(this);

        // adding the bottom navigation component is handled by {@link AbstractBottomNavigationActivity#setContentView}
        HideActionBarUtils.setContentView(this, MapMapsforgeV6Binding.inflate(getLayoutInflater()).getRoot(), true);

        setTitle();
        this.mapAttribution = findViewById(R.id.map_attribution);

        // map settings popup
        findViewById(R.id.map_settings_popup).setOnClickListener(v -> MapSettingsUtils.showSettingsPopup(this, individualRoute, this::refreshMapData, this::routingModeChanged, this::compactIconModeChanged, this::configureProximityNotifications, mapOptions.filterContext));

        // routes / tracks popup
        findViewById(R.id.map_individualroute_popup).setOnClickListener(v -> routeTrackUtils.showPopup(individualRoute, this::setTarget, null));

        // prepare circular progress spinner
        spinner = findViewById(R.id.map_progressbar);
        spinner.setVisibility(View.GONE);

        // initialize map
        mapView = findViewById(R.id.mfmapv5);

        mapView.setClickable(true);
        mapView.getMapScaleBar().setVisible(true);
        mapView.setBuiltInZoomControls(true);

        // style zoom controls
        mapView.setBuiltInZoomControls(false);
        findViewById(R.id.map_zoomin).setOnTouchListener(new RepeatOnHoldListener(500, v -> mapView.getModel().mapViewPosition.zoomIn()));
        findViewById(R.id.map_zoomout).setOnTouchListener(new RepeatOnHoldListener(500, v -> mapView.getModel().mapViewPosition.zoomOut()));

        //make room for map attribution icon button
        final int mapAttPx = Math.round(this.getResources().getDisplayMetrics().density * 30);
        mapView.getMapScaleBar().setMarginHorizontal(mapAttPx);

        // create a tile cache of suitable size. always initialize it based on the smallest tile size to expect (256 for online tiles)
        tileCache = AndroidUtil.createTileCache(this, "mapcache", 256, 1f, this.mapView.getModel().frameBufferModel.getOverdrawFactor());

        // attach drag handler
        final DragHandler dragHandler = new DragHandler(this);
        mapView.setOnMapDragListener(dragHandler);

        mapMode = (mapOptions != null && mapOptions.mapMode != null) ? mapOptions.mapMode : MapMode.LIVE;

        // prepare initial settings of mapView
        if (mapOptions.mapState != null) {
            this.mapView.getModel().mapViewPosition.setCenter(MapsforgeUtils.toLatLong(mapOptions.mapState.getCenter()));
            this.mapView.setMapZoomLevel((byte) mapOptions.mapState.getZoomLevel());
            this.targetGeocode = mapOptions.mapState.getTargetGeocode();
            this.lastNavTarget = mapOptions.mapState.getLastNavTarget();
            mapOptions.isLiveEnabled = mapOptions.mapState.isLiveEnabled();
            mapOptions.isStoredEnabled = mapOptions.mapState.isStoredEnabled();
        } else if (mapOptions.searchResult != null) {
            final Viewport viewport = DataStore.getBounds(mapOptions.searchResult.getGeocodes());

            if (viewport != null) {
                postZoomToViewport(viewport);
            }
        } else if (StringUtils.isNotEmpty(mapOptions.geocode) && mapOptions.mapMode != MapMode.COORDS) {
            final Viewport viewport = DataStore.getBounds(mapOptions.geocode, Settings.getZoomIncludingWaypoints());

            if (viewport != null) {
                postZoomToViewport(viewport);
            }
            targetGeocode = mapOptions.geocode;
            final Geocache temp = getCurrentTargetCache();
            if (temp != null) {
                lastNavTarget = temp.getCoords();
            }
        } else if (mapOptions.coords != null) {
            postZoomToViewport(new Viewport(mapOptions.coords, 0, 0));
            if (mapOptions.mapMode == MapMode.LIVE) {
                coordsMarkerPosition = mapOptions.coords;
                if (coordsMarkerLayer != null) {
                    coordsMarkerLayer.setCoordsMarker(coordsMarkerPosition);
                }
                mapOptions.coords = null;   // no direction line, even if enabled in settings
                followMyLocationSwitch = false;   // do not center on GPS position, even if in LIVE mode
            }
        } else {
            postZoomToViewport(new Viewport(Settings.getMapCenter().getCoords(), 0, 0));
        }

        FilterUtils.initializeFilterBar(this, this);
        MapUtils.updateFilterBar(this, mapOptions.filterContext);

        Routing.connect(ROUTING_SERVICE_KEY, () -> resumeRoute(true), this);
        CompactIconModeUtils.setCompactIconModeThreshold(getResources());

        MapsforgeMapProvider.getInstance().updateOfflineMaps();

        MapUtils.showMapOneTimeMessages(this, mapMode);
        getLifecycle().addObserver(new GeocacheChangedBroadcastReceiver(this) {
            @Override
            protected void onReceive(final Context context, final String geocode) {
                caches.invalidate(Collections.singleton(geocode));
            }
        });

        this.getLifecycle().addObserver(new LifecycleAwareBroadcastReceiver(this, ACTION_INVALIDATE_MAPLIST) {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                invalidateOptionsMenu();
            }
        });

        getLifecycle().addObserver(new LifecycleAwareBroadcastReceiver(this, ACTION_INDIVIDUALROUTE_CHANGED) {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                reloadIndividualRoute();
            }
        });

    }

    private void postZoomToViewport(final Viewport viewport) {
        mapView.post(() -> mapView.zoomToViewport(viewport, this.mapMode));
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull final Menu menu) {
        final boolean result = super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.map_activity, menu);

        MapProviderFactory.addMapviewMenuItems(this, menu);
        MapProviderFactory.addMapViewLanguageMenuItems(menu);

        initFollowMyLocation(followMyLocationSwitch);
        FilterUtils.initializeFilterMenu(this, this);

        return result;
    }

    @Override
    public int getSelectedBottomItemId() {
        return mapOptions.mapMode == MapMode.LIVE ? MENU_MAP : MENU_HIDE_NAVIGATIONBAR;
    }

    @Override
    public boolean onPrepareOptionsMenu(@NonNull final Menu menu) {
        super.onPrepareOptionsMenu(menu);
        if (mapOptions != null) {
            ViewUtils.extendMenuActionBarDisplayItemCount(this, menu);
        }

        for (final MapSource mapSource : MapProviderFactory.getMapSources()) {
            MenuUtils.setVisible(menu.findItem(mapSource.getNumericalId()), mapSource.isAvailable());
        }

        try {
            final MenuItem itemMapLive = menu.findItem(R.id.menu_map_live);
            ToggleItemType.LIVE_MODE.toggleMenuItem(itemMapLive, mapOptions.isLiveEnabled);

            itemMapLive.setVisible(mapOptions.coords == null || mapOptions.mapMode == MapMode.LIVE);

            menu.findItem(R.id.menu_store_caches).setVisible(!caches.isDownloading() && !caches.getVisibleCacheGeocodes().isEmpty());

            final boolean tileLayerHasThemes = tileLayerHasThemes();
            menu.findItem(R.id.menu_theme_mode).setVisible(tileLayerHasThemes);
            menu.findItem(R.id.menu_theme_options).setVisible(tileLayerHasThemes);

            menu.findItem(R.id.menu_as_list).setVisible(!caches.isDownloading() && caches.getVisibleCachesCount() > 1);

            menu.findItem(R.id.menu_hint).setVisible(mapOptions.mapMode == MapMode.SINGLE);
            menu.findItem(R.id.menu_compass).setVisible(mapOptions.mapMode == MapMode.SINGLE);
            if (mapOptions.mapMode == MapMode.SINGLE) {
                LoggingUI.onPrepareOptionsMenu(menu, getSingleModeCache());
            }
            HistoryTrackUtils.onPrepareOptionsMenu(menu);
        } catch (final RuntimeException e) {
            Log.e("NewMap.onPrepareOptionsMenu", e);
        }

        return true;
    }

    @Override
    public void onConfigurationChanged(@NonNull final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        invalidateOptionsMenu();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.menu_map_live) {
            mapOptions.isLiveEnabled = !mapOptions.isLiveEnabled;
            if (mapOptions.isLiveEnabled) {
                mapOptions.isStoredEnabled = true;
                mapOptions.filterContext = new GeocacheFilterContext(LIVE);
                caches.setFilterContext(mapOptions.filterContext);
                refreshMapData(false, true);
            }

            if (mapOptions.mapMode == MapMode.LIVE) {
                Settings.setLiveMap(mapOptions.isLiveEnabled);
            }
            caches.handleStoredLayers(this, mapOptions);
            caches.handleLiveLayers(this, mapOptions);
            ActivityMixin.invalidateOptionsMenu(this);
            if (mapOptions.mapMode == MapMode.SINGLE) {
                // reset target cache on single mode map
                targetGeocode = mapOptions.geocode;
            }
            mapOptions.mapMode = MapMode.LIVE;
            updateSelectedBottomNavItemId();
            mapOptions.title = StringUtils.EMPTY;
            setTitle();
        } else if (id == R.id.menu_filter) {
            showFilterMenu();
        } else if (id == R.id.menu_store_caches) {
            final Set<String> visibleCacheGeocodes = caches.getVisibleCacheGeocodes();
            CacheDownloaderService.downloadCaches(this, visibleCacheGeocodes, false, false, () -> caches.invalidate(visibleCacheGeocodes));
        } else if (id == R.id.menu_theme_mode) {
            this.renderThemeHelper.selectMapTheme(this.tileLayer, this.tileCache);
        } else if (id == R.id.menu_theme_options) {
            this.renderThemeHelper.selectMapThemeOptions();
        } else if (id == R.id.menu_as_list) {
            CacheListActivity.startActivityMap(this, new SearchResult(caches.getVisibleCacheGeocodes()));
            ActivityMixin.overrideTransitionToFade(this);
        } else if (id == R.id.menu_hillshading) {
            Settings.setMapShadingShowLayer(!Settings.getMapShadingShowLayer());
            item.setChecked(Settings.getMapShadingShowLayer());
            changeMapSource(mapSource);
        } else if (id == R.id.menu_hint) {
            menuShowHint();
        } else if (id == R.id.menu_compass) {
            menuCompass();
        } else if (LoggingUI.onMenuItemSelected(item, this, getSingleModeCache(), null)) {
            return true;
        } else if (id == R.id.menu_check_routingdata) {
            final BoundingBox bb = mapView.getBoundingBox();
            MapUtils.checkRoutingData(this, bb.minLatitude, bb.minLongitude, bb.maxLatitude, bb.maxLongitude);
        } else if (id == R.id.menu_check_hillshadingdata) {
            final BoundingBox bb = mapView.getBoundingBox();
            MapUtils.checkHillshadingData(this, bb.minLatitude, bb.minLongitude, bb.maxLatitude, bb.maxLongitude);
        } else if (HistoryTrackUtils.onOptionsItemSelected(this, id, () -> historyLayer.requestRedraw(), this::clearTrailHistory)
                || DownloaderUtils.onOptionsItemSelected(this, id)) {
            return true;
        } else if (id == R.id.menu_routetrack) {
            routeTrackUtils.showPopup(individualRoute, this::setTarget, null);
        } else {
            final String language = MapProviderFactory.getLanguage(id);
            final MapSource mapSource = MapProviderFactory.getMapSource(id);
            if (language != null || id == MAP_LANGUAGE_DEFAULT_ID) {
                item.setChecked(true);
                changeLanguage(language);
                return true;
            }
            if (mapSource != null) {
                item.setChecked(true);
                changeMapSource(mapSource);
                return true;
            }
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private void refreshMapData(final boolean circlesSwitched, final boolean filterChanged) {
        if (circlesSwitched) {
            caches.switchCircles();
        }
        if (caches != null) {
            caches.invalidate();
        }
        if (null != trackLayer) {
            trackLayer.requestRedraw();
        }
        if (null != geoObjectLayer) {
            geoObjectLayer.requestRedraw();
        }

        if (filterChanged) {
            MapUtils.updateFilterBar(this, mapOptions.filterContext);
        }
    }

    private void routingModeChanged(final RoutingMode newValue) {
        Settings.setRoutingMode(newValue);
        if ((null != individualRoute && individualRoute.getNumSegments() > 0) || null != tracks) {
            ViewUtils.showShortToast(this, R.string.brouter_recalculating);
        }
        reloadIndividualRoute();
        if (null != tracks) {
            try {
                AndroidRxUtils.andThenOnUi(Schedulers.computation(), () -> tracks.traverse((key, route, color, width) -> {
                    if (route instanceof Route) {
                        ((Route) route).calculateNavigationRoute();
                    }
                    trackLayer.updateRoute(key, route, color, width);
                }), () -> trackLayer.requestRedraw());
            } catch (RejectedExecutionException e) {
                Log.e("NewMap.routingModeChanged: RejectedExecutionException: " + e.getMessage());
            }
        }
        navigationLayer.requestRedraw();
    }

    private void compactIconModeChanged(final int newValue) {
        Settings.setCompactIconMode(newValue);
        caches.invalidateAll(NO_OVERLAY_ID);
    }

    private void clearTrailHistory() {
        this.historyLayer.reset();
        this.historyLayer.requestRedraw();
        showToast(res.getString(R.string.map_trailhistory_cleared));
    }

    private void clearIndividualRoute() {
        individualRoute.clearRoute(routeLayer);
        individualRoute.clearRoute((route) -> {
            routeLayer.updateIndividualRoute(route);
            updateRouteTrackButtonVisibility();
        });
        distanceView.showRouteDistance();
        showToast(res.getString(R.string.map_individual_route_cleared));
    }

    private void centerOnPosition(final double latitude, final double longitude, final Viewport viewport) {
        followMyLocationSwitch = false;
        initFollowMyLocation(false);
        mapView.getModel().mapViewPosition.setMapPosition(new MapPosition(new LatLong(latitude, longitude), (byte) mapView.getMapZoomLevel()));
        postZoomToViewport(viewport);
    }

    private void menuCompass() {
        final Geocache cache = getCurrentTargetCache();
        if (cache != null) {
            CompassActivity.startActivityCache(this, cache);
        }
    }

    private void menuShowHint() {
        final Geocache cache = getCurrentTargetCache();
        if (cache != null) {
            cache.showHintToast(this);
        }
    }

    @Override
    public void showFilterMenu() {
        FilterUtils.openFilterActivity(this, mapOptions.filterContext,
                new SearchResult(caches.getVisibleCacheGeocodes()).getCachesFromSearchResult(LoadFlags.LOAD_CACHE_OR_DB));
    }

    @Override
    public boolean showSavedFilterList() {
        return FilterUtils.openFilterList(this, mapOptions.filterContext);
    }

    @Override
    public void refreshWithFilter(final GeocacheFilter filter) {
        mapOptions.filterContext.set(filter);
        refreshMapData(false, true);
    }

    private void changeMapSource(@NonNull final MapSource newSource) {
        final MapSource oldSource = Settings.getMapSource();
        final boolean restartRequired = !MapProviderFactory.isSameActivity(oldSource, newSource);

        // Update MapSource in settings
        Settings.setMapSource(newSource);

        if (restartRequired) {
            mapRestart();
        } else if (mapView != null) {  // changeMapSource can be called by onCreate()
            switchTileLayer(newSource);
        }
    }

    private void changeLanguage(final String language) {
        Settings.setMapLanguage(language);
        mapRestart();
    }

    /**
     * Restart the current activity with the default map source.
     */
    private void mapRestart() {
        mapOptions.mapState = currentMapState();
        finish();
        mapOptions.startIntentWithoutTransition(this, Settings.getMapProvider().getMapClass());
    }

    /**
     * Get the current map state from the map view if it exists or from the mapStateIntent field otherwise.
     *
     * @return the current map state as an array of int, or null if no map state is available
     */
    private MapState currentMapState() {
        if (mapView == null) {
            return null;
        }
        final Geopoint mapCenter = mapView.getViewport().getCenter();
        return new MapState(mapCenter.getCoords(), mapView.getMapZoomLevel(), followMyLocationSwitch, Settings.isShowCircles(), targetGeocode, lastNavTarget, mapOptions.isLiveEnabled, mapOptions.isStoredEnabled);
    }

    private void configureProximityNotifications() {
        // reconfigure, but only if necessary
        proximityNotification = Settings.isGeneralProximityNotificationActive() ? proximityNotification != null ? proximityNotification : new ProximityNotification(true, false) : null;
    }

    private void switchTileLayer(final MapSource newSource) {
        final ITileLayer oldLayer = this.tileLayer;
        ITileLayer newLayer = null;

        mapSource = newSource;

        if (this.mapAttribution != null) {
            this.mapAttribution.setOnClickListener(
                    new MapAttributionDisplayHandler(() -> this.mapSource.calculateMapAttribution(this)));
        }

        if (newSource instanceof AbstractMapsforgeMapSource) {
            newLayer = ((AbstractMapsforgeMapSource) newSource).createTileLayer(tileCache, this.mapView.getModel().mapViewPosition);
        }
        ActivityMixin.invalidateOptionsMenu(this);

        // Exchange layer
        if (newLayer != null) {
            mapView.setZoomLevelMax(newLayer.getZoomLevelMax());
            mapView.setZoomLevelMin(newLayer.getZoomLevelMin());

            // make sure map zoom level is within new zoom level boundaries
            final int currentZoomLevel = mapView.getMapZoomLevel();
            if (currentZoomLevel < newLayer.getZoomLevelMin()) {
                mapView.setMapZoomLevel(newLayer.getZoomLevelMin());
            } else if (currentZoomLevel > newLayer.getZoomLevelMax()) {
                mapView.setMapZoomLevel(newLayer.getZoomLevelMax());
            }

            final Layers layers = this.mapView.getLayerManager().getLayers();
            int index = 0;
            if (oldLayer != null) {
                index = layers.indexOf(oldLayer.getTileLayer()) + 1;
            }
            layers.add(index, newLayer.getTileLayer());
            this.tileLayer = newLayer;
            this.renderThemeHelper.reapplyMapTheme(this.tileLayer, this.tileCache);
            //trigger mapscalebar redraw - otherwise the shown distances will be wrong.
            //See https://github.com/mapsforge/mapsforge/discussions/1313
            this.mapView.getMapScaleBar().setDistanceUnitAdapter(this.mapView.getMapScaleBar().getDistanceUnitAdapter());
            this.tileLayer.onResume();
        } else {
            this.tileLayer = null;
        }

        // Cleanup
        if (oldLayer != null) {
            this.mapView.getLayerManager().getLayers().remove(oldLayer.getTileLayer());
            oldLayer.getTileLayer().onDestroy();
        }
        tileCache.purge();
    }

    private void resumeTileLayer() {
        if (this.tileLayer != null) {
            this.tileLayer.onResume();
        }
    }

    private void pauseTileLayer() {
        if (this.tileLayer != null) {
            this.tileLayer.onPause();
        }
    }

    private boolean tileLayerHasThemes() {
        if (tileLayer != null) {
            return tileLayer.hasThemes();
        }

        return false;
    }

    private void resumeRoute(final boolean force) {
        if (null == individualRoute || force) {
            individualRoute = new IndividualRoute(this::setTarget);
            reloadIndividualRoute();
        } else {
            individualRoute.updateRoute(routeLayer);
        }
    }

    private void resumeTrack(final String key, final boolean preventReloading) {
        if (null == tracks) {
            if (!preventReloading) {
                this.tracks = new Tracks(this.routeTrackUtils, this::setTrack);
            }
        } else {
            final IGeoItemSupplier gdp = tracks.getRoute(key);
            if (trackLayer != null && (gdp == null || gdp instanceof Route)) {
                trackLayer.updateRoute(key, gdp, tracks.getColor(key), tracks.getWidth(key));
            }
            if (null != geoObjectLayer && (gdp == null || gdp instanceof GeoItemHolder)) {
                if (gdp == null || gdp.isHidden()) {
                    geoObjectLayer.removeGeoObjectLayer(key);
                } else {
                    //settings... this will be implemented more beautiful in unified map
                    final float widthFactor = 2f;
                    final float defaultWidth = tracks.getWidth(key) / widthFactor;
                    final int defaultStrokeColor = tracks.getColor(key);
                    final int defaultFillColor = Color.argb(32, Color.red(defaultStrokeColor), Color.green(defaultStrokeColor), Color.blue(defaultStrokeColor));
                    final Func1<Float, Float> widthAdjuster = w -> MapLineUtils.getWidthFromRaw(w == null ? 1 : w.intValue(), false) * widthFactor;

                    final Layer l = GeoObjectLayer.createGeoObjectLayer(gdp.getItem(), geoObjectLayer.getDisplayModel(),
                            defaultWidth, defaultStrokeColor, defaultFillColor, widthAdjuster);
                    geoObjectLayer.addGeoObjectLayer(key, l);
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d("NewMap: onResume");

        resumeTileLayer();
        resumeRoute(Settings.removeFromRouteOnLog());
        if (tracks != null) {
            tracks.resumeAllTracks(this::resumeTrack);
        }
        mapView.getModel().mapViewPosition.addObserver(this);
        MapUtils.updateFilterBar(this, mapOptions.filterContext);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d("NewMap: onStart");

        sheetManageLifecycleOnStart(sheetInfo, newSheetInfo -> sheetInfo = newSheetInfo);
        initializeLayers();
    }

    @Override
    protected void clearSheetInfo() {
        sheetInfo = null;
    }

    private void initializeLayers() {

        switchTileLayer(Settings.getMapSource());

        // History Layer
        this.historyLayer = new HistoryLayer(trailHistory);
        this.mapView.getLayerManager().getLayers().add(this.historyLayer);

        // RouteLayer
        this.routeLayer = new RouteLayer(realRouteDistance -> {
            if (null != this.distanceView) {
                this.distanceView.setRouteDistance(realRouteDistance);
            }
        }, this.mapHandlers.getTapHandler(), mapView.getLayerManager());
        this.mapView.getLayerManager().getLayers().add(this.routeLayer);

        //GeoobjectLayer
        this.geoObjectLayer = new GeoObjectLayer();
        this.mapView.getLayerManager().getLayers().add(this.geoObjectLayer);

        // TrackLayer
        this.trackLayer = new TrackLayer();
        this.mapView.getLayerManager().getLayers().add(this.trackLayer);

        // NavigationLayer
        Geopoint navTarget = lastNavTarget;
        if (navTarget == null) {
            navTarget = mapOptions.coords;
            if (navTarget == null && StringUtils.isNotEmpty(mapOptions.geocode)) {
                final Viewport bounds = DataStore.getBounds(mapOptions.geocode);
                if (bounds != null) {
                    navTarget = bounds.center;
                }
            }
        }
        this.navigationLayer = new NavigationLayer(navTarget, realDistance -> {
            if (null != this.distanceView) {
                this.distanceView.setRealDistance(realDistance);
            }
        });
        this.mapView.getLayerManager().getLayers().add(this.navigationLayer);

        // GeoitemLayer
        GeoitemLayer.resetColors();

        // Coords marker
        this.coordsMarkerLayer = new CoordsMarkerLayer();
        coordsMarkerLayer.setCoordsMarker(coordsMarkerPosition);
        this.mapView.getLayerManager().getLayers().add(this.coordsMarkerLayer);

        // TapHandler
        this.tapHandlerLayer = new TapHandlerLayer(this.mapHandlers.getTapHandler(), this);
        this.mapView.getLayerManager().getLayers().add(this.tapHandlerLayer);

        // Caches bundle
        if (mapOptions.searchResult != null) {
            this.caches = new CachesBundle(this, mapOptions.searchResult, this.mapView, this.mapHandlers);
        } else if (StringUtils.isNotEmpty(mapOptions.geocode)) {
            if (mapOptions.mapMode == MapMode.COORDS && mapOptions.coords != null) {
                this.caches = new CachesBundle(this, mapOptions.coords, mapOptions.waypointType, mapOptions.waypointPrefix, this.mapView, this.mapHandlers, mapOptions.geocode);
            } else {
                this.caches = new CachesBundle(this, mapOptions.geocode, this.mapView, this.mapHandlers);
            }
        } else if (mapOptions.coords != null) {
            this.caches = new CachesBundle(this, mapOptions.coords, mapOptions.waypointType, mapOptions.waypointPrefix, this.mapView, this.mapHandlers, null);
        } else {
            caches = new CachesBundle(this, this.mapView, this.mapHandlers);
        }

        // Stored enabled map
        caches.handleStoredLayers(this, mapOptions);
        // Live enabled map
        caches.handleLiveLayers(this, mapOptions);
        caches.setFilterContext(mapOptions.filterContext);

        // Position layer
        this.positionLayer = new PositionLayer();
        this.mapView.getLayerManager().getLayers().add(positionLayer);

        //Distance view
        this.distanceView = new DistanceView(findViewById(R.id.distanceinfo), navTarget, Settings.isBrouterShowBothDistances());

        //Target view
        this.targetView = new TargetView(findViewById(R.id.target), StringUtils.EMPTY, StringUtils.EMPTY);
        final Geocache target = getCurrentTargetCache();
        if (target != null) {
            targetView.setTarget(target.getShortGeocode(), target.getName());
        }

        // resume location access
        resumeDisposables.add(geoDirUpdate.start(GeoDirHandler.UPDATE_GEODIR));
    }

    @Override
    public void onPause() {
        Log.d("NewMap: onPause");

        savePrefs();

        pauseTileLayer();
        mapView.getModel().mapViewPosition.removeObserver(this);
        super.onPause();
    }

    @Override
    protected void onStop() {
        Log.d("NewMap: onStop");
        terminateLayers();
        super.onStop();
    }

    private void terminateLayers() {

        this.resumeDisposables.clear();

        this.caches.onDestroy();
        this.caches = null;

        this.mapView.getLayerManager().getLayers().remove(this.positionLayer);
        this.positionLayer = null;
        this.mapView.getLayerManager().getLayers().remove(this.navigationLayer);
        this.navigationLayer = null;
        this.mapView.getLayerManager().getLayers().remove(this.routeLayer);
        this.routeLayer = null;
        this.mapView.getLayerManager().getLayers().remove(this.geoObjectLayer);
        this.geoObjectLayer = null;
        this.mapView.getLayerManager().getLayers().remove(this.trackLayer);
        this.trackLayer = null;
        this.mapView.getLayerManager().getLayers().remove(this.historyLayer);
        this.historyLayer = null;

        if (this.tileLayer != null) {
            this.mapView.getLayerManager().getLayers().remove(this.tileLayer.getTileLayer());
            this.tileLayer.getTileLayer().onDestroy();
            this.tileLayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        Log.d("NewMap: onDestroy");
        this.tileCache.destroy();
        this.mapView.getModel().mapViewPosition.destroy();
        this.mapView.destroy();
        ResourceBitmapCacheMonitor.release();

        if (this.mapAttribution != null) {
            this.mapAttribution.setOnClickListener(null);
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBundle(STATE_ROUTETRACKUTILS, routeTrackUtils.getState());

        Log.d("New map: onSaveInstanceState");

        final MapState state = prepareMapState();
        outState.putParcelable(BUNDLE_MAP_STATE, state);
        if (proximityNotification != null) {
            outState.putParcelable(BUNDLE_PROXIMITY_NOTIFICATION, proximityNotification);
        }
        if (mapOptions.filterContext != null) {
            outState.putParcelable(BUNDLE_FILTERCONTEXT, mapOptions.filterContext);
        }
        if (sheetInfo != null) {
            outState.putParcelable(BUNDLE_SHEETINFO, sheetInfo);
        }
    }

    private MapState prepareMapState() {
        return new MapState(MapsforgeUtils.toGeopoint(mapView.getModel().mapViewPosition.getCenter()), mapView.getMapZoomLevel(), followMyLocationSwitch, false, targetGeocode, lastNavTarget, mapOptions.isLiveEnabled, mapOptions.isStoredEnabled);
    }

    private void centerMap(final Geopoint geopoint) {
        mapView.getModel().mapViewPosition.setCenter(new LatLong(geopoint.getLatitude(), geopoint.getLongitude()));
    }

    public Location getCoordinates() {
        final LatLong center = mapView.getModel().mapViewPosition.getCenter();
        final Location loc = new Location("newmap");
        loc.setLatitude(center.latitude);
        loc.setLongitude(center.longitude);
        return loc;
    }

    private void initFollowMyLocation(final boolean followMyLocation) {
        synchronized (inFollowMyLocation) {
            if (inFollowMyLocation[0] > 0) {
                return;
            }
            inFollowMyLocation[0]++;
        }
        Settings.setFollowMyLocation(followMyLocation);
        followMyLocationSwitch = followMyLocation;

        final ImageButton followMyLocationButton = findViewById(R.id.map_followmylocation_btn);
        if (followMyLocationButton != null) { // can be null after screen rotation
            followMyLocationButton.setImageResource(followMyLocation ? R.drawable.map_followmylocation_btn : R.drawable.map_followmylocation_off_btn);
            followMyLocationButton.setOnClickListener(v -> initFollowMyLocation(!followMyLocationSwitch));
        }

        if (followMyLocation) {
            final Location currentLocation = LocationDataProvider.getInstance().currentGeo(); // get location even if none was delivered to the view-model yet
            mapView.setCenter(new LatLong(currentLocation.getLatitude(), currentLocation.getLongitude()));
        }
        synchronized (inFollowMyLocation) {
            inFollowMyLocation[0]--;
        }
    }

    public void triggerLongTapContextMenu(final Point tapXY) {
        if (Settings.isLongTapOnMapActivated()) {
            MapUtils.createMapLongClickPopupMenu(this, new Geopoint(tapHandlerLayer.getLongTapLatLong().latitude, tapHandlerLayer.getLongTapLatLong().longitude),
                            new android.graphics.Point((int) tapXY.x, (int) tapXY.y), individualRoute, routeLayer,
                            this::updateRouteTrackButtonVisibility,
                            getCurrentTargetCache(), mapOptions, this::setTarget)
                    .setOnDismissListener(menu -> tapHandlerLayer.resetLongTapLatLong())
                    .show();
        }
    }

    @Override
    public void onReceiveTargetUpdate(final TargetInfo targetInfo) {
        if (Settings.isAutotargetIndividualRoute()) {
            Settings.setAutotargetIndividualRoute(false);
            ViewUtils.showShortToast(this, R.string.map_disable_autotarget_individual_route);
        }
        setTarget(targetInfo.coords, targetInfo.geocode);
    }

    private static final class DisplayHandler extends Handler {

        @NonNull
        private final WeakReference<NewMap> mapRef;

        DisplayHandler(@NonNull final NewMap map) {
            this.mapRef = new WeakReference<>(map);
        }

        @Override
        public void handleMessage(@NonNull final Message msg) {
            final NewMap map = mapRef.get();
            if (map == null) {
                return;
            }
            if (msg.what == UPDATE_TITLE) {
                map.setTitle();
                map.setSubtitle();
            }
        }

    }

    private void setTitle() {
        final String title = calculateTitle();

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(MapUtils.getColoredValue(title));
        }
    }

    @NonNull
    private String calculateTitle() {
        if (mapOptions.isLiveEnabled) {
            return res.getString(R.string.map_live);
        }
        if (mapOptions.mapMode == MapMode.SINGLE) {
            final Geocache cache = getSingleModeCache();
            if (cache != null) {
                return cache.getName();
            }
        }
        return StringUtils.defaultIfEmpty(mapOptions.title, res.getString(R.string.map_offline));
    }

    private void setSubtitle() {
        final String subtitle = calculateSubtitle();
        if (StringUtils.isEmpty(subtitle)) {
            return;
        }

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setSubtitle(MapUtils.getColoredValue(subtitle));
        }
    }

    @NonNull
    private String calculateSubtitle() {
        if (!mapOptions.isLiveEnabled && mapOptions.mapMode == MapMode.SINGLE) {
            final Geocache cache = getSingleModeCache();
            if (cache != null) {
                return Formatter.formatMapSubtitle(cache);
            }
            return "";
        }

        // count caches in the sub title
        final int visible = countVisibleCaches();
        final int total = countTotalCaches();

        final StringBuilder subtitle = new StringBuilder();
        if (visible != total && Settings.isDebug()) {
            subtitle.append(visible).append('/').append(res.getQuantityString(R.plurals.cache_counts, total, total));
        } else {
            subtitle.append(res.getQuantityString(R.plurals.cache_counts, visible, visible));
        }

        return subtitle.toString();
    }

    private int countVisibleCaches() {
        return caches != null ? caches.getVisibleCachesCount() : 0;
    }

    private int countTotalCaches() {
        return caches != null ? caches.getCachesCount() : 0;
    }

    /**
     * Updates the progress.
     */
    private static final class ShowProgressHandler extends Handler {
        private int counter = 0;

        @NonNull
        private final WeakReference<NewMap> mapRef;

        ShowProgressHandler(@NonNull final NewMap map) {
            this.mapRef = new WeakReference<>(map);
        }

        @Override
        public void handleMessage(final Message msg) {
            final int what = msg.what;

            if (what == HIDE_PROGRESS) {
                if (--counter == 0) {
                    showProgress(false);
                }
            } else if (what == SHOW_PROGRESS) {
                showProgress(true);
                counter++;
            }
        }

        private void showProgress(final boolean show) {
            final NewMap map = mapRef.get();
            if (map == null) {
                return;
            }
            map.spinner.setVisibility(show ? View.VISIBLE : View.GONE);
        }

    }

    // class: update location
    private static class UpdateLoc extends GeoDirHandler {
        // use the following constants for fine tuning - find good compromise between smooth updates and as less updates as possible

        // minimum time in milliseconds between position overlay updates
        private static final long MIN_UPDATE_INTERVAL = 500;
        // minimum change of heading in grad for position overlay update
        private static final float MIN_HEADING_DELTA = 15f;
        // minimum change of location in fraction of map width/height (whatever is smaller) for position overlay update
        private static final float MIN_LOCATION_DELTA = 0.01f;

        @NonNull
        Location currentLocation = LocationDataProvider.getInstance().currentGeo();
        float currentHeading;

        private long timeLastPositionOverlayCalculation = 0;
        private long timeLastDistanceCheck = 0;
        /**
         * weak reference to the outer class
         */
        @NonNull
        private final WeakReference<NewMap> mapRef;

        UpdateLoc(@NonNull final NewMap map) {
            mapRef = new WeakReference<>(map);
        }

        @Override
        public void updateGeoDir(@NonNull final cgeo.geocaching.sensors.GeoData geo, final float dir) {
            currentLocation = geo;
            currentHeading = AngleUtils.getDirectionNow(dir);
            repaintPositionOverlay();
        }

        @NonNull
        public Location getCurrentLocation() {
            return currentLocation;
        }

        /**
         * Repaint position overlay but only with a max frequency and if position or heading changes sufficiently.
         */
        void repaintPositionOverlay() {
            final long currentTimeMillis = System.currentTimeMillis();
            if (currentTimeMillis > (timeLastPositionOverlayCalculation + MIN_UPDATE_INTERVAL)) {
                timeLastPositionOverlayCalculation = currentTimeMillis;

                try {
                    final NewMap map = mapRef.get();
                    if (map != null) {
                        final boolean needsRepaintForDistanceOrAccuracy = needsRepaintForDistanceOrAccuracy();
                        final boolean needsRepaintForHeading = needsRepaintForHeading();

                        if (needsRepaintForDistanceOrAccuracy && NewMap.followMyLocationSwitch) {
                            map.centerMap(new Geopoint(currentLocation));
                        }

                        if (needsRepaintForDistanceOrAccuracy || needsRepaintForHeading) {

                            map.historyLayer.setCoordinates(currentLocation);
                            map.navigationLayer.setCoordinates(currentLocation);
                            map.distanceView.setCoordinates(currentLocation);
                            map.distanceView.showRouteDistance();
                            map.positionLayer.setCoordinates(currentLocation);
                            map.positionLayer.setHeading(currentHeading);
                            map.positionLayer.requestRedraw();

                            if (null != map.proximityNotification && (timeLastDistanceCheck == 0 || currentTimeMillis > (timeLastDistanceCheck + MIN_UPDATE_INTERVAL))) {
                                map.proximityNotification.checkDistance(map.caches.getClosestDistanceInM(new Geopoint(currentLocation.getLatitude(), currentLocation.getLongitude())));
                                timeLastDistanceCheck = System.currentTimeMillis();
                            }
                        }
                    }
                } catch (final RuntimeException e) {
                    Log.w("Failed to update location", e);
                }
            }
        }

        boolean needsRepaintForHeading() {
            final NewMap map = mapRef.get();
            if (map == null) {
                return false;
            }
            return Math.abs(AngleUtils.difference(currentHeading, map.positionLayer.getHeading())) > MIN_HEADING_DELTA;
        }

        boolean needsRepaintForDistanceOrAccuracy() {
            final NewMap map = mapRef.get();
            if (map == null) {
                return false;
            }
            final Location lastLocation = map.getCoordinates();

            float dist = Float.MAX_VALUE;
            if (lastLocation != null) {
                if (lastLocation.getAccuracy() != currentLocation.getAccuracy()) {
                    return true;
                }
                dist = currentLocation.distanceTo(lastLocation);
            }

            final float[] mapDimension = new float[1];
            if (map.mapView.getWidth() < map.mapView.getHeight()) {
                final double span = map.mapView.getLongitudeSpan() / 1e6;
                Location.distanceBetween(currentLocation.getLatitude(), currentLocation.getLongitude(), currentLocation.getLatitude(), currentLocation.getLongitude() + span, mapDimension);
            } else {
                final double span = map.mapView.getLatitudeSpan() / 1e6;
                Location.distanceBetween(currentLocation.getLatitude(), currentLocation.getLongitude(), currentLocation.getLatitude() + span, currentLocation.getLongitude(), mapDimension);
            }

            return dist > (mapDimension[0] * MIN_LOCATION_DELTA);
        }
    }

    private static class DragHandler implements OnMapDragListener {

        @NonNull
        private final WeakReference<NewMap> mapRef;

        DragHandler(@NonNull final NewMap parent) {
            mapRef = new WeakReference<>(parent);
        }

        @Override
        public void onDrag() {
            final NewMap map = mapRef.get();
            if (map != null && NewMap.followMyLocationSwitch) {
                NewMap.followMyLocationSwitch = false;
                map.initFollowMyLocation(false);
            }
        }
    }

    public void showSelection(@NonNull final List<GeoitemRef> items, final boolean longPressMode) {
        if (items.isEmpty() && !longPressMode) {
            if (sheetRemoveFragment()) {
                return;
            }
            HideActionBarUtils.toggleActionBar(this);
        }
        if (items.isEmpty()) {
            return;
        }

        if (items.size() == 1) {
            if (longPressMode) {
                if (Settings.isLongTapOnMapActivated()) {
                    triggerCacheWaypointLongTapContextMenu(items.get(0));
                }
            } else {
                showPopup(items.get(0));
            }
            return;
        }
        try {
            final ArrayList<GeoitemRef> sorted = new ArrayList<>(items);
            Collections.sort(sorted, GeoitemRef.NAME_COMPARATOR);

            final SimpleDialog.ItemSelectModel<GeoitemRef> model = new SimpleDialog.ItemSelectModel<>();
            model
                .setItems(sorted)
                .setDisplayViewMapper((item, itemGroup, ctx, view, parent) ->
                        GeoItemSelectorUtils.createGeoItemView(NewMap.this, item, GeoItemSelectorUtils.getOrCreateView(NewMap.this, view, parent)),
                        (item, itemGroup) -> item.getName() + "::" + item.getGeocode())
                .setItemPadding(0);

            SimpleDialog.of(this).setTitle(R.string.map_select_multiple_items).selectSingle(model, item -> {
                if (longPressMode) {
                    if (Settings.isLongTapOnMapActivated()) {
                        triggerCacheWaypointLongTapContextMenu(item);
                    }
                } else {
                    showPopup(item);
                }
            });

        } catch (final NotFoundException e) {
            Log.e("NewMap.showSelection", e);
        }
    }

    private void triggerCacheWaypointLongTapContextMenu(final GeoitemRef item) {
        final long mapSize = MercatorProjection.getMapSize(mapView.getModel().mapViewPosition.getZoomLevel(), mapView.getModel().displayModel.getTileSize());
        Geopoint geopoint = null;
        if (item.getType() == CoordinateType.CACHE) {
            final Geocache cache = DataStore.loadCache(item.getGeocode(), LoadFlags.LOAD_CACHE_OR_DB);
            if (cache != null) {
                geopoint = cache.getCoords();
            }
        } else {
            final Waypoint waypoint = DataStore.loadWaypoint(item.getId());
            if (waypoint != null) {
                geopoint = waypoint.getCoords();
            }
        }
        if (geopoint == null) {
            geopoint = mapView.getViewport().center;
        }

        final RouteItem routeItem = new RouteItem(item);
        if (Settings.isShowRouteMenu()) {
            final int tapX = (int) (MercatorProjection.longitudeToPixelX(geopoint.getLongitude(), mapSize) - MercatorProjection.longitudeToPixelX(mapView.getViewport().bottomLeft.getLongitude(), mapSize));
            final int tapY = (int) (MercatorProjection.latitudeToPixelY(geopoint.getLatitude(), mapSize) - MercatorProjection.latitudeToPixelY(mapView.getViewport().topRight.getLatitude(), mapSize));
            MapUtils.createCacheWaypointLongClickPopupMenu(this, routeItem, tapX, tapY, individualRoute, routeLayer, this::updateRouteTrackButtonVisibility)
                    .setOnDismissListener(menu -> tapHandlerLayer.resetLongTapLatLong())
                    .show();
        } else {
            toggleRouteItem(item);
        }
    }

    private void showPopup(final GeoitemRef item) {
        if (item == null || StringUtils.isEmpty(item.getGeocode())) {
            return;
        }

        try {
            if (item.getType() == CoordinateType.CACHE) {
                final Geocache cache = DataStore.loadCache(item.getGeocode(), LoadFlags.LOAD_CACHE_OR_DB);
                if (cache != null) {
                    popupGeocodes.add(cache.getGeocode());
                    sheetInfo = new UnifiedMapViewModel.SheetInfo(cache.getGeocode(), 0);
                    sheetShowDetails(sheetInfo);
                    return;
                }
                return;
            }

            if (item.getType() == CoordinateType.WAYPOINT && item.getId() >= 0) {
                popupGeocodes.add(item.getGeocode());
                sheetInfo = new UnifiedMapViewModel.SheetInfo(item.getGeocode(), item.getId());
                sheetShowDetails(sheetInfo);
            }

        } catch (final NotFoundException e) {
            Log.e("NewMap.showPopup", e);
        }
    }

    private void toggleRouteItem(final GeoitemRef item) {
        if (item == null || StringUtils.isEmpty(item.getGeocode())) {
            return;
        }
        if (individualRoute == null) {
            individualRoute = new IndividualRoute(this::setTarget);
        }
        individualRoute.toggleItem(this, new RouteItem(item), routeLayer);
        distanceView.showRouteDistance();
        updateRouteTrackButtonVisibility();
    }

    public void toggleRouteItem(final LatLong latLong) {
        if (individualRoute == null) {
            individualRoute = new IndividualRoute(this::setTarget);
        }
        individualRoute.toggleItem(this, new RouteItem(new Geopoint(latLong.latitude, latLong.longitude)), routeLayer);
        distanceView.showRouteDistance();
        updateRouteTrackButtonVisibility();
    }

    @Nullable
    private Geocache getSingleModeCache() {
        if (StringUtils.isNotBlank(mapOptions.geocode)) {
            return DataStore.loadCache(mapOptions.geocode, LoadFlags.LOAD_CACHE_OR_DB);
        }
        return null;
    }

    @Nullable
    private Geocache getCurrentTargetCache() {
        if (StringUtils.isNotBlank(targetGeocode)) {
            return DataStore.loadCache(targetGeocode, LoadFlags.LOAD_CACHE_OR_DB);
        }
        return null;
    }

    private void setTarget(final Geopoint coords, final String geocode) {
        lastNavTarget = coords;
        if (StringUtils.isNotBlank(geocode)) {
            targetGeocode = geocode;
            final Geocache target = getCurrentTargetCache();
            targetView.setTarget(targetGeocode, target != null ? target.getName() : StringUtils.EMPTY);
            if (lastNavTarget == null && target != null) {
                lastNavTarget = target.getCoords();
            }
        } else {
            targetGeocode = null;
            targetView.setTarget(null, null);
        }
        if (navigationLayer != null) {
            navigationLayer.setDestination(lastNavTarget);
            navigationLayer.requestRedraw();
        }
        if (distanceView != null) {
            distanceView.setDestination(lastNavTarget);
            distanceView.setCoordinates(geoDirUpdate.getCurrentLocation());
        }

        ActivityMixin.invalidateOptionsMenu(this);
    }

    private void savePrefs() {
        Settings.setMapZoom(this.mapMode, mapView.getMapZoomLevel());
        Settings.setMapCenter(new MapsforgeGeoPoint(mapView.getModel().mapViewPosition.getCenter()));
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == AbstractDialogFragment.REQUEST_CODE_TARGET_INFO) {
            if (resultCode == AbstractDialogFragment.RESULT_CODE_SET_TARGET) {
                final TargetInfo targetInfo = data.getExtras().getParcelable(Intents.EXTRA_TARGET_INFO);
                if (targetInfo != null) {
                    if (Settings.isAutotargetIndividualRoute()) {
                        Settings.setAutotargetIndividualRoute(false);
                        ViewUtils.showShortToast(this, R.string.map_disable_autotarget_individual_route);
                    }
                    setTarget(targetInfo.coords, targetInfo.geocode);
                }
            }
            final List<String> changedGeocodes = new ArrayList<>();
            String geocode = popupGeocodes.poll();
            while (geocode != null) {
                changedGeocodes.add(geocode);
                geocode = popupGeocodes.poll();
            }
            if (caches != null) {
                caches.invalidate(changedGeocodes);
            }
        }
        if (requestCode == GeocacheFilterActivity.REQUEST_SELECT_FILTER && resultCode == Activity.RESULT_OK) {
            mapOptions.filterContext = data.getParcelableExtra(EXTRA_FILTER_CONTEXT);
            refreshMapData(false, true);
        }

        this.routeTrackUtils.onActivityResult(requestCode, resultCode, data);
    }

    private void setTrack(final String key, final IGeoItemSupplier route, final int unused1, final int unused2) {
        tracks.setRoute(key, route);
        resumeTrack(key, null == route);
        updateRouteTrackButtonVisibility();
    }

    private void reloadIndividualRoute() {
        if (individualRoute != null) {
            individualRoute.reloadRoute(this::reloadIndividualRouteFollowUp);
        }
    }

    private void reloadIndividualRouteFollowUp(final IndividualRoute route) {
        if (null != routeLayer) {
            routeLayer.updateIndividualRoute(route);
            updateRouteTrackButtonVisibility();
        } else {
            // try again in 0.25 seconds
            new Handler(Looper.getMainLooper()).postDelayed(() -> reloadIndividualRouteFollowUp(route), 250);
        }
    }

    private void updateRouteTrackButtonVisibility() {
        routeTrackUtils.updateRouteTrackButtonVisibility(findViewById(R.id.container_individualroute), individualRoute, tracks);
    }

    private Boolean isTargetSet() {
        return /* StringUtils.isNotBlank(targetGeocode) && */ null != lastNavTarget;
    }

    private static class ResourceBitmapCacheMonitor {

        private static int refCount = 0;

        static synchronized void addRef() {
            refCount++;
            Log.d("ResourceBitmapCacheMonitor.addRef");
        }

        static synchronized void release() {
            if (refCount > 0) {
                refCount--;
                Log.d("ResourceBitmapCacheMonitor.release");
                if (refCount == 0) {
                    Log.d("ResourceBitmapCacheMonitor.clearResourceBitmaps");
                    AndroidResourceBitmap.clearResourceBitmaps();
                }
            }
        }

    }

    public boolean getLastCompactIconMode() {
        return lastCompactIconMode;
    }

    public boolean checkCompactIconMode(final int overlayId, final int newCount) {
        boolean newCompactIconMode = lastCompactIconMode;
        if (null != caches) {
            newCompactIconMode = CompactIconModeUtils.forceCompactIconMode(caches.getVisibleCachesCount(overlayId, newCount));
            if (lastCompactIconMode != newCompactIconMode) {
                lastCompactIconMode = newCompactIconMode;
                // @todo Exchanging & redrawing the icons would be sufficient, do not have to invalidate everything!
                caches.invalidateAll(overlayId); // redraw all icons except for the given overlay
            }
        }
        return newCompactIconMode;
    }

    // get notified for viewport changes (zoom/pan)
    public void onChange() {
        final Viewport newViewport = mapView.getViewport();
        if (!newViewport.equals(lastViewport)) {
            lastViewport = newViewport;
            checkCompactIconMode(NO_OVERLAY_ID, 0);
        }
    }

    public static class MapAttributionDisplayHandler implements View.OnClickListener {

        private Supplier<ImmutablePair<String, Boolean>> attributionSupplier;
        private ImmutablePair<String, Boolean> attributionPair;

        public MapAttributionDisplayHandler(final Supplier<ImmutablePair<String, Boolean>> attributionSupplier) {
            this.attributionSupplier = attributionSupplier;
        }

        @Override
        public void onClick(final View v) {

            if (this.attributionPair == null) {
                this.attributionPair = attributionSupplier.get();
                if (this.attributionPair == null || this.attributionPair.left == null) {
                    this.attributionPair = new ImmutablePair<>("---", false);
                }
                this.attributionSupplier = null; //prevent possible memory leaks
            }
            displayMapAttribution(v.getContext(), this.attributionPair.left, this.attributionPair.right);
        }
    }

    private static void displayMapAttribution(final Context ctx, final String attribution, final boolean linkify) {

        //create text message
        CharSequence message = HtmlCompat.fromHtml(attribution, HtmlCompat.FROM_HTML_MODE_LEGACY);
        if (linkify) {
            final SpannableString s = new SpannableString(message);
            ViewUtils.safeAddLinks(s, Linkify.ALL);
            message = s;
        }

        final AlertDialog alertDialog = Dialogs.newBuilder(ctx)
                .setTitle(ctx.getString(R.string.map_source_attribution_dialog_title))
                .setCancelable(true)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (dialog, pos) -> dialog.dismiss())
                .create();
        alertDialog.show();

        // Make the URLs in TextView clickable. Must be called after show()
        // Note: we do NOT use the "setView()" option of AlertDialog because this screws up the layout
        ((TextView) alertDialog.findViewById(android.R.id.message)).setMovementMethod(LinkMovementMethod.getInstance());

    }


}
