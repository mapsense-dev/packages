// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.googlemaps;

import static io.flutter.plugins.googlemaps.Convert.clusterToPigeon;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMapOptions;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.Polygon;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.maps.android.clustering.Cluster;
import com.google.maps.android.clustering.ClusterManager;
import com.google.maps.android.collections.MarkerManager;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.platform.PlatformView;
import io.flutter.plugins.googlemaps.Messages.FlutterError;
import io.flutter.plugins.googlemaps.Messages.MapsApi;
import io.flutter.plugins.googlemaps.Messages.MapsCallbackApi;
import io.flutter.plugins.googlemaps.Messages.MapsInspectorApi;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

// method call handler
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;

//map controller
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;

//kml
import io.flutter.plugin.platform.PlatformView;
import com.google.android.gms.maps.CameraUpdate;
import com.google.maps.android.data.kml.KmlContainer;
import com.google.maps.android.data.kml.KmlLayer;
import com.google.maps.android.data.kml.KmlPlacemark;
import com.google.maps.android.data.kml.KmlPolygon;
import com.google.maps.android.data.kml.KmlPoint;
import com.google.maps.android.data.kml.KmlLineString;
import com.google.maps.android.data.kml.KmlMultiGeometry;
import org.xmlpull.v1.XmlPullParserException;

// reading a file
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.stream.Collectors;

//geojson
import com.google.maps.android.data.geojson.GeoJsonFeature;
import com.google.maps.android.data.geojson.GeoJsonLineString;
import com.google.maps.android.data.geojson.GeoJsonLayer;
import com.google.maps.android.data.geojson.GeoJsonMultiPolygon;
import com.google.maps.android.data.geojson.GeoJsonPoint;
import com.google.maps.android.data.geojson.GeoJsonPolygon;
import org.json.JSONObject;
import org.json.JSONException;

//heatmaps
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.maps.android.heatmaps.HeatmapTileProvider;
import com.google.maps.android.heatmaps.WeightedLatLng;

//clustering
import com.google.maps.android.clustering.ClusterManager;
import com.google.maps.android.clustering.view.DefaultClusterRenderer;
import com.google.maps.android.clustering.ClusterItem;
import io.flutter.plugins.googlemaps.models.MyItem;

