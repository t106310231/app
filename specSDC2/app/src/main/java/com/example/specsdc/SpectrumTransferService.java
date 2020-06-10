package com.example.specsdc;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.UUID;

public class SpectrumTransferService extends Service
{
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt;
    private String mBluetoothDeviceAddress;
    private boolean mIsConnected = false;

    public final static String disconnect = "disconnect";

    public final static String ACTION_GATT_CONNECTED =
            "com.nanolambda.SpectrumTransferService.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.nanolambda.SpectrumTransferService.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.nanolambda.SpectrumTransferService.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_TX_NOTIFICATION_SET =
            "com.nanolambda.SpectrumTransferService.ACTION_TX_NOTIFICATION_SET";
    public final static String ACTION_DATA_AVAILABLE =
            "com.nanolambda.SpectrumTransferService.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "com.nanolambda.SpectrumTransferService.EXTRA_DATA";
    public final static String DEVICE_DOES_NOT_SUPPORT_SPECTRUM_TRANSFER =
            "com.nanolambda.SpectrumTransferService.DEVICE_DOES_NOT_SUPPORT_SPECTRUM_TRANSFER";

    public static final UUID CCCD							= UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final UUID SPECTRUM_TRANSFER_SERVICE_UUID	= UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca3e");
    public static final UUID RX_CHAR_UUID					= UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca3e");
    public static final UUID TX_CHAR_UUID					= UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca3e");

    // Implements callback methods for GATT events that the app cares about.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback()
    {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState)
        {
            if (newState == BluetoothProfile.STATE_CONNECTED)
            {
                mIsConnected = true;
                broadcastUpdate(ACTION_GATT_CONNECTED);
                mBluetoothGatt.discoverServices();
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED)
            {
                mIsConnected = false;
                broadcastUpdate(ACTION_GATT_DISCONNECTED);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status)
        {
            if (status == BluetoothGatt.GATT_SUCCESS)
            {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status)
        {
            if (status == BluetoothGatt.GATT_SUCCESS)
            {
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
        {
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status)
        {
            if(TX_CHAR_UUID.equals(descriptor.getCharacteristic().getUuid()))
            {
                broadcastUpdate(ACTION_TX_NOTIFICATION_SET);
            }
        }
    };

    private void broadcastUpdate(final String action)
    {
        final Intent intent = new Intent(action);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action, final BluetoothGattCharacteristic characteristic)
    {
        final Intent intent = new Intent(action);

        if (action.equals(ACTION_DATA_AVAILABLE))
        {
            intent.putExtra(EXTRA_DATA, characteristic.getValue());
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder
    {
        SpectrumTransferService getService()
        {
            return SpectrumTransferService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent)
    {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean init()
    {
        if (mBluetoothManager == null)
        {
            mBluetoothManager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);

            if (mBluetoothManager == null)
            {
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();

        return mBluetoothAdapter != null;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address)
    {
        if (mBluetoothAdapter == null || address == null)
        {
            return false;
        }

        // Previously connected device. Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress) && mBluetoothGatt != null)
        {
            return mBluetoothGatt.connect();
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);

        if (device == null)
        {
            return false;
        }

        mBluetoothGatt = device.connectGatt(this, true, mGattCallback);
        mBluetoothDeviceAddress = address;
        return true;
    }

    public boolean isConnected()
    {
        return mIsConnected;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect()
    {
        if (mBluetoothAdapter == null || mBluetoothGatt == null)
        {
            return;
        }

        mBluetoothGatt.disconnect();
    }

    public void requestMtu(int mtu)
    {
        // requesting 247 byte MTU
        mBluetoothGatt.requestMtu(mtu);
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close()
    {
        if (mBluetoothGatt == null)
        {
            return;
        }

        mBluetoothDeviceAddress = null;
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    public void enableTXNotification()
    {
        BluetoothGattService service = mBluetoothGatt.getService(SPECTRUM_TRANSFER_SERVICE_UUID);

        if (service == null)
        {
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_SPECTRUM_TRANSFER);
            return;
        }

        BluetoothGattCharacteristic txChar = service.getCharacteristic(TX_CHAR_UUID);

        if (txChar == null)
        {
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_SPECTRUM_TRANSFER);
            return;
        }

        mBluetoothGatt.setCharacteristicNotification(txChar, true);

        BluetoothGattDescriptor descriptor = txChar.getDescriptor(CCCD);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        mBluetoothGatt.writeDescriptor(descriptor);
    }

    public void sendCommand(byte[] value)
    {
        BluetoothGattService service = mBluetoothGatt.getService(SPECTRUM_TRANSFER_SERVICE_UUID);

        if (service == null)
        {
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_SPECTRUM_TRANSFER);
            return;
        }

        BluetoothGattCharacteristic rxChar = service.getCharacteristic(RX_CHAR_UUID);

        if (rxChar == null)
        {
            broadcastUpdate(DEVICE_DOES_NOT_SUPPORT_SPECTRUM_TRANSFER);
            return;
        }

        rxChar.setValue(value);
        mBluetoothGatt.writeCharacteristic(rxChar);
    }
}
