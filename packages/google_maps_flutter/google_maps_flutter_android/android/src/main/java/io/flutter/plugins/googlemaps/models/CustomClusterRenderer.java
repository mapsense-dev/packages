package io.flutter.plugins.googlemaps.models;

//googlemaps
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMapOptions; 

//clustering
import com.google.maps.android.clustering.ClusterManager;
import com.google.maps.android.clustering.view.DefaultClusterRenderer;
import com.google.maps.android.clustering.ClusterItem;
import io.flutter.plugins.googlemaps.models.MyItem;

//custom icons
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.BitmapDescriptor;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.util.Log;


public class CustomClusterRenderer<T extends ClusterItem> extends DefaultClusterRenderer<T> {

    private final Context context;
  
    public CustomClusterRenderer(Context context, GoogleMap map, ClusterManager<T> clusterManager) {
      super(context, map, clusterManager);
      this.context = context;
    }
  
  
  private BitmapDescriptor getCustomMarkerIcon(String assetPath) {
    try {
  
      Bitmap bitmap = BitmapFactory.decodeStream(context.getAssets().open(assetPath));
      int defaultMarkerSize = (int) (30 *
          context.getResources().getDisplayMetrics().density);
  
      // Resize the bitmap to match the default marker size
      Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, defaultMarkerSize,
          defaultMarkerSize, false);
  
      // Return resized custom marker icon
      return BitmapDescriptorFactory.fromBitmap(resizedBitmap);
  
    } catch (Exception e) {
      e.printStackTrace();
      Log.d("CustomMarker", e.toString());
      // Return a default marker if custom marker fails
      return BitmapDescriptorFactory.defaultMarker();
    }
  }
  
    @Override
    protected void onBeforeClusterItemRendered(T item, MarkerOptions markerOptions) {
      // Customizing individual markers
      BitmapDescriptor customIcon = getCustomMarkerIcon("icons/marker.png");
      // BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN);
      markerOptions.icon(customIcon);
      
    }
  }