package net.kenevans.android.blecardiacmonitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.preference.PreferenceManager;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * For a given BLE device, this Activity provides the user interface to connect,
 * display data, and display GATT services and characteristics supported by the
 * device. The Activity communicates with {@code BCMBleService}, which in turn
 * interacts with the Bluetooth LE API.
 */
public class DeviceMonitorActivity extends AppCompatActivity implements IConstants {
    private TextView mConnectionState;
    private TextView mBat;
    private TextView mHr;
    private TextView mRr;
    private TextView mStatus;
    private String mDeviceName;
    private String mDeviceAddress;
    private BCMBleService mBLECardiacBleService;
    private boolean mConnected = false;
    private BCMDbAdapter mDbAdapter;
    private BluetoothGattCharacteristic mCharBat;
    private BluetoothGattCharacteristic mCharHr;

    /**
     * Manages the service lifecycle.
     */
    private final ServiceConnection mServiceConnection = new
            ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName componentName,
                                               IBinder service) {
                    Log.d(TAG, "onServiceConnected: " + mDeviceName + " "
                            + mDeviceAddress);
                    mBLECardiacBleService = ((BCMBleService.LocalBinder)
                            service)
                            .getService();
                    if (!mBLECardiacBleService.initialize()) {
                        String msg = "Unable to initialize Bluetooth";
                        Log.e(TAG, msg);
                        Utils.errMsg(DeviceMonitorActivity.this, msg);
                        return;
                    }
                    if (mDbAdapter != null) {
                        mBLECardiacBleService.startDatabase(mDbAdapter);
                    }
                    // Automatically connects to the device upon successful
                    // start-up
                    // initialization.
                    SharedPreferences prefs = PreferenceManager
                            .getDefaultSharedPreferences
                                    (DeviceMonitorActivity.this);
                    boolean manuallyDisconnected = prefs.getBoolean(
                            PREF_MANUALLY_DISCONNECTED, false);
                    if (!manuallyDisconnected) {
                        boolean res = mBLECardiacBleService.connect
                                (mDeviceAddress);
                        Log.d(TAG, "Connect mBLECardiacBleService result=" +
                                res);
                        if (res) {
                            setManuallyDisconnected(false);
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName componentName) {
                    Log.d(TAG, "onServiceDisconnected");
                    mBLECardiacBleService = null;
                }
            };

    /**
     * Handles various events fired by the Service.
     * <p/>
     * <br>
     * <br>
     * ACTION_GATT_CONNECTED: connected to a GATT server.<br>
     * ACTION_GATT_DISCONNECTED: disconnected from a GATT server.<br>
     * ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.<br>
     * ACTION_DATA_AVAILABLE: received data from the device. This can be a
     * result of read or notification operations.<br>
     */
    private final BroadcastReceiver mGattUpdateReceiver = new
            BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final String action = intent.getAction();
                    if (BCMBleService.ACTION_GATT_CONNECTED.equals(action)) {
                        Log.d(TAG, "onReceive: " + action);
                        mConnected = true;
                        updateConnectionState(R.string.connected);
                        invalidateOptionsMenu();
                    } else if (BCMBleService.ACTION_GATT_DISCONNECTED.equals
                            (action)) {
                        Log.d(TAG, "onReceive: " + action);
                        mConnected = false;
                        resetDataViews();
                        updateConnectionState(R.string.disconnected);
                        invalidateOptionsMenu();
                    } else if (BCMBleService.ACTION_GATT_SERVICES_DISCOVERED
                            .equals(action)) {
                        Log.d(TAG, "onReceive: " + action);
                        onServicesDiscovered(mBLECardiacBleService
                                .getSupportedGattServices());
                    } else if (BCMBleService.ACTION_DATA_AVAILABLE.equals
                            (action)) {
                        // Log.d(TAG, "onReceive: " + action);
                        displayData(intent);
                    } else if (BCMBleService.ACTION_ERROR.equals(action)) {
                        // Log.d(TAG, "onReceive: " + action);
                        displayError(intent);
                    }
                }
            };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_monitor);

        // Initialize the preferences if not already done
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Use this check to determine whether BLE is supported on the device.
        // Then you can
        // selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH_LE)) {
            String msg = getString(R.string.ble_not_supported);
            Utils.errMsg(this, msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        }

        // Initializes a Bluetooth adapter. For API level 18 and above, get a
        // reference to BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager = (BluetoothManager)
                getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            String msg = getString(R.string.error_bluetooth_manager);
            Utils.errMsg(this, msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        }

        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        // Checks if Bluetooth is supported on the device
        if (adapter == null) {
            String msg = getString(R.string.bluetooth_not_supported);
            Utils.errMsg(this, msg);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        }

        // Use this instead of getPreferences to be application-wide
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(this);
        mDeviceName = prefs.getString(DEVICE_NAME_CODE, null);
        mDeviceAddress = prefs.getString(DEVICE_ADDRESS_CODE, null);
        Log.d(TAG, this.getClass().getSimpleName() + ": onCreate: "
                + mDeviceName + " " + mDeviceAddress);

        ((TextView) findViewById(R.id.device_name)).setText(mDeviceName);
        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
        mConnectionState = findViewById(R.id.connection_state);
        mBat = findViewById(R.id.bat_value);
        mHr = findViewById(R.id.hr_value);
        mRr = findViewById(R.id.rr_value);
        mStatus = findViewById(R.id.status_value);
        resetDataViews();

        Intent gattServiceIntent = new Intent(this, BCMBleService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        // Open the database
        File dataDir = getDataDirectory();
        if (dataDir == null) {
            return;
        }
        mDbAdapter = new BCMDbAdapter(this, dataDir);
        mDbAdapter.open();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, this.getClass().getSimpleName() + ": onResume: mConnected="
                + mConnected + " mBLECardiacBleService="
                + (mBLECardiacBleService == null ? "null" : "not null"));
        super.onResume();
        // Get the settings
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(this);
        boolean manuallyDisconnected = prefs.getBoolean(
                PREF_MANUALLY_DISCONNECTED, false);
        // DEBUG
        Log.d(TAG, "Starting registerReceiver");
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (!manuallyDisconnected && mDeviceAddress != null
                && mBLECardiacBleService != null) {
            Log.d(TAG, "Starting mBLECardiacBleService.connect");
            final boolean res = mBLECardiacBleService.connect(mDeviceAddress);
            Log.d(TAG, "mBLECardiacBleService.connect: result=" + res);
            if (res) {
                setManuallyDisconnected(false);
            }
        }
    }

    @Override
    protected void onPause() {
        Log.d(TAG, this.getClass().getSimpleName() + ": onPause");
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBLECardiacBleService = null;
        if (mDbAdapter != null) {
            mDbAdapter.close();
            mDbAdapter = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_device_monitor, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_connect:
                mBLECardiacBleService.connect(mDeviceAddress);
                setManuallyDisconnected(false);
                return true;
            case R.id.menu_disconnect:
                mBLECardiacBleService.disconnect();
                setManuallyDisconnected(true);
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
            case R.id.menu_select_device:
                selectDevice();
                return true;
            case R.id.menu_session_manager:
                startSessionManager();
                return true;
            case R.id.menu_plot:
                plot();
                return true;
            case R.id.menu_read_battery_level:
                readBatteryLevel();
                return true;
            case R.id.help:
                showHelp();
                return true;
            case R.id.menu_settings:
                showSettings();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent
            data) {
        switch (requestCode) {
            case REQUEST_SELECT_DEVICE_CODE:
                if (resultCode == Activity.RESULT_OK) {
                    mDeviceName = data.getStringExtra(DEVICE_NAME_CODE);
                    mDeviceAddress = data.getStringExtra(DEVICE_ADDRESS_CODE);
                    // Use this instead of getPreferences to be application-wide
                    SharedPreferences.Editor editor = PreferenceManager
                            .getDefaultSharedPreferences(this).edit();
                    editor.putString(DEVICE_NAME_CODE, mDeviceName);
                    editor.putString(DEVICE_ADDRESS_CODE, mDeviceAddress);
                    editor.apply();
                    ((TextView) findViewById(R.id.device_name))
                            .setText(mDeviceName);
                    ((TextView) findViewById(R.id.device_address))
                            .setText(mDeviceAddress);
                }
                break;
            case REQUEST_PLOT_CODE:
                String msg = null;
                if (data != null) {
                    msg = data.getStringExtra(MSG_CODE);
                }
                if (resultCode == RESULT_ERROR) {
                    if (msg != null) {
                        Utils.errMsg(this, msg);
                    } else {
                        Utils.errMsg(this, "Unknown error plotting");
                    }
                    // } else if (resultCode == RESULT_CANCELED) {
                    // if (msg != null) {
                    // Utils.errMsg(this, msg);
                    // } else {
                    // Utils.errMsg(this, "Canceled");
                    // }
                }
                break;
            case REQUEST_SETTINGS_CODE:
                Log.d(TAG, "onActivityResult: REQUEST_SETTINGS_CODE resultCode="
                        + resultCode);
                // resetDataViews();
                // if (mBLECardiacBleService != null &&
                // mBLECardiacBleService.getSessionInProgress()) {
                // setEnabledFlags();
                // }
                break;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Gets the current data directory and sets the default preference for
     * PREF_DATA_DIRECTORY.
     *
     * @return GThe directory or null on failure.
     */
    public File getDataDirectory() {
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(this);
        String dataDirName = prefs.getString(PREF_DATA_DIRECTORY, null);
        File dataDir = null;
        if (dataDirName != null) {
            dataDir = new File(dataDirName);
        } else {
            File sdCardRoot = Environment.getExternalStorageDirectory();
            if (sdCardRoot != null) {
                dataDir = new File(sdCardRoot, SD_CARD_DB_DIRECTORY);
                // Change the stored value (even if it is null)
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(PREF_DATA_DIRECTORY, dataDir.getPath());
                editor.apply();
            }
        }
        if (dataDir == null) {
            Utils.errMsg(this, "Data directory is null");
        } else if (!dataDir.exists()) {
            boolean res = dataDir.mkdir();
            if (!res) {
                Utils.errMsg(this, "Cannot find or create directory: "
                        + dataDir);
                return null;
            }
        }
        return dataDir;
    }

    /**
     * Updates the connection state view on the UI thread.
     *
     * @param resourceId The resource ID.
     */
    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
            }
        });
    }

    /**
     * Calls an activity to select the device.
     */
    public void selectDevice() {
        // Scan doesn't find current device if it is connected
        if (mConnected) {
            // Confirm the user wants to scan even if the current device is
            // connected
            new AlertDialog.Builder(DeviceMonitorActivity.this)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle(R.string.confirm)
                    .setMessage(R.string.scan_prompt)
                    .setPositiveButton(R.string.ok,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog,
                                                    int which) {
                                    Intent intent = new Intent(
                                            DeviceMonitorActivity.this,
                                            DeviceScanActivity.class);
                                    startActivityForResult(intent,
                                            REQUEST_SELECT_DEVICE_CODE);
                                }

                            }).setNegativeButton(R.string.cancel, null).show();
        } else {
            Intent intent = new Intent(DeviceMonitorActivity.this,
                    DeviceScanActivity.class);
            startActivityForResult(intent, REQUEST_SELECT_DEVICE_CODE);
        }
    }

    public void readBatteryLevel() {
        if (mBLECardiacBleService != null) {
            mBLECardiacBleService.readBatteryLevel();
        }
    }

    /**
     * Calls the plot activity.
     */
    public void plot() {
        Intent intent = new Intent(DeviceMonitorActivity.this,
                PlotActivity.class);
        // Plot the current data, not a session
        intent.putExtra(PLOT_SESSION_CODE, false);
        startActivityForResult(intent, REQUEST_PLOT_CODE);
    }

    /**
     * Show the help.
     */
    private void showHelp() {
        try {
            // Start theInfoActivity
            Intent intent = new Intent();
            intent.setClass(this, InfoActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(INFO_URL, "file:///android_asset/bcm.html");
            startActivity(intent);
        } catch (Exception ex) {
            Utils.excMsg(this, getString(R.string.help_show_error), ex);
        }
    }

    /**
     * Calls the settings activity.
     */
    public void showSettings() {
        Intent intent = new Intent(DeviceMonitorActivity.this,
                SettingsActivity.class);
        intent.putExtra(SETTINGS_CODE, false);
        startActivityForResult(intent, REQUEST_SETTINGS_CODE);
    }

    /**
     * Calls the session manager activity.
     */
    public void startSessionManager() {
        Intent intent = new Intent(DeviceMonitorActivity.this,
                SessionManagerActivity.class);
        startActivityForResult(intent, REQUEST_SESSION_MANAGER_CODE);
    }

    /**
     * Displays the data from an ACTION_DATA_AVAILABLE callback.
     *
     * @param intent The Intent.
     */
    private void displayData(Intent intent) {
        String uuidString;
        UUID uuid;
        String value;
        try {
            uuidString = intent.getStringExtra(EXTRA_UUID);
            if (uuidString == null) {
                mStatus.setText(R.string.null_uuid_msg);
                return;
            }
            uuid = UUID.fromString(uuidString);
            if (uuid.equals(UUID_HEART_RATE_MEASUREMENT)) {
                value = intent.getStringExtra(EXTRA_HR);
                if (value == null) {
                    mHr.setText(R.string.not_available);
                } else {
                    mHr.setText(value);
                }
                value = intent.getStringExtra(EXTRA_RR);
                if (value == null) {
                    mRr.setText(R.string.not_available);
                } else {
                    mRr.setText(value);
                }
            } else if (uuid.equals(UUID_BATTERY_LEVEL)) {
                value = intent.getStringExtra(EXTRA_BAT);
                if (value == null) {
                    mBat.setText(R.string.not_available);
                } else {
                    mBat.setText(value);
                }
            }
        } catch (Exception ex) {
            Log.d(TAG, "Error displaying data", ex);
            mStatus.setText(this.getString(R.string.exception_msg_format,
                    ex.getMessage()));
            // Don't use Utils here as there may be many
            // Utils.excMsg(this, "Error displaying message", ex);
        }
    }

    /**
     * Sets the PREF_MANUALLY_DISCONNECTED preference in PreferenceManager
     * .getDefaultSharedPreferences.
     *
     * @param b The value for PREF_MANUALLY_DISCONNECTED.
     */
    private void setManuallyDisconnected(boolean b) {
        SharedPreferences.Editor editor = PreferenceManager
                .getDefaultSharedPreferences(this).edit();
        editor.putBoolean(PREF_MANUALLY_DISCONNECTED, b);
        editor.apply();
    }

    /**
     * Displays the error from an ACTION_ERROR callback.
     *
     * @param intent The Intent with the message for the error.
     */
    private void displayError(Intent intent) {
        String msg = null;
        try {
            msg = intent.getStringExtra(EXTRA_MSG);
            if (msg == null) {
                mStatus.setText(R.string.null_error_msg);
                Utils.errMsg(this, "Received null error message");
                return;
            }
            Utils.errMsg(this, msg);
        } catch (Exception ex) {
            Log.d(TAG, "Error displaying error", ex);
            mStatus.setText(this.getString(R.string.exception_msg_format,
                    ex.getMessage()));
            Utils.excMsg(this, msg, ex);
        }
    }

    /**
     * Starts a session using the proper starting Characteristics depending on
     * mDoBat, mDoHr, and mDoCustom.
     *
     * @return Whether the service started the session successfully.
     */
    boolean startSession() {
        Log.d(TAG, "  mCharBat=" + mCharBat + " mCharHr=" + mCharHr);
        return mBLECardiacBleService.startSession(mCharBat, mCharHr);
    }

    /**
     * Resets the data view to show default values
     */
    public void resetDataViews() {
        mBat.setText(R.string.not_available);
        mHr.setText(R.string.not_available);
        mRr.setText(R.string.not_available);
        mStatus.setText("");
    }

    /**
     * Sets up read or notify for this characteristic if possible.
     *
     * @param characteristic The Characteristic found.
     */
    private void onCharacteristicFound(
            BluetoothGattCharacteristic characteristic) {
        Log.d(TAG, "onCharacteristicFound: " + characteristic.getUuid());
        if (characteristic.getUuid().equals(UUID_HEART_RATE_MEASUREMENT)
                || characteristic.getUuid().equals(UUID_BATTERY_LEVEL)
                || characteristic.getUuid().equals(UUID_CUSTOM_MEASUREMENT)) {
            if (characteristic.getUuid().equals(UUID_HEART_RATE_MEASUREMENT)) {
                mCharHr = characteristic;
            } else if (characteristic.getUuid().equals(UUID_BATTERY_LEVEL)) {
                mCharBat = characteristic;
            }
            startSession();
        }
    }

    /**
     * Make an IntentFilter for the actions in which we are interested.
     *
     * @return The IntentFilter.
     */
    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BCMBleService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BCMBleService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BCMBleService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BCMBleService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    /**
     * Called when services are discovered.
     *
     * @param gattServices The list of Gatt services.
     */
    private void onServicesDiscovered(List<BluetoothGattService> gattServices) {
        if (gattServices == null) {
            return;
        }
        // Loop through available GATT Services
        UUID serviceUuid;
        UUID charUuid;
        mCharBat = null;
        mCharHr = null;
        boolean hrFound = false, batFound = false;
        for (BluetoothGattService gattService : gattServices) {
            serviceUuid = gattService.getUuid();
            if (serviceUuid.equals(UUID_HEART_RATE_SERVICE)) {
                hrFound = true;
                // Loop through available Characteristics
                for (BluetoothGattCharacteristic characteristic : gattService
                        .getCharacteristics()) {
                    charUuid = characteristic.getUuid();
                    if (charUuid.equals(UUID_HEART_RATE_MEASUREMENT)) {
                        mCharHr = characteristic;
                        onCharacteristicFound(characteristic);
                    }
                }
            } else if (serviceUuid.equals(UUID_BATTERY_SERVICE)) {
                batFound = true;
                // Loop through available Characteristics
                for (BluetoothGattCharacteristic characteristic : gattService
                        .getCharacteristics()) {
                    charUuid = characteristic.getUuid();
                    if (charUuid.equals(UUID_BATTERY_LEVEL)) {
                        mCharBat = characteristic;
                        onCharacteristicFound(characteristic);
                    }
                }
            }
        }
        if (!hrFound || !batFound || mCharHr == null || mCharBat == null) {
            String info = "Services and Characteristics not found:" + "\n";
            if (!hrFound) {
                info += "  Heart Rate" + "\n";
            } else if (mCharHr == null) {
                info += "    Heart Rate Measurement" + "\n";
            } else if (!batFound) {
                info += "  Battery" + "\n";
            } else if (mCharBat == null) {
                info += "    Battery Level" + "\n";
            }
            Utils.warnMsg(this, info);
            Log.d(TAG, "onServicesDiscovered: " + info);
        }
    }

}
