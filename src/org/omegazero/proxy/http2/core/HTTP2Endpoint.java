/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2.core;

import java.io.IOException;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;

import org.omegazero.common.logging.Logger;
import org.omegazero.net.common.NetCommon;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http2.error.HTTP2ConnectionError;
import org.omegazero.proxy.http2.hpack.HPackContext;
import org.omegazero.proxy.http2.streams.ControlStream;
import org.omegazero.proxy.http2.streams.HTTP2Stream;
import org.omegazero.proxy.http2.streams.MessageStream;
import org.omegazero.proxy.http2.util.FrameUtil;

public abstract class HTTP2Endpoint {

	private static final Logger logger = Logger.create();


	protected final SocketConnection connection;
	protected final HTTP2Settings settings;
	protected final HPackContext hpack;

	private final byte[] frameBuffer;
	private int frameBufferSize = 0;
	private int frameExpectedSize = 0;

	protected Map<Integer, HTTP2Stream> streams = new java.util.concurrent.ConcurrentHashMap<>();
	protected Deque<MessageStream> closeWaitStreams = new java.util.LinkedList<>();
	protected int highestStreamId = 0;

	protected long closeWaitTimeout = 5000000000L;

	public HTTP2Endpoint(SocketConnection connection, HTTP2Settings settings, HPackContext.Session hpackSession, boolean useHuffmanEncoding) {
		this(connection, settings, new HPackContext(hpackSession, useHuffmanEncoding, settings.get(HTTP2Constants.SETTINGS_HEADER_TABLE_SIZE)));
	}

	public HTTP2Endpoint(SocketConnection connection, HTTP2Settings settings, HPackContext hpack) {
		this.connection = connection;
		this.settings = settings;
		this.hpack = hpack;

		this.frameBuffer = new byte[settings.get(HTTP2Constants.SETTINGS_MAX_FRAME_SIZE) + HTTP2Constants.FRAME_HEADER_SIZE];

		this.connection.setOnWritable(this::handleConnectionWindowUpdate);
	}


	protected abstract HTTP2Stream newStreamForFrame(int streamId, int type, int flags, byte[] payload) throws IOException;


	public void processData(byte[] data) {
		int index = 0;
		while(index < data.length){
			index = this.processData0(data, index);
			if(index < 0)
				break;
		}
	}

	protected final int processData0(byte[] data, int index) {
		int len = this.assembleFrame(data, index);
		if(len < 0){
			this.getControlStream().sendGoaway(this.highestStreamId, HTTP2Constants.STATUS_FRAME_SIZE_ERROR);
			this.connection.close();
			return -1;
		}else
			index += len;
		if(!this.connection.isConnected())
			return -1;
		return index;
	}


	private int assembleFrame(byte[] data, int offset) {
		if(this.frameBufferSize == 0){
			if(data.length - offset < HTTP2Constants.FRAME_HEADER_SIZE)
				return -1;
			int length = ((data[offset] & 0xff) << 16) | ((data[offset + 1] & 0xff) << 8) | (data[offset + 2] & 0xff);
			if(length > this.settings.get(HTTP2Constants.SETTINGS_MAX_FRAME_SIZE))
				return -1;
			this.frameExpectedSize = length + HTTP2Constants.FRAME_HEADER_SIZE;
		}
		int remaining = Math.min(data.length - offset, this.frameExpectedSize - this.frameBufferSize);
		System.arraycopy(data, offset, this.frameBuffer, this.frameBufferSize, remaining);
		this.frameBufferSize += remaining;
		if(this.frameBufferSize == this.frameExpectedSize){
			this.processFrame();
			this.frameBufferSize = 0;
		}
		return remaining;
	}

