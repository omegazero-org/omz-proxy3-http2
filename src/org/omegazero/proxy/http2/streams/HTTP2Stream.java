/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2.streams;

import static org.omegazero.proxy.http2.core.HTTP2Constants.*;

import java.io.IOException;

import org.omegazero.common.logging.Logger;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http2.error.HTTP2ConnectionError;
import org.omegazero.proxy.http2.util.FrameUtil;

/**
 * Represents a HTTP/2 stream where frames can be sent or received.
 */
public abstract class HTTP2Stream {

	private static final Logger logger = Logger.create();


	protected final int streamId;
	protected final SocketConnection connection;

	protected Object windowSizeLock = new Object();
	protected int receiverWindowSize;
	protected int localWindowSize;

	public HTTP2Stream(int streamId, SocketConnection connection) {
		this.streamId = streamId;
		this.connection = connection;
	}


	public void receiveFrame(int type, int flags, byte[] data) throws IOException {
		if(type == FRAME_TYPE_WINDOW_UPDATE){
			if(data.length != 4)
				throw new HTTP2ConnectionError(STATUS_FRAME_SIZE_ERROR);
			int v = FrameUtil.readInt32BE(data, 0);
			if(v < 1)
				throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, true);
			synchronized(this.windowSizeLock){
				int nws = this.receiverWindowSize + v;
				if(nws < 0) // overflow
					nws = Integer.MAX_VALUE;
				this.receiverWindowSize = nws;
			}
			this.windowUpdate();
		}else if(type >= 0 && type < FRAME_TYPES)
			throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR);
	}

	public void writeFrame(int type, int flags, byte[] data) {
		writeFrame(this.connection, this.streamId, type, flags, data);
	}

	public void sendWindowSizeUpdate(int increment) {
		if(increment <= 0)
			throw new IllegalArgumentException("Invalid window size increment: " + increment);
		synchronized(this.windowSizeLock){
			int nws = this.localWindowSize + increment;
			if(nws < 0)
				nws = Integer.MAX_VALUE;
			this.localWindowSize = nws;
		}
		this.writeFrame(FRAME_TYPE_WINDOW_UPDATE, 0, FrameUtil.int32BE(increment));
	}


	protected void windowUpdate() {
	}


	public int getStreamId() {
		return this.streamId;
	}

	public SocketConnection getConnection() {
		return this.connection;
	}


	public static boolean isFlowControlledFrameType(int type) {
		return type == FRAME_TYPE_DATA;
	}

	protected static void writeFrame(SocketConnection connection, int streamId, int type, int flags, byte[] data) {
		if(logger.debug())
			logger.trace("local -> ", connection.getRemoteAddress(), " HTTP2 frame: stream=", streamId, " type=", type, " flags=", flags, " length=", data.length);
		byte[] frameHeader = new byte[FRAME_HEADER_SIZE];
		frameHeader[0] = (byte) (data.length >> 16);
		frameHeader[1] = (byte) (data.length >> 8);
		frameHeader[2] = (byte) data.length;
		frameHeader[3] = (byte) type;
		frameHeader[4] = (byte) flags;
		FrameUtil.writeInt32BE(frameHeader, 5, streamId);
		synchronized(connection){
			connection.writeQueue(frameHeader);
			connection.write(data);
		}
	}
}
