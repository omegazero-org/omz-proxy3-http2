/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2.util;

public class FrameUtil {


	public static int readInt16BE(byte[] data, int offset) {
		return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
	}

	public static void writeInt16BE(byte[] data, int offset, int value) {
		data[offset] = (byte) (value >> 8);
		data[offset + 1] = (byte) value;
	}

	public static int readInt32BE(byte[] data, int offset) {
		return ((data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16) | ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
	}

	public static void writeInt32BE(byte[] data, int offset, int value) {
		data[offset] = (byte) (value >> 24);
		data[offset + 1] = (byte) (value >> 16);
		data[offset + 2] = (byte) (value >> 8);
		data[offset + 3] = (byte) value;
	}

	public static byte[] int32BE(int value) {
		byte[] data = new byte[4];
		writeInt32BE(data, 0, value);
		return data;
	}
}
