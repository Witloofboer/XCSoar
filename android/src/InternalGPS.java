/* Copyright_License {

  XCSoar Glide Computer - http://www.xcsoar.org/
  Copyright (C) 2000-2021 The XCSoar Project
  A detailed list of copyright holders can be found in the file "AUTHORS".

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License
  as published by the Free Software Foundation; either version 2
  of the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
}
*/

package org.xcsoar;

import android.os.Handler;
import android.os.Bundle;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.WindowManager;
import android.view.Surface;
import android.util.Log;
import java.lang.Math;

/**
 * Code to support the internal GPS receiver via #LocationManager.
 */
public class InternalGPS
  implements LocationListener, SensorEventListener, Runnable, AndroidSensor
{
  private static final String TAG = "XCSoar";

  private static Handler handler;

  private final SensorListener listener;

  /**
   * Global initialization of the class.  Must be called from the main
   * event thread, because the Handler object must be bound to that
   * thread.
   */
  public static void Initialize() {
    handler = new Handler();
  }

  /** the name of the currently selected location provider */
  String locationProvider = LocationManager.GPS_PROVIDER;
  //String locationProvider = LocationManager.NETWORK_PROVIDER;

  private LocationManager locationManager;
  private SensorManager sensorManager;
  private WindowManager windowManager;
  private Sensor accelerometer;
  private static boolean queriedLocationSettings = false;

  private int state = STATE_LIMBO;

  private final SafeDestruct safeDestruct = new SafeDestruct();

  InternalGPS(Context context, SensorListener listener) {
    this.listener = listener;

    locationManager = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
    if (locationManager == null ||
        locationManager.getProvider(locationProvider) == null) {
      /* on the Nook Simple Touch, LocationManager.isProviderEnabled()
         can crash, but LocationManager.getProvider() appears to be
         safe, therefore we're first checking the latter; if the
         device does have a GPS, it returns non-null even when the
         user has disabled GPS */
      locationProvider = null;
    } else if (!locationManager.isProviderEnabled(locationProvider) &&
        !queriedLocationSettings) {
      // Let user turn on GPS, XCSoar is not allowed to.
      Intent myIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
      context.startActivity(myIntent);
      queriedLocationSettings = true;
    }

    windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

    update();
  }

  /**
   * Called by the #Handler, indirectly by update().  Updates the
   * LocationManager subscription inside the main thread.
   */
  @Override public void run() {
    Log.d(TAG, "Updating GPS subscription...");
    locationManager.removeUpdates(this);
    sensorManager.unregisterListener(this);

    if (locationProvider != null) {
      Log.d(TAG, "Subscribing to GPS updates.");
      try {
        locationManager.requestLocationUpdates(locationProvider,
                                               1000, 0, this);
      } catch (IllegalArgumentException e) {
        /* this exception was recorded on the Android Market, message
           was: "provider=gps" - no idea what that means */
        setConnectedSafe(0);
        return;
      }

      sensorManager.registerListener(this, accelerometer,
                                     sensorManager.SENSOR_DELAY_NORMAL);

      setConnectedSafe(1); // waiting for fix
    } else {
      Log.d(TAG, "Unsubscribing from GPS updates.");
      setConnectedSafe(0); // not connected
    }
    Log.d(TAG, "Done updating GPS subscription...");
  }

  /**
   * Update the LocationManager subscription.  May be called from any
   * thread.
   */
  private void update() {
    Log.d(TAG, "Triggering GPS subscription update...");
    handler.removeCallbacks(this);
    handler.post(this);
  }

  public void setLocationProvider(String _locationProvider) {
    locationProvider = _locationProvider;
    update();
  }

  @Override
  public void close() {
    safeDestruct.beginShutdown();
    setLocationProvider(null);
    safeDestruct.finishShutdown();
  }

  @Override
  public int getState() {
    return state;
  }

  private void sendLocation(Location location) {
    Bundle extras = location.getExtras();

    listener.onLocationSensor(location.getTime(),
                              extras != null ? extras.getInt("satellites", -1) : -1,
                              location.getLongitude(), location.getLatitude(),
                              location.hasAltitude(), false,
                              location.getAltitude(),
                              location.hasBearing(), location.getBearing(),
                              location.hasSpeed(), location.getSpeed(),
                              location.hasAccuracy(), location.getAccuracy());
  }

  private void setConnectedSafe(int connected) {
    if (!safeDestruct.increment())
      return;

    try {
      listener.onConnected(connected);
    } finally {
      safeDestruct.decrement();
    }
  }

  /** from LocationListener */
  @Override public void onLocationChanged(Location newLocation) {
    if (!safeDestruct.increment())
      return;

    try {
      listener.onConnected(2); // fix found
      sendLocation(newLocation);
    } finally {
      safeDestruct.decrement();
    }
  }

  /** from LocationListener */
  @Override public void onProviderDisabled(String provider) {
    setConnectedSafe(0); // not connected
  }

  /** from LocationListener */
  @Override public void onProviderEnabled(String provider) {
    setConnectedSafe(1); // waiting for fix
  }

  /** from LocationListener */
  @Override public void onStatusChanged(String provider, int status,
                                        Bundle extras) {
    switch (status) {
    case LocationProvider.OUT_OF_SERVICE:
      state = STATE_FAILED;
      setConnectedSafe(0); // not connected
      listener.onSensorStateChanged();
      break;

    case LocationProvider.TEMPORARILY_UNAVAILABLE:
      state = STATE_LIMBO;
      setConnectedSafe(1); // waiting for fix
      listener.onSensorStateChanged();
      break;

    case LocationProvider.AVAILABLE:
      state = STATE_READY;
      listener.onSensorStateChanged();
      break;
    }
  }

  /** from sensorEventListener */
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
  }

  /** from sensorEventListener */
  public void onSensorChanged(SensorEvent event) {
    double acceleration;
    acceleration = Math.sqrt((double) event.values[0]*event.values[0] +
                             (double) event.values[1]*event.values[1] +
                             (double) event.values[2]*event.values[2]) /
                   SensorManager.GRAVITY_EARTH;
    switch (windowManager.getDefaultDisplay().getOrientation()) {
      case Surface.ROTATION_0:   // g = -y
        acceleration *= Math.signum(event.values[1]);
        break;
      case Surface.ROTATION_90:  // g = -x
        acceleration *= Math.signum(event.values[0]);
        break;
      case Surface.ROTATION_180:  // g = y
        acceleration *= Math.signum(-event.values[1]);
        break;
      case Surface.ROTATION_270:  // g = x
        acceleration *= Math.signum(-event.values[0]);
        break;
    }
    // TODO: do lowpass filtering to remove vibrations?!?

    listener.onAccelerationSensor1(acceleration);
  }
}
