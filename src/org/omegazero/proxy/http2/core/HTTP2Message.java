/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2.core;

import java.util.Map;

import org.omegazero.proxy.http.HTTPMessage;

public class HTTP2Message extends HTTPMessage {

	private static final long serialVersionUID = 1L;


	private final int streamId;

	protected HTTP2Message(boolean request, int streamId) {
		super(request, null);
		this.streamId = streamId;
	}

	public HTTP2Message(int status, String version, Map<String, String> headers, int streamId) {
		super(status, version, headers);
		this.streamId = streamId;
	}

	public HTTP2Message(String method, String scheme, String authority, String path, String version, Map<String, String> headers, int streamId) {
		super(method, scheme, authority, path, version, headers);
		this.streamId = streamId;
	}


	public int getStreamId() {
		return this.streamId;
	}


	@Override
	public HTTP2Message clone() {
		return this.clone(this.streamId);
	}

	public HTTP2Message clone(int streamId) {
		HTTP2Message c = new HTTP2Message(super.isRequest(), streamId);
		super.cloneData(c);
		return c;
	}
}