	private void processFrame() {
		this.purgeClosedStreams();
		int type = this.frameBuffer[3];
		int flags = this.frameBuffer[4];
		int streamId = FrameUtil.readInt32BE(this.frameBuffer, 5) & 0x7fffffff;
		byte[] payload = Arrays.copyOfRange(this.frameBuffer, HTTP2Constants.FRAME_HEADER_SIZE, this.frameExpectedSize);
		if(logger.debug())
			logger.trace(this.connection.getRemoteAddress(), " -> local HTTP2 frame: stream=", streamId, " type=", type, " flags=", flags, " length=", payload.length);
		HTTP2Stream stream = this.streams.get(streamId);
		try{
			ControlStream controlStream = this.getControlStream();
			if(stream == null){
				stream = this.newStreamForFrame(streamId, type, flags, payload);
				if(stream != null){
					this.highestStreamId = streamId;
					this.streams.put(streamId, stream);
				}
			}
			if(stream != null){
				if(!controlStream.isSettingsReceived() && type != HTTP2Constants.FRAME_TYPE_SETTINGS)
					throw new HTTP2ConnectionError(HTTP2Constants.STATUS_PROTOCOL_ERROR);
				stream.receiveFrame(type, flags, payload);
				if(HTTP2Stream.isFlowControlledFrameType(type) && payload.length > 0){ // what is the purpose of this
					controlStream.consumeLocalConnectionWindow(payload.length);
					if(controlStream.getLocalWindowSize() < 0x1000000)
						controlStream.sendWindowSizeUpdate(0x1000000);
				}
			}else if(type != HTTP2Constants.FRAME_TYPE_PRIORITY){
				throw new HTTP2ConnectionError(HTTP2Constants.STATUS_PROTOCOL_ERROR);
			}
		}catch(Exception e){
			if(e instanceof HTTP2ConnectionError){
				HTTP2ConnectionError h2e = (HTTP2ConnectionError) e;
				if(logger.debug())
					logger.debug(this.connection.getRemoteAddress(), " HTTP2 ", h2e.isStreamError() ? "stream" : "connection", " error in stream ",
							(stream != null ? stream.getStreamId() : "(none)"), ": ", NetCommon.PRINT_STACK_TRACES ? e : e.toString());
				if(h2e.isStreamError() && (stream instanceof MessageStream)){
					((MessageStream) stream).rst(h2e.getStatus());
				}else{
					this.getControlStream().sendGoaway(this.highestStreamId, h2e.getStatus());
					this.connection.close();
				}
			}else{
				logger.warn(this.connection.getRemoteAddress(), " Error while processing frame: ", NetCommon.PRINT_STACK_TRACES ? e : e.toString());
				this.getControlStream().sendGoaway(this.highestStreamId, HTTP2Constants.STATUS_INTERNAL_ERROR);
				this.connection.close();
			}
		}
	}

	private void purgeClosedStreams() {
		if(this.closeWaitStreams.size() > 0){
			synchronized(this.closeWaitStreams){
				MessageStream ms;
				long time = System.nanoTime();
				while((ms = this.closeWaitStreams.peekFirst()) != null && time - ms.getCloseTime() > this.closeWaitTimeout){
					this.closeWaitStreams.removeFirst();
					this.streams.remove(ms.getStreamId());
				}
			}
		}
	}

	protected ControlStream getControlStream() {
		return (ControlStream) this.streams.get(0);
	}

	protected void checkRemoteCreateStream() throws HTTP2ConnectionError {
		if(this.streams.size() + 1 > this.settings.get(HTTP2Constants.SETTINGS_MAX_CONCURRENT_STREAMS))
			throw new HTTP2ConnectionError(HTTP2Constants.STATUS_ENHANCE_YOUR_CALM);
	}

	protected void registerStream(HTTP2Stream stream) {
		this.streams.put(stream.getStreamId(), stream);
	}

	protected void handleConnectionWindowUpdate() {
		for(HTTP2Stream s : this.streams.values()){
			if(s instanceof MessageStream)
				((MessageStream) s).windowUpdate();
		}
	}


	public SocketConnection getConnection() {
		return this.connection;
	}
}
