/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.falkorichter.usb.rocketLauncher;

import java.nio.ByteBuffer;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.widget.Button;
import android.widget.TextView;

import com.googlecode.androidannotations.annotations.AfterViews;
import com.googlecode.androidannotations.annotations.EActivity;
import com.googlecode.androidannotations.annotations.SystemService;
import com.googlecode.androidannotations.annotations.UiThread;
import com.googlecode.androidannotations.annotations.ViewById;


public class MissileLauncherActivity extends Activity
implements View.OnClickListener, Runnable, OnTouchListener {

	private static final String TAG = "MissileLauncherActivity";

	
	Button mMoveUp;
	Button mMoveLeft;
	Button mMoveRight;	
	Button mMoveDown;
	Button mFireButton;
	TextView logTextView;

	
	private UsbManager mUsbManager;
	private UsbDevice mDevice;
	private UsbDeviceConnection mConnection;
	private UsbEndpoint mEndpointIntr;

	
	private SensorManager mSensorManager;
	private Sensor mGravitySensor;

	// USB control commands
	private static final int COMMAND_UP = 1;
	private static final int COMMAND_DOWN = 2;
	private static final int COMMAND_RIGHT = 4;
	private static final int COMMAND_LEFT = 8;
	private static final int COMMAND_FIRE = 10;
	private static final int COMMAND_STOP = 20;
	private static final int COMMAND_STATUS = 64;

	// constants for accelerometer orientation
	private static final int TILT_LEFT = 1;
	private static final int TILT_RIGHT = 2;
	private static final int TILT_UP = 8;
	private static final int TILT_DOWN = 4;
	private static final double THRESHOLD = 5.0;

	public void onCreate(){

		setLayout(R.layout.launcher);

		
		mUsbManager =  (UsbManager) getSystemService(Context.USB_SERVICE);
		mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

		@ViewById(R.id.moveUp)
		@ViewById(R.id.moveLeft)
		@ViewById(R.id.moveRight)
		@ViewById(R.id.moveDown)
		@ViewById(R.id.fire)
		@ViewById(R.id.logTextView)

		initialize();
	}

	@AfterViews
	public void initialize() {

		logTextView.setText("");
		logTextView.setVerticalScrollBarEnabled(true);

		mMoveUp.setOnClickListener(this);
		mMoveUp.setOnTouchListener(this);

		mMoveLeft.setOnClickListener(this);
		mMoveLeft.setOnTouchListener(this);

		mMoveRight.setOnClickListener(this);
		mMoveRight.setOnTouchListener(this);

		mMoveDown.setOnClickListener(this);
		mMoveDown.setOnTouchListener(this);

		mFireButton.setOnClickListener(this);
		mFireButton.setOnTouchListener(this);

		mGravitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
	}

	@Override
	public void onPause() {
		super.onPause();
		mSensorManager.unregisterListener(mGravityListener);
	}

	@Override
	public void onResume() {
		super.onResume();
		mSensorManager.registerListener(mGravityListener, mGravitySensor,
				SensorManager.SENSOR_DELAY_NORMAL);

		Intent intent = getIntent();
		Log.d(TAG, "intent: " + intent);
		String action = intent.getAction();

		UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
		if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
			setDevice(device);
		} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
			if (mDevice != null && mDevice.equals(device)) {
				setDevice(null);
			}
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
	}

	private void setDevice(UsbDevice device) {
		appendLogText("setDevice " + device);
		
		appendLogText("getDeviceClass: "+device.getDeviceClass());
		//TODO: finish the mapping
		switch (device.getDeviceClass()) {
		case UsbConstants.USB_CLASS_APP_SPEC:
			appendLogText("getDeviceClass: USB_CLASS_APP_SPEC ");
			break;
		case UsbConstants.USB_CLASS_PER_INTERFACE:
			appendLogText("getDeviceClass: USB_CLASS_PER_INTERFACE");
			break;
		}
	
		appendLogText("getDeviceId:" + device.getDeviceId());
		appendLogText("getDeviceName:" + device.getDeviceName());
		appendLogText("getVendorId:" + device.getVendorId());
		appendLogText("getProductId:" + device.getProductId());

		if (device.getInterfaceCount() != 1) {
			appendLogText("could not find interface");
			return;
		}
		UsbInterface intf = device.getInterface(0);
		// device should have one endpoint
		if (intf.getEndpointCount() != 1) {
			appendLogText("could not find endpoint");
			return;
		}
		// endpoint should be of type interrupt
		UsbEndpoint ep = intf.getEndpoint(0);
		if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) {

			appendLogText("endpoint is not interrupt type");
			return;
		}

		mDevice = device;
		mEndpointIntr = ep;
		if (device != null) {
			UsbDeviceConnection connection = mUsbManager.openDevice(device);
			if (connection != null && connection.claimInterface(intf, true)) {
				appendLogText("open SUCCESS");
				mConnection = connection;
				Thread thread = new Thread(this);
				thread.start();

			} else {
				Log.d(TAG, "open FAIL");
				mConnection = null;
			}
		}
	}

	
	public void appendLogText(String string) {
		runOnUiThread(new Runnable(){
			@Override
			public void run(){
				Log.e(TAG, string);
				logTextView.setVerticalScrollbarPosition(0);
				logTextView.setText(string+"\n"+logTextView.getText());		
			}
		});
	}

	private void sendCommand(int control) {
		if (mConnection != null) {
			synchronized (this) {

				byte[] message = new byte[8];
				message[0] = 0x2;
				message[2] = 0;
				message[3] = 0;
				message[4] = 0;
				message[5] = 0;
				message[6] = 0;
				message[7] = 0;
				switch (control) {
				case COMMAND_DOWN:
					message[1] = 0x1;
					break;
				case COMMAND_LEFT:
					message[1] = 0x4;
					break;
				case COMMAND_UP:
					message[1] = 0x2;
					break;
				case COMMAND_RIGHT:
					message[1] = 0x8;
					break;
				case COMMAND_FIRE:
					message[1] = 0x10;
					break;
				case COMMAND_STOP:
					message[1] = 0x20;
					break;
				default:

				}
				mConnection.controlTransfer(0x21, 0x9, 0x200, 0, message, message.length, 0);

			}
		}
		else{
			Log.d(TAG, "don«t sendMove " + control);
		}
	}

	public void onClick(View v) {

	}

	private int mLastValue = 0;

	SensorEventListener mGravityListener = new SensorEventListener() {
		public void onSensorChanged(SensorEvent event) {

			// compute current tilt
			int value = 0;
			if (event.values[0] < -THRESHOLD) {
				value += TILT_LEFT;
			} else if (event.values[0] > THRESHOLD) {
				value += TILT_RIGHT;
			}
			if (event.values[1] < -THRESHOLD) {
				value += TILT_UP;
			} else if (event.values[1] > THRESHOLD) {
				value += TILT_DOWN;
			}

			if (value != mLastValue) {
				mLastValue = value;
				// send motion command if the tilt changed
				switch (value) {
				case TILT_LEFT:
					sendCommand(COMMAND_RIGHT);
					break;
				case TILT_RIGHT:
					sendCommand(COMMAND_LEFT);
					break;
				case TILT_UP:
					sendCommand(COMMAND_DOWN);
					break;
				case TILT_DOWN:
					sendCommand(COMMAND_UP);
					break;
				default:
					sendCommand(COMMAND_STOP);
					break;
				}
			}
		}

		public void onAccuracyChanged(Sensor sensor, int accuracy) {
			// ignore
		}
	};

	@Override
	public void run() {
		ByteBuffer buffer = ByteBuffer.allocate(1);
		UsbRequest request = new UsbRequest();
		request.initialize(mConnection, mEndpointIntr);
		byte status = -1;
		while (true) {
			// queue a request on the interrupt endpoint
			request.queue(buffer, 1);
			// send poll status command
			sendCommand(COMMAND_STATUS);
			// wait for status event
			if (mConnection.requestWait() == request) {
				byte newStatus = buffer.get(0);
				appendLogText("got newStatus " + newStatus);
				if (newStatus != status) {
					appendLogText( "got status " + newStatus);
					status = newStatus;
					if ((status & COMMAND_FIRE) != 0) {
						// stop firing
						sendCommand(COMMAND_STOP);
					}
				}
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
				}
			} else {
				appendLogText("requestWait failed, exiting");
				break;
			}
		}
	}

	@Override
	public boolean onTouch(View v, MotionEvent event) {
		if(event.getAction() == MotionEvent.ACTION_DOWN){
			if (v == mMoveDown) {
				sendCommand(COMMAND_DOWN);
			}
			else if (v == mMoveLeft) {
				sendCommand(COMMAND_LEFT);
			}
			else if (v == mMoveRight) {
				sendCommand(COMMAND_RIGHT);
			}
			else if (v == mMoveUp) {
				sendCommand(COMMAND_UP);
			}
			else if (v == mFireButton) {
				sendCommand(COMMAND_FIRE);
			}
		}
		else if(event.getAction() == MotionEvent.ACTION_UP){
			sendCommand(COMMAND_STOP);
		}
		return false;
	}
}


