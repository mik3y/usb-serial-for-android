/* Copyright 2014 Andreas Butti
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Driver for CH340, maybe also working with CH341, but not tested
 * See http://wch-ic.com/product/usb/ch340.asp
 *
 * @author Andreas Butti
 */
public class Ch34xSerialDriver implements UsbSerialDriver {

	private static final String TAG = Ch34xSerialDriver.class.getSimpleName();

	private final UsbDevice mDevice;
	private final UsbSerialPort mPort;

	private static final int LCR_ENABLE_RX   = 0x80;
	private static final int LCR_ENABLE_TX   = 0x40;
	private static final int LCR_MARK_SPACE  = 0x20;
	private static final int LCR_PAR_EVEN    = 0x10;
	private static final int LCR_ENABLE_PAR  = 0x08;
	private static final int LCR_STOP_BITS_2 = 0x04;
	private static final int LCR_CS8         = 0x03;
	private static final int LCR_CS7         = 0x02;
	private static final int LCR_CS6         = 0x01;
	private static final int LCR_CS5         = 0x00;

	public Ch34xSerialDriver(UsbDevice device) {
		mDevice = device;
		mPort = new Ch340SerialPort(mDevice, 0);
	}

	@Override
	public UsbDevice getDevice() {
		return mDevice;
	}

	@Override
	public List<UsbSerialPort> getPorts() {
		return Collections.singletonList(mPort);
	}

	public class Ch340SerialPort extends CommonUsbSerialPort {

		private static final int USB_TIMEOUT_MILLIS = 5000;

		private final int DEFAULT_BAUD_RATE = 9600;

		private boolean dtr = false;
		private boolean rts = false;

		private final Boolean mEnableAsyncReads;
		private UsbEndpoint mReadEndpoint;
		private UsbEndpoint mWriteEndpoint;
		private UsbRequest mUsbRequest;

		public Ch340SerialPort(UsbDevice device, int portNumber) {
			super(device, portNumber);
			mEnableAsyncReads = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1);
		}

		@Override
		public UsbSerialDriver getDriver() {
			return Ch34xSerialDriver.this;
		}

		@Override
		public void open(UsbDeviceConnection connection) throws IOException {
			if (mConnection != null) {
				throw new IOException("Already opened.");
			}

			mConnection = connection;
			boolean opened = false;
			try {
				for (int i = 0; i < mDevice.getInterfaceCount(); i++) {
					UsbInterface usbIface = mDevice.getInterface(i);
					if (!mConnection.claimInterface(usbIface, true)) {
						throw new IOException("Could not claim data interface.");
					}
				}

				UsbInterface dataIface = mDevice.getInterface(mDevice.getInterfaceCount() - 1);
				for (int i = 0; i < dataIface.getEndpointCount(); i++) {
					UsbEndpoint ep = dataIface.getEndpoint(i);
					if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
						if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
							mReadEndpoint = ep;
						} else {
							mWriteEndpoint = ep;
						}
					}
				}

				if (mEnableAsyncReads) {
					Log.d(TAG, "Async reads enabled");
				} else {
					Log.d(TAG, "Async reads disabled.");
				}

				initialize();
				setBaudRate(DEFAULT_BAUD_RATE);

