package com.example.specsdc;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.text.HtmlCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.provider.SyncStateContract;
import android.text.Html;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RatingBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.example.specsdc.R;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.nanolambda.NSP32.DataChannel;
import com.nanolambda.NSP32.NSP32;
import com.nanolambda.NSP32.ReturnPacket;
import com.nanolambda.NSP32.ReturnPacketReceivedListener;
import com.nanolambda.NSP32.SpectrumInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String MODEL_PATH = "spectrum_color_detection.tflite";
    private com.example.specsdc.Classifier classifier;
    private Executor executor = Executors.newSingleThreadExecutor();
    private TextView mviewColor,b,a;
    private Button mdetect;

    private static final int requestSelectDevice	= 1;
    private static final int requestEnalbeBT		= 2;
    private static final int REQUEST_PERMISSIONS	= 4;
    private static final String DISCONNECT_CMD_STR	= "disconnect";
    private float[] dataPoints2;

    private Button mupload,mbtnLight,mclear;
    private Button mBtnConnectDisconnect;
    private Button mBtnSpectrum;
    private LineChart mChartSpectrum;

    private NSP32 mNSP32;
    private short[] mWavelength = null;
    private boolean acqing = false;
    private boolean lighting = false;

    private com.example.specsdc.SpectrumTransferService mService = null;
    private BluetoothAdapter mBtAdapter = null;

    private List<Entry> mChartPoints;
    private LineDataSet mChartDataSetA;
    private List<ILineDataSet> mChartDataSets;
    private LineData mChartData;
    private StringBuilder data;
    private Spinner mSpinnerFrameAvgNum;

    String url = "jdbc:postgresql://192.168.0.101:5432/postgres";//?serverTimezone=UTC";//useUnicode=true&characterEncoding=UTF-8&useSSL=false&?useUnicode=true&characterEncoding=utf-8&useSSL=true;
    String db_user = "postgres";
    String db_password = "";

    Handler guiUpdateHandler = new Handler();
    Runnable guiUpdateRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            mChartSpectrum.invalidate();
            guiUpdateHandler.postDelayed(this, 50);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mdetect = (Button)findViewById(R.id.detect);
        mviewColor = (TextView)findViewById(R.id.viewColor);
        b = (TextView)findViewById(R.id.b);
        a = (TextView)findViewById(R.id.a);
        mbtnLight = (Button)findViewById(R.id.btnLight);
        mupload = (Button)findViewById(R.id.upload);
        mBtnConnectDisconnect = (Button) findViewById(R.id.btn_select);
        mBtnSpectrum = (Button)findViewById(R.id.buttonSpectrum);

        mSpinnerFrameAvgNum = (Spinner)findViewById(R.id.spinnerFrameAvgNum);
        SetSpinnerEntries(mSpinnerFrameAvgNum, 1, 41, 2);