/** Controller of a single GoogleMaps MapView instance. */
class GoogleMapController
    implements ActivityPluginBinding.OnSaveInstanceStateListener,
        ClusterManager.OnClusterItemClickListener<MarkerBuilder>,
        ClusterManagersController.OnClusterItemRendered<MarkerBuilder>,
        DefaultLifecycleObserver,
        GoogleMapListener,
        GoogleMapOptionsSink,
        MapsApi,
        MapsInspectorApi,
        OnMapReadyCallback,
        MethodChannel.MethodCallHandler,
        PlatformView {

  private static final String TAG = "GoogleMapController";
  private final int id;
  private final MapsCallbackApi flutterApi;
  private final BinaryMessenger binaryMessenger;
  private final GoogleMapOptions options;
  @Nullable private MapView mapView;
  @Nullable private GoogleMap googleMap;
  private boolean trackCameraPosition = false;
  private boolean myLocationEnabled = false;
  private boolean myLocationButtonEnabled = false;
  private boolean zoomControlsEnabled = true;
  private boolean indoorEnabled = true;
  private boolean trafficEnabled = false;
  private boolean buildingsEnabled = true;
  private boolean disposed = false;
  @VisibleForTesting final float density;
  private @Nullable Messages.VoidResult mapReadyResult;
  private final Context context;
  private final LifecycleProvider lifecycleProvider;
  private final MarkersController markersController;
  private final ClusterManagersController clusterManagersController;
  private final PolygonsController polygonsController;
  private final PolylinesController polylinesController;
  private final CirclesController circlesController;
  private final HeatmapsController heatmapsController;
  private final TileOverlaysController tileOverlaysController;
  private MarkerManager markerManager;
  private MarkerManager.Collection markerCollection;
  private @Nullable List<Messages.PlatformMarker> initialMarkers;
  private @Nullable List<Messages.PlatformClusterManager> initialClusterManagers;
  private @Nullable List<Messages.PlatformPolygon> initialPolygons;
  private @Nullable List<Messages.PlatformPolyline> initialPolylines;
  private @Nullable List<Messages.PlatformCircle> initialCircles;
  private @Nullable List<Messages.PlatformHeatmap> initialHeatmaps;
  private @Nullable List<Messages.PlatformTileOverlay> initialTileOverlays;
  // Null except between initialization and onMapReady.
  private @Nullable String initialMapStyle;
  private boolean lastSetStyleSucceeded;
  @VisibleForTesting List<Float> initialPadding;
  private String CHANNEL = "plugins.flutter.dev/google_maps_android_0";

  GoogleMapController(
      int id,
      Context context,
      BinaryMessenger binaryMessenger,
      LifecycleProvider lifecycleProvider,
      GoogleMapOptions options) {
    this.id = id;
    this.context = context;
    this.options = options;
    this.mapView = new MapView(context, options);
    this.density = context.getResources().getDisplayMetrics().density;
    this.binaryMessenger = binaryMessenger;
    flutterApi = new MapsCallbackApi(binaryMessenger, Integer.toString(id));
    MapsApi.setUp(binaryMessenger, Integer.toString(id), this);
    MapsInspectorApi.setUp(binaryMessenger, Integer.toString(id), this);
    AssetManager assetManager = context.getAssets();
     // these lines are added for the native method channel to communicate with flutter app
     MethodChannel methodChannel = new MethodChannel(binaryMessenger, CHANNEL);
     methodChannel.setMethodCallHandler(this);
    this.lifecycleProvider = lifecycleProvider;
    this.clusterManagersController = new ClusterManagersController(flutterApi, context);
    this.markersController =
        new MarkersController(
            flutterApi,
            clusterManagersController,
            assetManager,
            density,
            new Convert.BitmapDescriptorFactoryWrapper());
    this.polygonsController = new PolygonsController(flutterApi, density);
    this.polylinesController = new PolylinesController(flutterApi, assetManager, density);
    this.circlesController = new CirclesController(flutterApi, density);
    this.heatmapsController = new HeatmapsController();
    this.tileOverlaysController = new TileOverlaysController(flutterApi);
  }

  // Constructor for testing purposes only
  @VisibleForTesting
  GoogleMapController(
      int id,
      Context context,
      BinaryMessenger binaryMessenger,
      MapsCallbackApi flutterApi,
      LifecycleProvider lifecycleProvider,
      GoogleMapOptions options,
      ClusterManagersController clusterManagersController,
      MarkersController markersController,
      PolygonsController polygonsController,
      PolylinesController polylinesController,
      CirclesController circlesController,
      HeatmapsController heatmapController,
      TileOverlaysController tileOverlaysController) {
    this.id = id;
    this.context = context;
    this.binaryMessenger = binaryMessenger;
    this.flutterApi = flutterApi;
    this.options = options;
    this.mapView = new MapView(context, options);
    this.density = context.getResources().getDisplayMetrics().density;
    this.lifecycleProvider = lifecycleProvider;
    this.clusterManagersController = clusterManagersController;
    this.markersController = markersController;
    this.polygonsController = polygonsController;
    this.polylinesController = polylinesController;
    this.circlesController = circlesController;
    this.heatmapsController = heatmapController;
    this.tileOverlaysController = tileOverlaysController;
  }

  @Override
  public View getView() {
    return mapView;
  }

  @VisibleForTesting
  /* package */ void setView(MapView view) {
    mapView = view;
  }

  void init() {
    lifecycleProvider.getLifecycle().addObserver(this);
    mapView.getMapAsync(this);
  }

  @Override
  public void onMapReady(@NonNull GoogleMap googleMap) {
    this.googleMap = googleMap;
    this.googleMap.setIndoorEnabled(this.indoorEnabled);
    this.googleMap.setTrafficEnabled(this.trafficEnabled);
    this.googleMap.setBuildingsEnabled(this.buildingsEnabled);
    installInvalidator();
    if (mapReadyResult != null) {
      mapReadyResult.success();
      mapReadyResult = null;
    }
    setGoogleMapListener(this);
    markerManager = new MarkerManager(googleMap);
    markerCollection = markerManager.newCollection();
    updateMyLocationSettings();
    markersController.setCollection(markerCollection);
    clusterManagersController.init(googleMap, markerManager);
    polygonsController.setGoogleMap(googleMap);
    polylinesController.setGoogleMap(googleMap);
    circlesController.setGoogleMap(googleMap);
    heatmapsController.setGoogleMap(googleMap);
    tileOverlaysController.setGoogleMap(googleMap);
    setMarkerCollectionListener(this);
    setClusterItemClickListener(this);
    setClusterItemRenderedListener(this);
    updateInitialClusterManagers();
    updateInitialMarkers();
    updateInitialPolygons();
    updateInitialPolylines();
    updateInitialCircles();
    updateInitialHeatmaps();
    updateInitialTileOverlays();
    if (initialPadding != null && initialPadding.size() == 4) {
      setPadding(
          initialPadding.get(0),
          initialPadding.get(1),
          initialPadding.get(2),
          initialPadding.get(3));
    }
    if (initialMapStyle != null) {
      updateMapStyle(initialMapStyle);
      initialMapStyle = null;
    }
  }

  // Returns the first TextureView found in the view hierarchy.
  private static TextureView findTextureView(ViewGroup group) {
    final int n = group.getChildCount();
    for (int i = 0; i < n; i++) {
      View view = group.getChildAt(i);
      if (view instanceof TextureView) {
        return (TextureView) view;
      }
      if (view instanceof ViewGroup) {
        TextureView r = findTextureView((ViewGroup) view);
        if (r != null) {
          return r;
        }
      }
    }
    return null;
  }

  private void installInvalidator() {
    if (mapView == null) {
      // This should only happen in tests.
      return;
    }
    TextureView textureView = findTextureView(mapView);
    if (textureView == null) {
      Log.i(TAG, "No TextureView found. Likely using the LEGACY renderer.");
      return;
    }
    Log.i(TAG, "Installing custom TextureView driven invalidator.");
    SurfaceTextureListener internalListener = textureView.getSurfaceTextureListener();
    // Override the Maps internal SurfaceTextureListener with our own. Our listener
    // mostly just invokes the internal listener callbacks but in onSurfaceTextureUpdated
    // the mapView is invalidated which ensures that all map updates are presented to the
    // screen.
    final MapView mapView = this.mapView;
    textureView.setSurfaceTextureListener(
        new TextureView.SurfaceTextureListener() {
          public void onSurfaceTextureAvailable(
              @NonNull SurfaceTexture surface, int width, int height) {
            if (internalListener != null) {
              internalListener.onSurfaceTextureAvailable(surface, width, height);
            }
          }

          public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            if (internalListener != null) {
              return internalListener.onSurfaceTextureDestroyed(surface);
            }
            return true;
          }

          public void onSurfaceTextureSizeChanged(
              @NonNull SurfaceTexture surface, int width, int height) {
            if (internalListener != null) {
              internalListener.onSurfaceTextureSizeChanged(surface, width, height);
            }
          }

          public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            if (internalListener != null) {
              internalListener.onSurfaceTextureUpdated(surface);
            }
            mapView.invalidate();
          }
        });
  }
  // added method channel communications and layer functions
  private KmlLayer currentKmlLayer = null;
  private GeoJsonLayer currentGeoJsonLayer = null;
  private TileOverlay heatmapOverlay = null;
  private ClusterManager<MyItem> clusterManager = null;

  @Override
  public void onMethodCall(MethodCall call, MethodChannel.Result result) {

    switch (call.method) {
      case "map#addKML": {
        int resourceId = call.argument("resourceId");
        addKMLLayer(resourceId);
        result.success(null);
        break;
      }
      case "map#addGeoJSON": {
        int resourceId = call.argument("resourceId");
        addGeoJSON(resourceId);
        result.success(null);
        break;
      }
      case "map#addHeatMap": {
        int resourceId = call.argument("resourceId");
        addKmlHeatmap(resourceId);
        addGeoJSONHeatmap(resourceId);
        break;
      }
      case "map#addClustering": {
        int resourceId = call.argument("resourceId");
        addGeoJSONClustering(resourceId);
        addKmlClustering(resourceId);
        break;
      }
      case "map#removeLayers": {
        if (currentKmlLayer != null) {
          currentKmlLayer.removeLayerFromMap();
          currentKmlLayer = null;
        }
        // Remove GeoJSON layer if present
        if (currentGeoJsonLayer != null) {
          currentGeoJsonLayer.removeLayerFromMap();
          currentGeoJsonLayer = null;
        }
        if (heatmapOverlay != null) {
          heatmapOverlay.remove();
          heatmapOverlay = null; // Reset the variable to avoid trying to remove it again
        }
        if (clusterManager != null) {
          clusterManager.clearItems();
          clusterManager.cluster();
          clusterManager = null;
        }
        break;
      }
      default:
        result.notImplemented();

    }
  }
  // KML layer functions //
  // function to add KML layer to the screen
  private void addKMLLayer(int resourceId) {
    try {
      currentKmlLayer = new KmlLayer(googleMap, resourceId, context);
      Log.d("ADDKML", "KML layer fetched");
      currentKmlLayer.addLayerToMap();
      Log.d("ADDKML", "KML layer added to the map");
      moveCameraToKml(currentKmlLayer);

    } catch (XmlPullParserException | IOException e) {
      e.printStackTrace();
    }
  }

  // move camera to the layer on map
  private void moveCameraToKml(KmlLayer kmlLayer) {
    if (kmlLayer == null || googleMap == null) {
      return;
    }

    try {
      LatLngBounds.Builder builder = new LatLngBounds.Builder();
      boolean hasGeometry = false;

      // Process all containers recursively
      for (KmlContainer container : kmlLayer.getContainers()) {
        hasGeometry = processContainer(container, builder) || hasGeometry;
      }

      if (hasGeometry) {
        LatLngBounds bounds = builder.build();
        Log.d("Bounds", "Southwest: " + bounds.southwest + ", Northeast: " + bounds.northeast);

        int width = context.getResources().getDisplayMetrics().widthPixels;
        int height = context.getResources().getDisplayMetrics().heightPixels;

        googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, width, height, 1));
      } else {
        Log.d("moveCameraToKml", "No geometry found in KML to move camera.");
      }

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  // process any multi-layer kml data
  private boolean processContainer(KmlContainer container, LatLngBounds.Builder builder) {
    boolean hasGeometry = false;

    for (KmlPlacemark placemark : container.getPlacemarks()) {
      if (placemark.getGeometry() instanceof KmlPolygon) {
        KmlPolygon polygon = (KmlPolygon) placemark.getGeometry();
        for (LatLng latLng : polygon.getOuterBoundaryCoordinates()) {
          builder.include(latLng);
          hasGeometry = true;
        }
      } else if (placemark.getGeometry() instanceof KmlPoint) {
        KmlPoint point = (KmlPoint) placemark.getGeometry();
        builder.include(point.getGeometryObject());
        // googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(point.getGeometryObject(),
        // 15));
        hasGeometry = true;
      } else if (placemark.getGeometry() instanceof KmlLineString) {
        KmlLineString lineString = (KmlLineString) placemark.getGeometry();
        for (LatLng latLng : lineString.getGeometryObject()) {
          builder.include(latLng);
          hasGeometry = true;
        }
      } else if (placemark.getGeometry() instanceof KmlMultiGeometry) {
        // Handle MultiGeometry
        KmlMultiGeometry multiGeometry = (KmlMultiGeometry) placemark.getGeometry();
        for (Object geometry : multiGeometry.getGeometryObject()) {
          if (geometry instanceof KmlPolygon) {
            KmlPolygon polygon = (KmlPolygon) geometry;
            for (LatLng latLng : polygon.getOuterBoundaryCoordinates()) {
              Log.d("processContainer", "MultiGeometry Polygon Point: " + latLng.toString());
              builder.include(latLng);
              hasGeometry = true;
            }
          } else if (geometry instanceof KmlPoint) {
            KmlPoint point = (KmlPoint) geometry;
            Log.d("processContainer", "MultiGeometry Point: " + point.getGeometryObject().toString());
            builder.include(point.getGeometryObject());
            hasGeometry = true;
          } else if (geometry instanceof KmlLineString) {
            KmlLineString lineString = (KmlLineString) geometry;
            for (LatLng latLng : lineString.getGeometryObject()) {
              Log.d("processContainer", "MultiGeometry LineString Point: " + latLng.toString());
              builder.include(latLng);
              hasGeometry = true;
            }
          }
        }
      } else {
        Log.d("processContainer", "Unknown geometry type: " + placemark.getGeometry().getGeometryType());
      }
    }

    // Recursively process nested containers
    for (KmlContainer nestedContainer : container.getContainers()) {
      hasGeometry = processContainer(nestedContainer, builder) || hasGeometry;

    }

    return hasGeometry;
  }

  // GEOJSON layer functions //

  // move maps camera to the layer
  private void moveCameraToGeoJson(GeoJsonLayer geoJsonLayer) {
    try {
      LatLngBounds.Builder builder = new LatLngBounds.Builder();
      boolean hasGeometry = false;

      // Iterate through features in the GeoJsonLayer
      for (GeoJsonFeature feature : geoJsonLayer.getFeatures()) {
        if (feature.getGeometry() != null) {
          if (feature.getGeometry() instanceof GeoJsonPoint) {
            GeoJsonPoint point = (GeoJsonPoint) feature.getGeometry();
            builder.include(point.getCoordinates());
            hasGeometry = true;
          } else if (feature.getGeometry() instanceof GeoJsonPolygon) {
            GeoJsonPolygon polygon = (GeoJsonPolygon) feature.getGeometry();
            for (List<LatLng> outerBoundary : polygon.getCoordinates()) {
              for (LatLng latLng : outerBoundary) {
                builder.include(latLng);
              }
            }
            hasGeometry = true;
          } else if (feature.getGeometry() instanceof GeoJsonLineString) {
            GeoJsonLineString lineString = (GeoJsonLineString) feature.getGeometry();
            for (LatLng latLng : lineString.getCoordinates()) {
              builder.include(latLng);
            }
            hasGeometry = true;
          } else if (feature.getGeometry() instanceof GeoJsonMultiPolygon) {
            GeoJsonMultiPolygon multiPolygon = (GeoJsonMultiPolygon) feature.getGeometry();
            for (GeoJsonPolygon polygon : multiPolygon.getPolygons()) {
              for (List<LatLng> outerBoundary : polygon.getCoordinates()) {
                for (LatLng latLng : outerBoundary) {
                  builder.include(latLng);
                }
              }
            }
            hasGeometry = true;
          }
        }
      }

      if (hasGeometry) {
        LatLngBounds bounds = builder.build();
        int width = context.getResources().getDisplayMetrics().widthPixels;
        int height = context.getResources().getDisplayMetrics().heightPixels;

        // Create CameraUpdate to fit all geometries within bounds
        CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, width, height, 0);

        // Move the camera to the GeoJSON layer bounds
        googleMap.animateCamera(cameraUpdate);
      } else {
        Log.d("moveCameraToGeoJson", "No geometry found in GeoJSON to move camera.");
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  // fetch GeoJSON resource
  private String loadGeoJsonFromResource(int resourceId) throws IOException {
    InputStream inputStream = context.getResources().openRawResource(resourceId);
    StringBuilder builder = new StringBuilder();
    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
    String line;
    while ((line = reader.readLine()) != null) {
      builder.append(line);
    }
    reader.close();
    return builder.toString();
  }

  // add GeoJSON layer to the map
  private void addGeoJSON(int resourceId) {
    try {
      String geoJsonData = loadGeoJsonFromResource(resourceId);
      JSONObject geoJsonObject = new JSONObject(geoJsonData);
      currentGeoJsonLayer = new GeoJsonLayer(googleMap, geoJsonObject);
      currentGeoJsonLayer.addLayerToMap();
      moveCameraToGeoJson(currentGeoJsonLayer);
    } catch (IOException | JSONException e) {
      e.printStackTrace();

    }
  }

  // HeatMap function //
  // ->KML HeatMap functions
  private void addKmlHeatmap(int resId) {
    try {
      KmlLayer kmlLayer = new KmlLayer(googleMap, resId, context);
      kmlLayer.addLayerToMap();
      Log.d("Heatmap", "KML file loaded successfully.");

      List<LatLng> points = new ArrayList<>();
      extractKmlGeometries(kmlLayer, points);

      if (points.isEmpty()) {
        Log.d("Heatmap", "No points found in KML for heatmap.");
        return;
      }

      HeatmapTileProvider heatmapTileProvider = new HeatmapTileProvider.Builder()
          .data(points)
          .build();
      moveCameraToKml(kmlLayer);
      kmlLayer.removeLayerFromMap();

      heatmapOverlay = googleMap.addTileOverlay(new TileOverlayOptions().tileProvider(heatmapTileProvider));
      Log.d("Heatmap", "Heatmap added to the map.");
    } catch (Exception e) {
      Log.e("Heatmap", "Error adding KML heatmap", e);
    }
  }

  private void extractKmlGeometries(KmlLayer kmlLayer, List<LatLng> points) {
    if (kmlLayer != null) {
      for (KmlContainer container : kmlLayer.getContainers()) {
        if (container != null) {
          Log.d("addKML", "Extracting geometries from container: " + container.getContainerId());
          extractGeometriesFromContainer(container, points);
        } else {
          Log.d("addKML", "Container is null.");
        }
      }
    } else {
      Log.d("addKML", "KmlLayer is null.");
    }
  }

  private void extractGeometriesFromContainer(KmlContainer container, List<LatLng> points) {
    // Extract geometries from the container
    for (KmlPlacemark placemark : container.getPlacemarks()) {
      if (placemark != null && placemark.getGeometry() instanceof KmlPoint) {
        KmlPoint point = (KmlPoint) placemark.getGeometry();
        LatLng latLng = point.getGeometryObject();
        if (latLng != null) {
          points.add(latLng);
          Log.d("Heatmap", "Added point to heatmap: " + latLng.toString());
        } else {
          Log.d("Heatmap", "Point geometry is null.");
        }
      } else {
        Log.d("Heatmap", "Geometry is not a KmlPoint or placemark is null.");
      }
    }

    // Recursively handle nested containers
    for (KmlContainer nestedContainer : container.getContainers()) {
      extractGeometriesFromContainer(nestedContainer, points);
    }
  }

  // ->GeoJSON Heatmap functions
  private void addGeoJSONHeatmap(int resId) {

    ArrayList<WeightedLatLng> heatmapPoints = new ArrayList<>();
    try {
      String geoJsonData = loadGeoJsonFromResource(resId);
      JSONObject geoJsonObject = new JSONObject(geoJsonData);
      GeoJsonLayer geoJsonLayer = new GeoJsonLayer(googleMap, geoJsonObject);
      geoJsonLayer.addLayerToMap();
      for (GeoJsonFeature feature : geoJsonLayer.getFeatures()) {
        if (feature.getGeometry() != null) {
          if (feature.getGeometry() instanceof GeoJsonPoint) {
            GeoJsonPoint point = (GeoJsonPoint) feature.getGeometry();
            heatmapPoints.add(new WeightedLatLng(point.getCoordinates()));

          } else if (feature.getGeometry() instanceof GeoJsonPolygon) {
            GeoJsonPolygon polygon = (GeoJsonPolygon) feature.getGeometry();
            for (List<LatLng> outerBoundary : polygon.getCoordinates()) {
              for (LatLng latLng : outerBoundary) {
                heatmapPoints.add(new WeightedLatLng(latLng));
              }
            }

          } else if (feature.getGeometry() instanceof GeoJsonLineString) {
            GeoJsonLineString lineString = (GeoJsonLineString) feature.getGeometry();
            for (LatLng latLng : lineString.getCoordinates()) {
              heatmapPoints.add(new WeightedLatLng(latLng));
            }

          } else if (feature.getGeometry() instanceof GeoJsonMultiPolygon) {
            GeoJsonMultiPolygon multiPolygon = (GeoJsonMultiPolygon) feature.getGeometry();
            for (GeoJsonPolygon polygon : multiPolygon.getPolygons()) {
              for (List<LatLng> outerBoundary : polygon.getCoordinates()) {
                for (LatLng latLng : outerBoundary) {
                  heatmapPoints.add(new WeightedLatLng(latLng));
                }
              }
            }

          }
        }
      }
      HeatmapTileProvider heatmapTileProvider = new HeatmapTileProvider.Builder()
          .weightedData(heatmapPoints)
          .radius(50) // Customize the radius
          .build();
      moveCameraToGeoJson(geoJsonLayer);
      geoJsonLayer.removeLayerFromMap();
      heatmapOverlay = googleMap.addTileOverlay(new TileOverlayOptions().tileProvider(heatmapTileProvider));

    } catch (Exception e) {
      e.printStackTrace();
    }

  }

  // Clustering functions //
  // ->KML clustering functions
  private void addKmlClustering(int resId) {
    try {
      KmlLayer kmlLayer = new KmlLayer(googleMap, resId, context);
      kmlLayer.addLayerToMap();
      clusterManager = new ClusterManager<>(context, googleMap);
      googleMap.setOnCameraIdleListener(clusterManager);
      googleMap.setOnMarkerClickListener(clusterManager);

      List<LatLng> points = new ArrayList<>();
      extractKmlGeometries(kmlLayer, points);
      if (points.isEmpty()) {
        Log.d("addKmlClustering", "No points found in KML for Clustering.");
        return;
      }
      moveCameraToKml(kmlLayer);
      kmlLayer.removeLayerFromMap();

      for (LatLng point : points) {
        clusterManager.addItem(new MyItem(point.latitude, point.longitude, "", ""));
      }

      clusterManager.cluster(); // Perform clustering

    } catch (Exception e) {
      Log.d("addKmlClustering", "Error processing KML: " + e.getMessage());
      e.printStackTrace();
    }
  }

  // -> GeoJSON Clustering function
  private void addGeoJSONClustering(int resId) {
    try {
      String geoJsonData = loadGeoJsonFromResource(resId);
      JSONObject geoJsonObject = new JSONObject(geoJsonData);
      GeoJsonLayer geoJsonLayer = new GeoJsonLayer(googleMap, geoJsonObject);
      geoJsonLayer.addLayerToMap();
      clusterManager = new ClusterManager<>(context, googleMap);
      googleMap.setOnCameraIdleListener(clusterManager);
      googleMap.setOnMarkerClickListener(clusterManager);

      List<LatLng> points = new ArrayList<>();
      for (GeoJsonFeature feature : geoJsonLayer.getFeatures()) {
        if (feature.getGeometry() != null) {
          if (feature.getGeometry() instanceof GeoJsonPoint) {
            GeoJsonPoint point = (GeoJsonPoint) feature.getGeometry();
            points.add(point.getCoordinates());

          } else if (feature.getGeometry() instanceof GeoJsonPolygon) {
            GeoJsonPolygon polygon = (GeoJsonPolygon) feature.getGeometry();
            for (List<LatLng> outerBoundary : polygon.getCoordinates()) {
              for (LatLng latLng : outerBoundary) {
                points.add(latLng);
              }
            }

          } else if (feature.getGeometry() instanceof GeoJsonLineString) {
            GeoJsonLineString lineString = (GeoJsonLineString) feature.getGeometry();
            for (LatLng latLng : lineString.getCoordinates()) {
              points.add(latLng);
            }

          } else if (feature.getGeometry() instanceof GeoJsonMultiPolygon) {
            GeoJsonMultiPolygon multiPolygon = (GeoJsonMultiPolygon) feature.getGeometry();
            for (GeoJsonPolygon polygon : multiPolygon.getPolygons()) {
              for (List<LatLng> outerBoundary : polygon.getCoordinates()) {
                for (LatLng latLng : outerBoundary) {
                  points.add(latLng);
                }
              }
            }

          }
        }
      }
      // code to fetch points for heatmap
      for (LatLng point : points) {
        clusterManager.addItem(new MyItem(point.latitude, point.longitude, "", ""));
      }
      moveCameraToGeoJson(geoJsonLayer);
      geoJsonLayer.removeLayerFromMap();
      clusterManager.cluster(); // Perform clustering
    } catch (Exception e) {
      e.printStackTrace();
    }

  }

    


  @Override
  public void onMapClick(@NonNull LatLng latLng) {
    flutterApi.onTap(Convert.latLngToPigeon(latLng), new NoOpVoidResult());
  }

  @Override
  public void onMapLongClick(@NonNull LatLng latLng) {
    flutterApi.onLongPress(Convert.latLngToPigeon(latLng), new NoOpVoidResult());
  }

  @Override
  public void onCameraMoveStarted(int reason) {
    flutterApi.onCameraMoveStarted(new NoOpVoidResult());
  }

  @Override
  public void onInfoWindowClick(Marker marker) {
    markersController.onInfoWindowTap(marker.getId());
  }

  @Override
  public void onCameraMove() {
    if (!trackCameraPosition) {
      return;
    }
    flutterApi.onCameraMove(
        Convert.cameraPositionToPigeon(googleMap.getCameraPosition()), new NoOpVoidResult());
  }

  @Override
  public void onCameraIdle() {
    clusterManagersController.onCameraIdle();
    flutterApi.onCameraIdle(new NoOpVoidResult());
  }

  @Override
  public boolean onMarkerClick(Marker marker) {
    return markersController.onMapsMarkerTap(marker.getId());
  }

  @Override
  public void onMarkerDragStart(Marker marker) {
    markersController.onMarkerDragStart(marker.getId(), marker.getPosition());
  }

  @Override
  public void onMarkerDrag(Marker marker) {
    markersController.onMarkerDrag(marker.getId(), marker.getPosition());
  }

  @Override
  public void onMarkerDragEnd(Marker marker) {
    markersController.onMarkerDragEnd(marker.getId(), marker.getPosition());
  }

  @Override
  public void onPolygonClick(Polygon polygon) {
    polygonsController.onPolygonTap(polygon.getId());
  }

  @Override
  public void onPolylineClick(Polyline polyline) {
    polylinesController.onPolylineTap(polyline.getId());
  }

  @Override
  public void onCircleClick(Circle circle) {
    circlesController.onCircleTap(circle.getId());
  }

  @Override
  public void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    MapsApi.setUp(binaryMessenger, Integer.toString(id), null);
    MapsInspectorApi.setUp(binaryMessenger, Integer.toString(id), null);
    setGoogleMapListener(null);
    setMarkerCollectionListener(null);
    setClusterItemClickListener(null);
    setClusterItemRenderedListener(null);
    destroyMapViewIfNecessary();
    Lifecycle lifecycle = lifecycleProvider.getLifecycle();
    if (lifecycle != null) {
      lifecycle.removeObserver(this);
    }
  }

  private void setGoogleMapListener(@Nullable GoogleMapListener listener) {
    if (googleMap == null) {
      Log.v(TAG, "Controller was disposed before GoogleMap was ready.");
      return;
    }
    googleMap.setOnCameraMoveStartedListener(listener);
    googleMap.setOnCameraMoveListener(listener);
    googleMap.setOnCameraIdleListener(listener);
    googleMap.setOnPolygonClickListener(listener);
    googleMap.setOnPolylineClickListener(listener);
    googleMap.setOnCircleClickListener(listener);
    googleMap.setOnMapClickListener(listener);
    googleMap.setOnMapLongClickListener(listener);
  }

  @VisibleForTesting
  public void setMarkerCollectionListener(@Nullable GoogleMapListener listener) {
    if (googleMap == null) {
      Log.v(TAG, "Controller was disposed before GoogleMap was ready.");
      return;
    }

    markerCollection.setOnMarkerClickListener(listener);
    markerCollection.setOnMarkerDragListener(listener);
    markerCollection.setOnInfoWindowClickListener(listener);
  }

  @VisibleForTesting
  public void setClusterItemClickListener(
      @Nullable ClusterManager.OnClusterItemClickListener<MarkerBuilder> listener) {
    if (googleMap == null) {
      Log.v(TAG, "Controller was disposed before GoogleMap was ready.");
      return;
    }

    clusterManagersController.setClusterItemClickListener(listener);
  }

  @VisibleForTesting
  public void setClusterItemRenderedListener(
      @Nullable ClusterManagersController.OnClusterItemRendered<MarkerBuilder> listener) {
    if (googleMap == null) {
      Log.v(TAG, "Controller was disposed before GoogleMap was ready.");
      return;
    }

    clusterManagersController.setClusterItemRenderedListener(listener);
  }

  // DefaultLifecycleObserver

  @Override
  public void onCreate(@NonNull LifecycleOwner owner) {
    if (disposed) {
      return;
    }
    mapView.onCreate(null);
  }

  @Override
  public void onStart(@NonNull LifecycleOwner owner) {
    if (disposed) {
      return;
    }
    mapView.onStart();
  }

  @Override
  public void onResume(@NonNull LifecycleOwner owner) {
    if (disposed) {
      return;
    }
    mapView.onResume();
  }

  @Override
  public void onPause(@NonNull LifecycleOwner owner) {
    if (disposed) {
      return;
    }
    mapView.onResume();
  }

  @Override
  public void onStop(@NonNull LifecycleOwner owner) {
    if (disposed) {
      return;
    }
    mapView.onStop();
  }

  @Override
  public void onDestroy(@NonNull LifecycleOwner owner) {
    owner.getLifecycle().removeObserver(this);
    if (disposed) {
      return;
    }
    destroyMapViewIfNecessary();
  }

  @Override
  public void onRestoreInstanceState(Bundle bundle) {
    if (disposed) {
      return;
    }
    mapView.onCreate(bundle);
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle bundle) {
    if (disposed) {
      return;
    }
    mapView.onSaveInstanceState(bundle);
  }

  // GoogleMapOptionsSink methods

  @Override
  public void setCameraTargetBounds(LatLngBounds bounds) {
    googleMap.setLatLngBoundsForCameraTarget(bounds);
  }

  @Override
  public void setCompassEnabled(boolean compassEnabled) {
    googleMap.getUiSettings().setCompassEnabled(compassEnabled);
  }

  @Override
  public void setMapToolbarEnabled(boolean mapToolbarEnabled) {
    googleMap.getUiSettings().setMapToolbarEnabled(mapToolbarEnabled);
  }

  @Override
  public void setMapType(int mapType) {
    googleMap.setMapType(mapType);
  }

  @Override
  public void setTrackCameraPosition(boolean trackCameraPosition) {
    this.trackCameraPosition = trackCameraPosition;
  }

  @Override
  public void setRotateGesturesEnabled(boolean rotateGesturesEnabled) {
    googleMap.getUiSettings().setRotateGesturesEnabled(rotateGesturesEnabled);
  }

  @Override
  public void setScrollGesturesEnabled(boolean scrollGesturesEnabled) {
    googleMap.getUiSettings().setScrollGesturesEnabled(scrollGesturesEnabled);
  }

  @Override
  public void setTiltGesturesEnabled(boolean tiltGesturesEnabled) {
    googleMap.getUiSettings().setTiltGesturesEnabled(tiltGesturesEnabled);
  }

  @Override
  public void setMinMaxZoomPreference(Float min, Float max) {
    googleMap.resetMinMaxZoomPreference();
    if (min != null) {
      googleMap.setMinZoomPreference(min);
    }
    if (max != null) {
      googleMap.setMaxZoomPreference(max);
    }
  }

  @Override
  public void setPadding(float top, float left, float bottom, float right) {
    if (googleMap != null) {
      googleMap.setPadding(
          (int) (left * density),
          (int) (top * density),
          (int) (right * density),
          (int) (bottom * density));
    } else {
      setInitialPadding(top, left, bottom, right);
    }
  }

  @VisibleForTesting
  void setInitialPadding(float top, float left, float bottom, float right) {
    if (initialPadding == null) {
      initialPadding = new ArrayList<>();
    } else {
      initialPadding.clear();
    }
    initialPadding.add(top);
    initialPadding.add(left);
    initialPadding.add(bottom);
    initialPadding.add(right);
  }

  @Override
  public void setZoomGesturesEnabled(boolean zoomGesturesEnabled) {
    googleMap.getUiSettings().setZoomGesturesEnabled(zoomGesturesEnabled);
  }

  /** This call will have no effect on already created map */
  @Override
  public void setLiteModeEnabled(boolean liteModeEnabled) {
    options.liteMode(liteModeEnabled);
  }

  @Override
  public void setMyLocationEnabled(boolean myLocationEnabled) {
    if (this.myLocationEnabled == myLocationEnabled) {
      return;
    }
    this.myLocationEnabled = myLocationEnabled;
    if (googleMap != null) {
      updateMyLocationSettings();
    }
  }

  @Override
  public void setMyLocationButtonEnabled(boolean myLocationButtonEnabled) {
    if (this.myLocationButtonEnabled == myLocationButtonEnabled) {
      return;
    }
    this.myLocationButtonEnabled = myLocationButtonEnabled;
    if (googleMap != null) {
      updateMyLocationSettings();
    }
  }

  @Override
  public void setZoomControlsEnabled(boolean zoomControlsEnabled) {
    if (this.zoomControlsEnabled == zoomControlsEnabled) {
      return;
    }
    this.zoomControlsEnabled = zoomControlsEnabled;
    if (googleMap != null) {
      googleMap.getUiSettings().setZoomControlsEnabled(zoomControlsEnabled);
    }
  }

  @Override
  public void setInitialMarkers(@NonNull List<Messages.PlatformMarker> initialMarkers) {
    this.initialMarkers = initialMarkers;
    if (googleMap != null) {
      updateInitialMarkers();
    }
  }

  private void updateInitialMarkers() {
    if (initialMarkers != null) {
      markersController.addMarkers(initialMarkers);
    }
  }

  @Override
  public void setInitialClusterManagers(
      @NonNull List<Messages.PlatformClusterManager> initialClusterManagers) {
    this.initialClusterManagers = initialClusterManagers;
    if (googleMap != null) {
      updateInitialClusterManagers();
    }
  }

  private void updateInitialClusterManagers() {
    if (initialClusterManagers != null) {
      clusterManagersController.addClusterManagers(initialClusterManagers);
    }
  }

  @Override
  public void setInitialPolygons(@NonNull List<Messages.PlatformPolygon> initialPolygons) {
    this.initialPolygons = initialPolygons;
    if (googleMap != null) {
      updateInitialPolygons();
    }
  }

  private void updateInitialPolygons() {
    if (initialPolygons != null) {
      polygonsController.addPolygons(initialPolygons);
    }
  }

  @Override
  public void setInitialPolylines(@NonNull List<Messages.PlatformPolyline> initialPolylines) {
    this.initialPolylines = initialPolylines;
    if (googleMap != null) {
      updateInitialPolylines();
    }
  }

  private void updateInitialPolylines() {
    if (initialPolylines != null) {
      polylinesController.addPolylines(initialPolylines);
    }
  }

  @Override
  public void setInitialCircles(@NonNull List<Messages.PlatformCircle> initialCircles) {
    this.initialCircles = initialCircles;
    if (googleMap != null) {
      updateInitialCircles();
    }
  }

  @Override
  public void setInitialHeatmaps(@NonNull List<Messages.PlatformHeatmap> initialHeatmaps) {
    this.initialHeatmaps = initialHeatmaps;
    if (googleMap != null) {
      updateInitialHeatmaps();
    }
  }

  private void updateInitialCircles() {
    if (initialCircles != null) {
      circlesController.addCircles(initialCircles);
    }
  }

  private void updateInitialHeatmaps() {
    if (initialHeatmaps != null) {
      heatmapsController.addHeatmaps(initialHeatmaps);
    }
  }

  @Override
  public void setInitialTileOverlays(
      @NonNull List<Messages.PlatformTileOverlay> initialTileOverlays) {
    this.initialTileOverlays = initialTileOverlays;
    if (googleMap != null) {
      updateInitialTileOverlays();
    }
  }

  private void updateInitialTileOverlays() {
    if (initialTileOverlays != null) {
      tileOverlaysController.addTileOverlays(initialTileOverlays);
    }
  }

  @SuppressLint("MissingPermission")
  private void updateMyLocationSettings() {
    if (hasLocationPermission()) {
      // The plugin doesn't add the location permission by default so that apps that don't need
      // the feature won't require the permission.
      // Gradle is doing a static check for missing permission and in some configurations will
      // fail the build if the permission is missing. The following disables the Gradle lint.
      // noinspection ResourceType
      googleMap.setMyLocationEnabled(myLocationEnabled);
      googleMap.getUiSettings().setMyLocationButtonEnabled(myLocationButtonEnabled);
    } else {
      // TODO(amirh): Make the options update fail.
      // https://github.com/flutter/flutter/issues/24327
      Log.e(TAG, "Cannot enable MyLocation layer as location permissions are not granted");
    }
  }

  private boolean hasLocationPermission() {
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  private int checkSelfPermission(String permission) {
    if (permission == null) {
      throw new IllegalArgumentException("permission is null");
    }
    return context.checkPermission(
        permission, android.os.Process.myPid(), android.os.Process.myUid());
  }

  private void destroyMapViewIfNecessary() {
    if (mapView == null) {
      return;
    }
    mapView.onDestroy();
    mapView = null;
  }

  public void setIndoorEnabled(boolean indoorEnabled) {
    this.indoorEnabled = indoorEnabled;
  }

  public void setTrafficEnabled(boolean trafficEnabled) {
    this.trafficEnabled = trafficEnabled;
    if (googleMap == null) {
      return;
    }
    googleMap.setTrafficEnabled(trafficEnabled);
  }

  public void setBuildingsEnabled(boolean buildingsEnabled) {
    this.buildingsEnabled = buildingsEnabled;
  }

  @Override
  public void onClusterItemRendered(@NonNull MarkerBuilder markerBuilder, @NonNull Marker marker) {
    markersController.onClusterItemRendered(markerBuilder, marker);
  }

  @Override
  public boolean onClusterItemClick(MarkerBuilder item) {
    return markersController.onMarkerTap(item.markerId());
  }

  public void setMapStyle(@Nullable String style) {
    if (googleMap == null) {
      initialMapStyle = style;
    } else {
      updateMapStyle(style);
    }
  }

  private boolean updateMapStyle(String style) {
    // Dart passes an empty string to indicate that the style should be cleared.
    final MapStyleOptions mapStyleOptions =
        style == null || style.isEmpty() ? null : new MapStyleOptions(style);
    lastSetStyleSucceeded = Objects.requireNonNull(googleMap).setMapStyle(mapStyleOptions);
    return lastSetStyleSucceeded;
  }

  /** MapsApi implementation */
  @Override
  public void waitForMap(@NonNull Messages.VoidResult result) {
    if (googleMap == null) {
      mapReadyResult = result;
    } else {
      result.success();
    }
  }

  @Override
  public void updateMapConfiguration(@NonNull Messages.PlatformMapConfiguration configuration) {
    Convert.interpretMapConfiguration(configuration, this);
  }

  @Override
  public void updateCircles(
      @NonNull List<Messages.PlatformCircle> toAdd,
      @NonNull List<Messages.PlatformCircle> toChange,
      @NonNull List<String> idsToRemove) {
    circlesController.addCircles(toAdd);
    circlesController.changeCircles(toChange);
    circlesController.removeCircles(idsToRemove);
  }

  @Override
  public void updateHeatmaps(
      @NonNull List<Messages.PlatformHeatmap> toAdd,
      @NonNull List<Messages.PlatformHeatmap> toChange,
      @NonNull List<String> idsToRemove) {
    heatmapsController.addHeatmaps(toAdd);
    heatmapsController.changeHeatmaps(toChange);
    heatmapsController.removeHeatmaps(idsToRemove);
  }

  @Override
  public void updateClusterManagers(
      @NonNull List<Messages.PlatformClusterManager> toAdd, @NonNull List<String> idsToRemove) {
    clusterManagersController.addClusterManagers(toAdd);
    clusterManagersController.removeClusterManagers(idsToRemove);
  }

  @Override
  public void updateMarkers(
      @NonNull List<Messages.PlatformMarker> toAdd,
      @NonNull List<Messages.PlatformMarker> toChange,
      @NonNull List<String> idsToRemove) {
    markersController.addMarkers(toAdd);
    markersController.changeMarkers(toChange);
    markersController.removeMarkers(idsToRemove);
  }

  @Override
  public void updatePolygons(
      @NonNull List<Messages.PlatformPolygon> toAdd,
      @NonNull List<Messages.PlatformPolygon> toChange,
      @NonNull List<String> idsToRemove) {
    polygonsController.addPolygons(toAdd);
    polygonsController.changePolygons(toChange);
    polygonsController.removePolygons(idsToRemove);
  }

  @Override
  public void updatePolylines(
      @NonNull List<Messages.PlatformPolyline> toAdd,
      @NonNull List<Messages.PlatformPolyline> toChange,
      @NonNull List<String> idsToRemove) {
    polylinesController.addPolylines(toAdd);
    polylinesController.changePolylines(toChange);
    polylinesController.removePolylines(idsToRemove);
  }

  @Override
  public void updateTileOverlays(
      @NonNull List<Messages.PlatformTileOverlay> toAdd,
      @NonNull List<Messages.PlatformTileOverlay> toChange,
      @NonNull List<String> idsToRemove) {
    tileOverlaysController.addTileOverlays(toAdd);
    tileOverlaysController.changeTileOverlays(toChange);
    tileOverlaysController.removeTileOverlays(idsToRemove);
  }

  @Override
  public @NonNull Messages.PlatformPoint getScreenCoordinate(
      @NonNull Messages.PlatformLatLng latLng) {
    if (googleMap == null) {
      throw new FlutterError(
          "GoogleMap uninitialized",
          "getScreenCoordinate called prior to map initialization",
          null);
    }
    Point screenLocation =
        googleMap.getProjection().toScreenLocation(Convert.latLngFromPigeon(latLng));
    return Convert.pointToPigeon(screenLocation);
  }

  @Override
  public @NonNull Messages.PlatformLatLng getLatLng(
      @NonNull Messages.PlatformPoint screenCoordinate) {
    if (googleMap == null) {
      throw new FlutterError(
          "GoogleMap uninitialized", "getLatLng called prior to map initialization", null);
    }
    LatLng latLng =
        googleMap.getProjection().fromScreenLocation(Convert.pointFromPigeon(screenCoordinate));
    return Convert.latLngToPigeon(latLng);
  }

  @Override
  public @NonNull Messages.PlatformLatLngBounds getVisibleRegion() {
    if (googleMap == null) {
      throw new FlutterError(
          "GoogleMap uninitialized", "getVisibleRegion called prior to map initialization", null);
    }
    LatLngBounds latLngBounds = googleMap.getProjection().getVisibleRegion().latLngBounds;
    return Convert.latLngBoundsToPigeon(latLngBounds);
  }

  @Override
  public void moveCamera(@NonNull Messages.PlatformCameraUpdate cameraUpdate) {
    if (googleMap == null) {
      throw new FlutterError(
          "GoogleMap uninitialized", "moveCamera called prior to map initialization", null);
    }
    googleMap.moveCamera(Convert.toCameraUpdate(cameraUpdate.getJson(), density));
  }

  @Override
  public void animateCamera(@NonNull Messages.PlatformCameraUpdate cameraUpdate) {
    if (googleMap == null) {
      throw new FlutterError(
          "GoogleMap uninitialized", "animateCamera called prior to map initialization", null);
    }
    googleMap.animateCamera(Convert.toCameraUpdate(cameraUpdate.getJson(), density));
  }

  @Override
  public @NonNull Double getZoomLevel() {
    if (googleMap == null) {
      throw new FlutterError(
          "GoogleMap uninitialized", "getZoomLevel called prior to map initialization", null);
    }
    return (double) googleMap.getCameraPosition().zoom;
  }

  @Override
  public void showInfoWindow(@NonNull String markerId) {
    markersController.showMarkerInfoWindow(markerId);
  }

  @Override
  public void hideInfoWindow(@NonNull String markerId) {
    markersController.hideMarkerInfoWindow(markerId);
  }

  @NonNull
  @Override
  public Boolean isInfoWindowShown(@NonNull String markerId) {
    return markersController.isInfoWindowShown(markerId);
  }

  @Override
  public @NonNull Boolean setStyle(@NonNull String style) {
    return updateMapStyle(style);
  }

  @Override
  public @NonNull Boolean didLastStyleSucceed() {
    return lastSetStyleSucceeded;
  }

  @Override
  public void clearTileCache(@NonNull String tileOverlayId) {
    tileOverlaysController.clearTileCache(tileOverlayId);
  }

  @Override
  public void takeSnapshot(@NonNull Messages.Result<byte[]> result) {
    if (googleMap == null) {
      result.error(new FlutterError("GoogleMap uninitialized", "takeSnapshot", null));
    } else {
      googleMap.snapshot(
          bitmap -> {
            if (bitmap == null) {
              result.error(new FlutterError("Snapshot failure", "Unable to take snapshot", null));
            } else {
              ByteArrayOutputStream stream = new ByteArrayOutputStream();
              bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
              byte[] byteArray = stream.toByteArray();
              bitmap.recycle();
              result.success(byteArray);
            }
          });
    }
  }

  /** MapsInspectorApi implementation */
  @Override
  public @NonNull Boolean areBuildingsEnabled() {
    return Objects.requireNonNull(googleMap).isBuildingsEnabled();
  }

  @Override
  public @NonNull Boolean areRotateGesturesEnabled() {
    return Objects.requireNonNull(googleMap).getUiSettings().isRotateGesturesEnabled();
  }

  @Override
  public @NonNull Boolean areZoomControlsEnabled() {
    return Objects.requireNonNull(googleMap).getUiSettings().isZoomControlsEnabled();
  }

  @Override
  public @NonNull Boolean areScrollGesturesEnabled() {
    return Objects.requireNonNull(googleMap).getUiSettings().isScrollGesturesEnabled();
  }

  @Override
  public @NonNull Boolean areTiltGesturesEnabled() {
    return Objects.requireNonNull(googleMap).getUiSettings().isTiltGesturesEnabled();
  }

  @Override
  public @NonNull Boolean areZoomGesturesEnabled() {
    return Objects.requireNonNull(googleMap).getUiSettings().isZoomGesturesEnabled();
  }

  @Override
  public @NonNull Boolean isCompassEnabled() {
    return Objects.requireNonNull(googleMap).getUiSettings().isCompassEnabled();
  }

  @Override
  public Boolean isLiteModeEnabled() {
    return options.getLiteMode();
  }

  @Override
  public @NonNull Boolean isMapToolbarEnabled() {
    return Objects.requireNonNull(googleMap).getUiSettings().isMapToolbarEnabled();
  }

  @Override
  public @NonNull Boolean isMyLocationButtonEnabled() {
    return Objects.requireNonNull(googleMap).getUiSettings().isMyLocationButtonEnabled();
  }

  @Override
  public @NonNull Boolean isTrafficEnabled() {
    return Objects.requireNonNull(googleMap).isTrafficEnabled();
  }

  @Override
  public @Nullable Messages.PlatformTileLayer getTileOverlayInfo(@NonNull String tileOverlayId) {
    TileOverlay tileOverlay = tileOverlaysController.getTileOverlay(tileOverlayId);
    if (tileOverlay == null) {
      return null;
    }
    return new Messages.PlatformTileLayer.Builder()
        .setFadeIn(tileOverlay.getFadeIn())
        .setTransparency((double) tileOverlay.getTransparency())
        .setZIndex((double) tileOverlay.getZIndex())
        .setVisible(tileOverlay.isVisible())
        .build();
  }

  @Override
  public @NonNull Messages.PlatformZoomRange getZoomRange() {
    return new Messages.PlatformZoomRange.Builder()
        .setMin((double) Objects.requireNonNull(googleMap).getMinZoomLevel())
        .setMax((double) Objects.requireNonNull(googleMap).getMaxZoomLevel())
        .build();
  }

  @Override
  public @NonNull List<Messages.PlatformCluster> getClusters(@NonNull String clusterManagerId) {
    Set<? extends Cluster<MarkerBuilder>> clusters =
        clusterManagersController.getClustersWithClusterManagerId(clusterManagerId);
    List<Messages.PlatformCluster> data = new ArrayList<>(clusters.size());
    for (Cluster<MarkerBuilder> cluster : clusters) {
      data.add(clusterToPigeon(clusterManagerId, cluster));
    }
    return data;
  }
}
