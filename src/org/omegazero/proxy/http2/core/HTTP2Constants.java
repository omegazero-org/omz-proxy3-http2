/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2.core;

public class HTTP2Constants {

	public static final byte[] CLIENT_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

	public static final int FRAME_HEADER_SIZE = 9;

	public static final int FRAME_TYPE_DATA = 0x0;
	public static final int FRAME_TYPE_HEADERS = 0x1;
	public static final int FRAME_TYPE_PRIORITY = 0x2;
	public static final int FRAME_TYPE_RST_STREAM = 0x3;
	public static final int FRAME_TYPE_SETTINGS = 0x4;
	public static final int FRAME_TYPE_PUSH_PROMISE = 0x5;
	public static final int FRAME_TYPE_PING = 0x6;
	public static final int FRAME_TYPE_GOAWAY = 0x7;
	public static final int FRAME_TYPE_WINDOW_UPDATE = 0x8;
	public static final int FRAME_TYPE_CONTINUATION = 0x9;
	public static final int FRAME_TYPES = 0xa;

	public static final int FRAME_FLAG_ANY_END_STREAM = 0x1;
	public static final int FRAME_FLAG_ANY_END_HEADERS = 0x4;
	public static final int FRAME_FLAG_ANY_PADDED = 0x8;
	public static final int FRAME_FLAG_ANY_ACK = 0x1;
	public static final int FRAME_FLAG_HEADERS_PRIORITY = 0x20;

	public static final int STATUS_NO_ERROR = 0x0;
	public static final int STATUS_PROTOCOL_ERROR = 0x1;
	public static final int STATUS_INTERNAL_ERROR = 0x2;
	public static final int STATUS_FLOW_CONTROL_ERROR = 0x3;
	public static final int STATUS_SETTINGS_TIMEOUT = 0x4;
	public static final int STATUS_STREAM_CLOSED = 0x5;
	public static final int STATUS_FRAME_SIZE_ERROR = 0x6;
	public static final int STATUS_REFUSED_STREAM = 0x7;
	public static final int STATUS_CANCEL = 0x8;
	public static final int STATUS_COMPRESSION_ERROR = 0x9;
	public static final int STATUS_CONNECT_ERROR = 0xa;
	public static final int STATUS_ENHANCE_YOUR_CALM = 0xb;
	public static final int STATUS_INADEQUATE_SECURITY = 0xc;
	public static final int STATUS_HTTP_1_1_REQUIRED = 0xd;

	public static final int SETTINGS_HEADER_TABLE_SIZE = 0x1;
	public static final int SETTINGS_ENABLE_PUSH = 0x2;
	public static final int SETTINGS_MAX_CONCURRENT_STREAMS = 0x3;
	public static final int SETTINGS_INITIAL_WINDOW_SIZE = 0x4;
	public static final int SETTINGS_MAX_FRAME_SIZE = 0x5;
	public static final int SETTINGS_MAX_HEADER_LIST_SIZE = 0x6;
	public static final int SETTINGS_COUNT = 0x7;

	public static final int SETTINGS_MAX_FRAME_SIZE_MIN = 16384;
	public static final int SETTINGS_MAX_FRAME_SIZE_MAX = 16777215;
}