				opened = true;
			} finally {
				if (!opened) {
					try {
						close();
					} catch (IOException e) {
						// Ignore IOExceptions during close()
					}
				}
			}
		}

		@Override
		public void close() throws IOException {
			if (mConnection == null) {
				throw new IOException("Already closed");
			}
			synchronized (mEnableAsyncReads) {
				if (mUsbRequest != null)
					mUsbRequest.cancel();
			}
			// TODO: nothing sended on close, maybe needed?

			try {
				mConnection.close();
			} finally {
				mConnection = null;
			}
		}


		@Override
		public int read(byte[] dest, int timeoutMillis) throws IOException {
			if (mEnableAsyncReads) {
				final UsbRequest request = new UsbRequest();
				try {
					request.initialize(mConnection, mReadEndpoint);
					final ByteBuffer buf = ByteBuffer.wrap(dest);
					if (!request.queue(buf, dest.length)) {
						throw new IOException("Error queueing request.");
					}
					mUsbRequest = request;
					final UsbRequest response = mConnection.requestWait();
					synchronized (mEnableAsyncReads) {
						mUsbRequest = null;
					}
					if (response == null) {
						throw new IOException("Null response");
					}

					final int nread = buf.position();
					if (nread > 0) {
						//Log.d(TAG, HexDump.dumpHexString(dest, 0, Math.min(32, dest.length)));
						return nread;
					} else {
						return 0;
					}
				} finally {
					mUsbRequest = null;
					request.close();
				}
			} else {
				final int numBytesRead;
				synchronized (mReadBufferLock) {
					int readAmt = Math.min(dest.length, mReadBuffer.length);
					numBytesRead = mConnection.bulkTransfer(mReadEndpoint, mReadBuffer, readAmt,
							timeoutMillis);
					if (numBytesRead < 0) {
						// This sucks: we get -1 on timeout, not 0 as preferred.
						// We *should* use UsbRequest, except it has a bug/api oversight
						// where there is no way to determine the number of bytes read
						// in response :\ -- http://b.android.com/28023
						return 0;
					}
					System.arraycopy(mReadBuffer, 0, dest, 0, numBytesRead);
				}
				return numBytesRead;
			}
		}

		@Override
		public int write(byte[] src, int timeoutMillis) throws IOException {
			int offset = 0;

			while (offset < src.length) {
				final int writeLength;
				final int amtWritten;

				synchronized (mWriteBufferLock) {
					final byte[] writeBuffer;

					writeLength = Math.min(src.length - offset, mWriteBuffer.length);
					if (offset == 0) {
						writeBuffer = src;
					} else {
						// bulkTransfer does not support offsets, make a copy.
						System.arraycopy(src, offset, mWriteBuffer, 0, writeLength);
						writeBuffer = mWriteBuffer;
					}

					amtWritten = mConnection.bulkTransfer(mWriteEndpoint, writeBuffer, writeLength,
							timeoutMillis);
				}
				if (amtWritten <= 0) {
					throw new IOException("Error writing " + writeLength
							+ " bytes at offset " + offset + " length=" + src.length);
				}

				Log.d(TAG, "Wrote amt=" + amtWritten + " attempted=" + writeLength);
				offset += amtWritten;
			}
			return offset;
		}

		private int controlOut(int request, int value, int index) {
			final int REQTYPE_HOST_TO_DEVICE = 0x41;
			return mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, request,
					value, index, null, 0, USB_TIMEOUT_MILLIS);
		}


		private int controlIn(int request, int value, int index, byte[] buffer) {
			final int REQTYPE_HOST_TO_DEVICE = UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_IN;
			return mConnection.controlTransfer(REQTYPE_HOST_TO_DEVICE, request,
					value, index, buffer, buffer.length, USB_TIMEOUT_MILLIS);
		}


		private void checkState(String msg, int request, int value, int[] expected) throws IOException {
			byte[] buffer = new byte[expected.length];
			int ret = controlIn(request, value, 0, buffer);

			if (ret < 0) {
				throw new IOException("Faild send cmd [" + msg + "]");
			}

			if (ret != expected.length) {
				throw new IOException("Expected " + expected.length + " bytes, but get " + ret + " [" + msg + "]");
			}

			for (int i = 0; i < expected.length; i++) {
				if (expected[i] == -1) {
					continue;
				}

				int current = buffer[i] & 0xff;
				if (expected[i] != current) {
					throw new IOException("Expected 0x" + Integer.toHexString(expected[i]) + " bytes, but get 0x" + Integer.toHexString(current) + " [" + msg + "]");
				}
			}
		}

		private void writeHandshakeByte() throws IOException {
			if (controlOut(0xa4, ~((dtr ? 1 << 5 : 0) | (rts ? 1 << 6 : 0)), 0) < 0) {
				throw new IOException("Faild to set handshake byte");
			}
		}

		private void initialize() throws IOException {
			checkState("init #1", 0x5f, 0, new int[]{-1 /* 0x27, 0x30 */, 0x00});

			if (controlOut(0xa1, 0, 0) < 0) {
				throw new IOException("init failed! #2");
			}

			setBaudRate(DEFAULT_BAUD_RATE);

			checkState("init #4", 0x95, 0x2518, new int[]{-1 /* 0x56, c3*/, 0x00});

			if (controlOut(0x9a, 0x2518, LCR_ENABLE_RX | LCR_ENABLE_TX | LCR_CS8) < 0) {
				throw new IOException("init failed! #5");
			}

			checkState("init #6", 0x95, 0x0706, new int[]{0xff, 0xee});

			if (controlOut(0xa1, 0x501f, 0xd90a) < 0) {
				throw new IOException("init failed! #7");
			}

			setBaudRate(DEFAULT_BAUD_RATE);

			writeHandshakeByte();

			checkState("init #10", 0x95, 0x0706, new int[]{-1/* 0x9f, 0xff*/, 0xee});
		}


		private void setBaudRate(int baudRate) throws IOException {
			final long CH341_BAUDBASE_FACTOR = 1532620800;
			final int CH341_BAUDBASE_DIVMAX = 3;

			long factor = CH341_BAUDBASE_FACTOR / baudRate;
			int divisor = CH341_BAUDBASE_DIVMAX;

			while ((factor > 0xfff0) && divisor > 0) {
				factor >>= 3;
				divisor--;
					}

			if (factor > 0xfff0) {
				throw new IOException("Baudrate " + baudRate + " not supported");
			}

			factor = 0x10000 - factor;

			int ret = controlOut(0x9a, 0x1312, (int) ((factor & 0xff00) | divisor));
			if (ret < 0) {
				throw new IOException("Error setting baud rate. #1)");
			}

			ret = controlOut(0x9a, 0x0f2c, (int) (factor & 0xff));
			if (ret < 0) {
				throw new IOException("Error setting baud rate. #2");
			}
		}

		@Override
		public void setParameters(int baudRate, int dataBits, int stopBits, int parity)
				throws IOException {
			setBaudRate(baudRate);

			int lcr = LCR_ENABLE_RX | LCR_ENABLE_TX;

			switch (dataBits) {
				case DATABITS_5:
					lcr |= LCR_CS5;
					break;
				case DATABITS_6:
					lcr |= LCR_CS6;
					break;
				case DATABITS_7:
					lcr |= LCR_CS7;
					break;
				case DATABITS_8:
					lcr |= LCR_CS8;
					break;
				default:
					throw new IllegalArgumentException("Unknown dataBits value: " + dataBits);
			}

			switch (parity) {
				case PARITY_NONE:
					break;
				case PARITY_ODD:
					lcr |= LCR_ENABLE_PAR;
					break;
				case PARITY_EVEN:
					lcr |= LCR_ENABLE_PAR | LCR_PAR_EVEN;
					break;
				case PARITY_MARK:
					lcr |= LCR_ENABLE_PAR | LCR_MARK_SPACE;
					break;
				case PARITY_SPACE:
					lcr |= LCR_ENABLE_PAR | LCR_MARK_SPACE | LCR_PAR_EVEN;
					break;
				default:
					throw new IllegalArgumentException("Unknown parity value: " + parity);
			}

			switch (stopBits) {
				case STOPBITS_1:
					break;
				case STOPBITS_1_5:
					throw new IllegalArgumentException("Unsupported stopBits value: 1.5");
				case STOPBITS_2:
					lcr |= LCR_STOP_BITS_2;
					break;
				default:
					throw new IllegalArgumentException("Unknown stopBits value: " + stopBits);
			}

			int ret = controlOut(0x9a, 0x2518, lcr);
			if (ret < 0) {
				throw new IOException("Error setting control byte");
			}
		}

		@Override
		public boolean getCD() throws IOException {
			return false;
		}

		@Override
		public boolean getCTS() throws IOException {
			return false;
		}

		@Override
		public boolean getDSR() throws IOException {
			return false;
		}

		@Override
		public boolean getDTR() throws IOException {
			return dtr;
		}

		@Override
		public void setDTR(boolean value) throws IOException {
			dtr = value;
			writeHandshakeByte();
		}

		@Override
		public boolean getRI() throws IOException {
			return false;
		}

		@Override
		public boolean getRTS() throws IOException {
			return rts;
		}

		@Override
		public void setRTS(boolean value) throws IOException {
			rts = value;
			writeHandshakeByte();
		}

		@Override
		public boolean purgeHwBuffers(boolean purgeReadBuffers, boolean purgeWriteBuffers) throws IOException {
			return true;
		}

	}

	public static Map<Integer, int[]> getSupportedDevices() {
		final Map<Integer, int[]> supportedDevices = new LinkedHashMap<Integer, int[]>();
		supportedDevices.put(UsbId.VENDOR_QINHENG, new int[]{
				UsbId.QINHENG_HL340
		});
		return supportedDevices;
	}

}