//        mclear = (Button)findViewById(R.id.clear);

        mChartSpectrum = (LineChart)findViewById(R.id.chartSpectrum);

        new Thread(new Runnable(){
            @Override
            public void run(){
                try {
                    Class.forName("org.postgresql.Driver");
                    Log.v("sql","driverSuccess");
                }catch( ClassNotFoundException e) {
                    Log.e("sql","driverFail");
                    return;
                }

                try {
                    Connection con = DriverManager.getConnection(url,db_user,db_password);
                    con.close();
                    Log.v("sql","connectSuccess");
                }catch(SQLException e) {
                    Log.e("sql","connectFail");
                    Log.e("sql", e.toString());
                }
                String inDatabase = "";

                try {
                    Connection con = DriverManager.getConnection(url, db_user, db_password);
                    String sql = "SELECT * FROM test";
                    Statement st = con.createStatement();
                    ResultSet rs = st.executeQuery(sql);

                    while (rs.next())
                    {
                        String id = rs.getString("id");
                        String name = rs.getString("name");
                        inDatabase += id + ", " + name + "\n";
                    }
                    Log.d("sql",inDatabase);
                    st.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }).start();


        mChartSpectrum.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        mChartSpectrum.getDescription().setEnabled(false);

//        GetPermission();

        mBtAdapter = BluetoothAdapter.getDefaultAdapter();

        if (mBtAdapter == null)
        {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        ServiceInit();

        mChartPoints = new ArrayList<>();
        mChartPoints.add(new Entry(0, 0));
        mChartDataSetA = new LineDataSet(mChartPoints, "Spectrum");

        mChartDataSets = new ArrayList<>();
        mChartDataSets.add(mChartDataSetA);

        mChartData = new LineData(mChartDataSets);
        mChartData.setDrawValues(false);

        mChartSpectrum.setData(mChartData);

        data = new StringBuilder();
        dataPoints2 = new float[121];

        final ArrayList<Float> list1 = new ArrayList<Float>();

//        mclear.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                int ca = data.capacity();
//                Log.d("ca",String.valueOf(ca));
//                //data.delete(0,120);
//            }
//        });

        mbtnLight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(lighting == false){
                    mNSP32.SetLedIntensity((byte) 0xDD,15,15);
                    lighting = true;
                    mbtnLight.setBackground(getDrawable(R.drawable.light2));
                }else{
                    mNSP32.SetLedIntensity((byte) 0xDD,0,0);
                    lighting = false;
                    mbtnLight.setBackground(getDrawable(R.drawable.light));
                }
            }
        });

        mdetect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.setVisibility(View.VISIBLE);
                float[][] inputData = new float[1][121];
                if(dataPoints2.length!=0){
                    for(int i=0; i<121; ++i){
                        inputData[0][i] = dataPoints2[i];
                    }

                    final float[][] results = classifier.recognizecolor(inputData);
                    float compar1 = Math.max(results[0][0],results[0][1]);
                    float compar2 = Math.max(results[0][2],results[0][3]);
                    float compar3 = Math.max(compar1,compar2);
                    String color;
                    if(compar3==results[0][0]){
                        color = "紅色";
                        b.setBackgroundColor(0xffff0000);
                    }else if(compar3==results[0][1]){
                        color = "綠色";
                        b.setBackgroundColor(0xff00ff00);
                    }else if(compar3==results[0][2]){
                        color = "藍色";
                        b.setBackgroundColor(0xff0000ff);
                    }else{
                        color = "白色";
                        b.setBackgroundColor(0xff000000);
                    }

                    mviewColor.setText("紅"+results[0][0]+"\n"+"綠"+results[0][1]+"\n"+"藍"+results[0][2]+"\n"+"白"+results[0][3]);

                }
            }
        });
        initTensorFlowAndLoadModel();

        mBtnConnectDisconnect.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                if (!mBtAdapter.isEnabled())
                {
                    Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(enableIntent,requestEnalbeBT);
                }else{
                    if (mBtnConnectDisconnect.getText().equals("C"))
                    {
                        Intent newIntent = new Intent(MainActivity.this, DeviceListActivity.class);
                        startActivityForResult(newIntent,requestSelectDevice);
                    }
                    else
                    {
                        mService.disconnect();
                    }
                }
            }
        });

        mBtnSpectrum.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                    if (acqing)
                    {
                        mBtnSpectrum.setBackground(getDrawable(R.drawable.start));
                        for(int i = 0; i < 121; ++i){
                            data.append(String.valueOf(dataPoints2[i])+",");
                        }
                        data.append("\n");
                        acqing = false;
                        mNSP32.Standby((byte)0);
                    }
                    else
                    {
                        mBtnSpectrum.setBackground(getDrawable(R.drawable.stop));
                        AcqSpectrum();
                    }
            }
        });

        mupload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try{
//                    Log.d("data",data);

                    //saving the file into device
                    FileOutputStream out = openFileOutput("data.csv", Context.MODE_PRIVATE);
                    out.write((data.toString()).getBytes());
                    out.close();

                    //exporting
                    Context context = getApplicationContext();
                    File filelocation = new File(getFilesDir(), "data.csv");
                    Uri path = FileProvider.getUriForFile(context, "com.example.specsdc.fileprovider", filelocation);
                    Intent fileIntent = new Intent(Intent.ACTION_SEND);
                    fileIntent.setType("text/csv");
                    fileIntent.putExtra(Intent.EXTRA_SUBJECT, "Data");
                    fileIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    fileIntent.putExtra(Intent.EXTRA_STREAM, path);
                    startActivity(Intent.createChooser(fileIntent, "Send mail"));

                }
                catch(Exception e){
                    e.printStackTrace();
                }
            }
        });

        // set initial UI state
        guiUpdateHandler.postDelayed(guiUpdateRunnable, 0);
    }

    private void initTensorFlowAndLoadModel() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    classifier = com.example.specsdc.TensorFlowImageClassifier.create(
                            getAssets(),
                            MODEL_PATH
                    );
                } catch (final Exception e) {
                    throw new RuntimeException("Error initializing TensorFlow!", e);
                }
            }
        });
    }

    private void SetSpinnerEntries(Spinner spinner, int startIdx, int endIdx, int selectedIdx)
    {
        ArrayAdapter<String> dataAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<String>());
        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        for(int i = startIdx; i < endIdx; ++i)
        {
            dataAdapter.add(String.valueOf(i));
        }

        spinner.setAdapter(dataAdapter);
        spinner.setSelection(selectedIdx);
    }

