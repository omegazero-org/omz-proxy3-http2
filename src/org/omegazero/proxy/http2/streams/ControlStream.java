/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2.streams;

import static org.omegazero.proxy.http2.core.HTTP2Constants.*;

import java.io.IOException;
import java.util.Arrays;

import org.omegazero.common.logging.Logger;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http2.core.HTTP2Settings;
import org.omegazero.proxy.http2.error.HTTP2ConnectionError;
import org.omegazero.proxy.http2.util.FrameUtil;
import org.omegazero.proxy.http2.util.StreamCallback;

/**
 * Represents the connection {@link HTTP2Stream} where frames are exchanged that affect the entire HTTP/2 connection (stream with ID equal to 0).
 */
public class ControlStream extends HTTP2Stream {

	private static final Logger logger = Logger.create();


	private StreamCallback<HTTP2Settings> onSettingsUpdate;
	private Runnable onWindowUpdate;
	private boolean settingsReceived = false;
	private HTTP2Settings remoteSettings = new HTTP2Settings();

	public ControlStream(int streamId, SocketConnection connection, HTTP2Settings localSettings) {
		super(streamId, connection);

		super.receiverWindowSize = this.remoteSettings.get(SETTINGS_INITIAL_WINDOW_SIZE);
		super.localWindowSize = localSettings.get(SETTINGS_INITIAL_WINDOW_SIZE);
	}


	public void sendGoaway(int highestStreamId, int errorCode) {
		byte[] payload = new byte[8];
		FrameUtil.writeInt32BE(payload, 0, highestStreamId);
		FrameUtil.writeInt32BE(payload, 4, errorCode);
		super.writeFrame(FRAME_TYPE_GOAWAY, 0, payload);
	}

	public void writeSettings(HTTP2Settings settings) {
		byte[] buf = new byte[SETTINGS_COUNT * 6];
		int buflen = 0;
		for(int i = 1; i < SETTINGS_COUNT; i++){
			if(settings.get(i) != HTTP2Settings.getDefault(i)){
				FrameUtil.writeInt16BE(buf, buflen, i);
				FrameUtil.writeInt32BE(buf, buflen + 2, settings.get(i));
				buflen += 6;
			}
		}
		super.writeFrame(FRAME_TYPE_SETTINGS, 0, Arrays.copyOf(buf, buflen));
	}


	@Override
	public void receiveFrame(int type, int flags, byte[] data) throws IOException {
		if(type == FRAME_TYPE_SETTINGS){
			if((flags & FRAME_FLAG_ANY_ACK) != 0){
				if(data.length > 0)
					throw new HTTP2ConnectionError(STATUS_FRAME_SIZE_ERROR);
				return;
			}
			int settingsCount = data.length / 6;
			if(settingsCount * 6 != data.length)
				throw new HTTP2ConnectionError(STATUS_FRAME_SIZE_ERROR);
			for(int i = 0; i < settingsCount; i++){
				int setting = FrameUtil.readInt16BE(data, i * 6);
				int value = FrameUtil.readInt32BE(data, i * 6 + 2);
				if(setting < 0 || setting >= SETTINGS_COUNT)
					continue;
				if(setting == SETTINGS_ENABLE_PUSH && !(value == 0 || value == 1))
					throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, "ENABLE_PUSH is invalid");
				if(setting == SETTINGS_MAX_FRAME_SIZE && (value < SETTINGS_MAX_FRAME_SIZE_MIN || value > SETTINGS_MAX_FRAME_SIZE_MAX))
					throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, "MAX_FRAME_SIZE is invalid");
				if(setting == SETTINGS_INITIAL_WINDOW_SIZE && value < 0)
					throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, "INITIAL_WINDOW_SIZE is invalid");
				logger.trace(super.connection.getRemoteAddress(), " SETTINGS: " + setting + " = " + value);
				this.remoteSettings.set(setting, value);
			}
			this.settingsReceived = true;
			logger.debug(super.connection.getRemoteAddress(), " Successfully received and processed SETTINGS frame, containing ", settingsCount, " settings");
			super.writeFrame(FRAME_TYPE_SETTINGS, FRAME_FLAG_ANY_ACK, new byte[0]);
			this.onSettingsUpdate.accept(this.remoteSettings);
		}else if(type == FRAME_TYPE_PING){
			if((flags & FRAME_FLAG_ANY_ACK) != 0)
				return;
			if(data.length != 8)
				throw new HTTP2ConnectionError(STATUS_FRAME_SIZE_ERROR);
			logger.trace(super.connection.getRemoteAddress(), " Received PING request");
			super.writeFrame(FRAME_TYPE_PING, FRAME_FLAG_ANY_ACK, data);
		}else if(type == FRAME_TYPE_GOAWAY){
			if(data.length < 8)
				throw new HTTP2ConnectionError(STATUS_FRAME_SIZE_ERROR);
			int lastStreamId = FrameUtil.readInt32BE(data, 0) & 0x7fffffff;
			int errorCode = FrameUtil.readInt32BE(data, 4);
			logger.debug(super.connection.getRemoteAddress(), " Received GOAWAY frame with error code ", errorCode, " and lastStreamId=", lastStreamId);
		}else
			super.receiveFrame(type, flags, data);
	}

	@Override
	protected void windowUpdate() {
		if(this.onWindowUpdate != null)
			this.onWindowUpdate.run();
	}


	/**
	 * 
	 * @param onSettingsUpdate A callback that is called when a <i>SETTINGS</i> frame is received by the remote endpoint
	 */
	public void setOnSettingsUpdate(StreamCallback<HTTP2Settings> onSettingsUpdate) {
		this.onSettingsUpdate = onSettingsUpdate;
	}

	/**
	 * 
	 * @param onSettingsUpdate A callback that is called when a <i>WINDOW_UPDATE</i> frame is received by the remote endpoint
	 */
	public void setOnWindowUpdate(Runnable onWindowUpdate) {
		this.onWindowUpdate = onWindowUpdate;
	}

	/**
	 * 
	 * @return <code>true</code> if a <i>SETTINGS</i> frame was received by the remote endpoint
	 */
	public boolean isSettingsReceived() {
		return this.settingsReceived;
	}

	/**
	 * 
	 * @return {@link HTTP2Settings} sent by the remote endpoint
	 */
	public HTTP2Settings getRemoteSettings() {
		return this.remoteSettings;
	}


	/**
	 * 
	 * @return The connection window size of the remote endpoint
	 */
	public int getReceiverWindowSize() {
		return super.receiverWindowSize;
	}

	/**
	 * 
	 * @return The connection window size of the local endpoint
	 */
	public int getLocalWindowSize() {
		return super.localWindowSize;
	}

	/**
	 * Reduces the remote endpoints connection window by the given <b>size</b> because a flow-controlled packet is being sent.
	 * 
	 * @param size The size of the payload of the flow-controlled packet
	 */
	public void consumeReceiverConnectionWindow(int size) {
		synchronized(super.windowSizeLock){
			if(size > super.receiverWindowSize)
				throw new IllegalStateException("size is larger than receiver window size");
			super.receiverWindowSize -= size;
		}
	}

	/**
	 * Reduces the local connection window by the given <b>size</b> because a flow-controlled packet was received.
	 * 
	 * @param size The size of the payload of the flow-controlled packet
	 */
	public void consumeLocalConnectionWindow(int size) {
		synchronized(super.windowSizeLock){
			if(size > super.localWindowSize)
				throw new IllegalStateException("size is larger than local window size");
			super.localWindowSize -= size;
		}
	}
}
