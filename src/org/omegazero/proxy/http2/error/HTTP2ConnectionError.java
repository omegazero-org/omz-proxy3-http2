/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2.error;

import java.io.IOException;

public class HTTP2ConnectionError extends IOException {

	private static final long serialVersionUID = 1L;


	private final int status;
	private final boolean streamError;

	public HTTP2ConnectionError(int status) {
		this(status, false);
	}

	public HTTP2ConnectionError(int status, boolean streamError) {
		this(status, streamError, "Error " + status);
	}

	public HTTP2ConnectionError(int status, String msg) {
		this(status, false, msg);
	}

	public HTTP2ConnectionError(int status, boolean streamError, String msg) {
		super(msg);
		this.status = status;
		this.streamError = streamError;
	}


	public int getStatus() {
		return this.status;
	}

	public boolean isStreamError() {
		return this.streamError;
	}
}