//    private void GetPermission()
//    {
//        // check if required permissions are granted
//        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
//        {
//            // ActivityCompat.shouldShowRequestPermissionRationale will return true if the user rejects permissions at the first time
//            if (ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION))
//            {
//                // tell user why the permissions are required
//                new AlertDialog.Builder(MainActivity.this)
//                        .setMessage("This app requires Bluetooth to function well. Please grant 'access location' permission for Bluetooth.")
//                        .setTitle(Html.fromHtml("<b>" + "Permission Settings" + "</b>"))
//                        .setPositiveButton("OK", new DialogInterface.OnClickListener()
//                        {
//                            @Override
//                            public void onClick(DialogInterface dialog, int which)
//                            {
//                                // send request
//                                ActivityCompat.requestPermissions(MainActivity.this,
//                                        new String[]{ Manifest.permission.ACCESS_COARSE_LOCATION },
//                                        REQUEST_PERMISSIONS);
//                            }
//                        })
//                        .setNegativeButton("No", new DialogInterface.OnClickListener()
//                        {
//                            @Override
//                            public void onClick(DialogInterface dialog, int which)
//                            {
//                            }
//                        })
//                        .show();
//            }
//            else
//            {
//                // send request
//                ActivityCompat.requestPermissions(MainActivity.this,
//                        new String[]{ Manifest.permission.ACCESS_COARSE_LOCATION },
//                        REQUEST_PERMISSIONS);
//            }
//        }
//    }

    // do some initialization when a new data channel session is connected
    private void OnDataChannelConnected()
    {
        // reset status
        mWavelength = null;

        // create a new NSP32 instance
        mNSP32 = new NSP32(mDataChannel, mReturnPacketReceivedListener);

        // get sensor id and wavelength first
        mNSP32.GetSensorId((byte)0);

        mNSP32.GetWavelength((byte)0);

        mNSP32.Standby((byte)0);	// go standby for power saving
    }


    private void AcqSpectrum()
    {

        int integrationTime = 32;
        int frameAvgNum = Integer.parseInt(mSpinnerFrameAvgNum.getSelectedItem().toString());
        //boolean enableAE = mChkbxEnableAE.isChecked();

        acqing = true;	// clear the "stop spectrum" flag
        //mRoundTripTimeStart = System.currentTimeMillis();	// record the single command round trip start time
        mNSP32.AcqSpectrum((byte)0, integrationTime, frameAvgNum, false);	// start acquisition
    }

    // send data (commands) to NSP32 through BLE
    private final DataChannel mDataChannel = new DataChannel()
    {
        public void SendData(byte[] data)
        {
            try
            {
                if (mService != null && mService.isConnected())
                {
                    mService.sendCommand(data);
                }
            }
            catch (Exception excp)
            {
            }
        }
    };

    private final ReturnPacketReceivedListener mReturnPacketReceivedListener = new ReturnPacketReceivedListener()
    {
        public void OnReturnPacketReceived(final ReturnPacket pkt)
        {
            runOnUiThread(new Runnable()
            {
                public void run()
                {
                    // if invalid packet is received, show error message
                    if(!pkt.IsPacketValid())
                    {
                        return;
                    }

                    // process the return packet
                    switch(pkt.CmdCode())
                    {
                        case Standby:
                            break;

                        case GetWavelength:
                            mWavelength = pkt.ExtractWavelengthInfo().Wavelength();
                            break;

                        case GetSpectrum:
                            if(acqing==false)
                            {
                                break;
                            }

                            SpectrumInfo info = pkt.ExtractSpectrumInfo();


                            float[] dataPoints = info.Spectrum();
                            mChartPoints.clear();



                            for (int i = 0; i < info.NumOfPoints(); ++i)
                            {
                                if(mWavelength != null)
                                {
                                    mChartPoints.add(new Entry(mWavelength[i], dataPoints[i]));
                                    //dataPoints2.(dataPoints[i]);
                                }
                                else
                                {
                                    mChartPoints.add(new Entry(i, dataPoints[i]));
                                    //dataPoints2.add(dataPoints[i]);
                                }
                            }

                            mChartDataSetA.notifyDataSetChanged();
                            mChartData.notifyDataChanged();
                            mChartData.setDrawValues(false);
                            mChartSpectrum.notifyDataSetChanged();

                            for(int i = 0; i < 121; ++i){
                                dataPoints2[i] = dataPoints[i];
                            }

                            AcqSpectrum();

                            break;
                    }
                }
            });
        }
    };

    // UART service connected/disconnected
    private ServiceConnection mServiceConnection = new ServiceConnection()
    {
        public void onServiceConnected(ComponentName className, IBinder rawBinder)
        {
            mService = ((com.example.specsdc.SpectrumTransferService.LocalBinder) rawBinder).getService();

            if (!mService.init())
            {
                // Unable to initialize Bluetooth
                finish();
            }
        }

        public void onServiceDisconnected(ComponentName classname)
        {
            mService = null;
        }
    };

    private final BroadcastReceiver UARTStatusChangeReceiver = new BroadcastReceiver()
    {
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();

            //*********************//
            if (action.equals(com.example.specsdc.SpectrumTransferService.ACTION_GATT_CONNECTED))
            {
                runOnUiThread(new Runnable()
                {
                    public void run()
                    {
                        mBtnSpectrum.setEnabled(true);
                        mbtnLight.setEnabled(true);
                        mBtnConnectDisconnect.setText("d");
                        mBtnConnectDisconnect.setBackground(getDrawable(R.drawable.blue));
                    }
                });
            }

            //*********************//
            if (action.equals(com.example.specsdc.SpectrumTransferService.ACTION_GATT_DISCONNECTED))
            {
                runOnUiThread(new Runnable()
                {
                    public void run()
                    {
                        mBtnSpectrum.setEnabled(false);
                        mbtnLight.setEnabled(false);
                        mBtnConnectDisconnect.setText("C");
                        mBtnConnectDisconnect.setBackground(getDrawable(R.drawable.blues));

                        mService.close();
                    }
                });
            }

            //*********************//
            if (action.equals(com.example.specsdc.SpectrumTransferService.ACTION_GATT_SERVICES_DISCOVERED))
            {
                mService.enableTXNotification();
            }

            //*********************//
            if (action.equals(com.example.specsdc.SpectrumTransferService.ACTION_TX_NOTIFICATION_SET))
            {
                runOnUiThread(new Runnable()
                {
                    public void run()
                    {
                        OnDataChannelConnected();
                        //SetGuiByAppMode(AppRunMode.Connected);
                    }
                });
            }

            //*********************//
            if (action.equals(com.example.specsdc.SpectrumTransferService.ACTION_DATA_AVAILABLE))
            {
                byte[] data = intent.getByteArrayExtra(com.example.specsdc.SpectrumTransferService.EXTRA_DATA);

                if(java.util.Arrays.equals(DISCONNECT_CMD_STR.getBytes(), data))
                {
                    // if this is a disconnect admit from BLE peripheral, then disconnect
                    mService.disconnect();
                }
                else
                {
                    try
                    {
                        // feed the received data to NSP32 API
                        mNSP32.OnReturnBytesReceived(data);
                    }
                    catch (Exception excp)
                    {
                    }
                }
            }

            //*********************//
            if (action.equals(com.example.specsdc.SpectrumTransferService.DEVICE_DOES_NOT_SUPPORT_SPECTRUM_TRANSFER))
            {

                mService.disconnect();
            }
        }
    };

    private void ServiceInit()
    {
        Intent bindIntent = new Intent(this, com.example.specsdc.SpectrumTransferService.class);
        bindService(bindIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        LocalBroadcastManager.getInstance(this).registerReceiver(UARTStatusChangeReceiver, makeGattUpdateIntentFilter());
    }

    private static IntentFilter makeGattUpdateIntentFilter()
    {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(com.example.specsdc.SpectrumTransferService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(com.example.specsdc.SpectrumTransferService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(com.example.specsdc.SpectrumTransferService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(com.example.specsdc.SpectrumTransferService.ACTION_TX_NOTIFICATION_SET);
        intentFilter.addAction(com.example.specsdc.SpectrumTransferService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(com.example.specsdc.SpectrumTransferService.DEVICE_DOES_NOT_SUPPORT_SPECTRUM_TRANSFER);
        return intentFilter;
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();

        try
        {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(UARTStatusChangeReceiver);
        }
        catch (Exception ignore)
        {
        }

        unbindService(mServiceConnection);
        mService.stopSelf();
        mService= null;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                classifier.close();
            }
        });
    }

    @Override
    public void onResume()
    {
        super.onResume();

        if (!mBtAdapter.isEnabled())
        {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, requestEnalbeBT);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case requestSelectDevice:
                //When the DeviceListActivity return, with the selected device address
                if (resultCode == Activity.RESULT_OK && data != null) {
                    String deviceAddress = data.getStringExtra(BluetoothDevice.EXTRA_DEVICE);
                    mService.connect(deviceAddress);
                }

                break;

            case requestEnalbeBT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this, "Bluetooth has turned on ", Toast.LENGTH_SHORT).show();
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Toast.makeText(this, "Problem in BT Turning ON ", Toast.LENGTH_SHORT).show();
                    finish();
                }

                break;

            default:
                break;
        }
    }

    @Override
    public void onBackPressed()
    {
        finish();
    }
}
