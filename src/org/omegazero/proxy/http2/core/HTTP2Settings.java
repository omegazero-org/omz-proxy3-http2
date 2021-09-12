/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2.core;

import static org.omegazero.proxy.http2.core.HTTP2Constants.*;

import java.util.Arrays;

public class HTTP2Settings {

	private static final int[] DEFAULT_SETTINGS = new int[SETTINGS_COUNT];


	private final int[] data;

	public HTTP2Settings() {
		this(getDefaultSettingsData());
	}

	public HTTP2Settings(int[] data) {
		this.data = data;
	}

	public HTTP2Settings(HTTP2Settings settings) {
		this.data = Arrays.copyOf(settings.data, settings.data.length);
	}


	public int get(int setting) {
		if(setting < 0 || setting >= SETTINGS_COUNT)
			throw new IllegalArgumentException("Invalid setting number: " + setting);
		return this.data[setting];
	}

	public void set(int setting, int value) {
		if(setting < 0 || setting >= SETTINGS_COUNT)
			throw new IllegalArgumentException("Invalid setting number: " + setting);
		this.data[setting] = value;
	}


	public static int getDefault(int setting) {
		if(setting < 0 || setting >= SETTINGS_COUNT)
			throw new IllegalArgumentException("Invalid setting number: " + setting);
		return DEFAULT_SETTINGS[setting];
	}

	public static int[] getDefaultSettingsData() {
		return Arrays.copyOf(DEFAULT_SETTINGS, DEFAULT_SETTINGS.length);
	}


	static{
		DEFAULT_SETTINGS[SETTINGS_HEADER_TABLE_SIZE] = 4096;
		DEFAULT_SETTINGS[SETTINGS_ENABLE_PUSH] = 1;
		DEFAULT_SETTINGS[SETTINGS_MAX_CONCURRENT_STREAMS] = Integer.MAX_VALUE;
		DEFAULT_SETTINGS[SETTINGS_INITIAL_WINDOW_SIZE] = 65535;
		DEFAULT_SETTINGS[SETTINGS_MAX_FRAME_SIZE] = SETTINGS_MAX_FRAME_SIZE_MIN;
		DEFAULT_SETTINGS[SETTINGS_MAX_HEADER_LIST_SIZE] = Integer.MAX_VALUE;
	}
}
