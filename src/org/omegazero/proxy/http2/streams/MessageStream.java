/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2.streams;

import static org.omegazero.proxy.http2.core.HTTP2Constants.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

import org.omegazero.common.logging.Logger;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http.HTTPMessage;
import org.omegazero.proxy.http.HTTPMessageData;
import org.omegazero.proxy.http.HTTPMessageTrailers;
import org.omegazero.proxy.http.HTTPValidator;
import org.omegazero.proxy.http2.core.HTTP2Message;
import org.omegazero.proxy.http2.core.HTTP2Settings;
import org.omegazero.proxy.http2.error.HTTP2ConnectionError;
import org.omegazero.proxy.http2.hpack.HPackContext;
import org.omegazero.proxy.http2.util.FrameUtil;
import org.omegazero.proxy.http2.util.StreamCallback;

/**
 * Represents any {@link HTTP2Stream} where HTTP messages are exchanged (streams with IDs higher than 0).
 */
public class MessageStream extends HTTP2Stream {

	private static final Logger logger = Logger.create();

	public static final int STATE_IDLE = 0;
	public static final int STATE_OPEN = 1;
	public static final int STATE_RESERVED_LOCAL = 2;
	public static final int STATE_RESERVED = 3;
	public static final int STATE_HALF_CLOSED_LOCAL = 4;
	public static final int STATE_HALF_CLOSED = 5;
	public static final int STATE_CLOSED = 6;


	private final ControlStream controlStream;
	private final HTTP2Settings remoteSettings;
	private final HTTP2Settings localSettings;
	private final HPackContext hpack;

	private int state = STATE_IDLE;
	private long closeTime;

	private boolean headersReceiving = false;
	private boolean headersEndStream = false;
	private ByteArrayOutputStream headersBuf = new ByteArrayOutputStream();
	private int promisedStreamId = -1;

	private HTTP2Message receivedMessage;
	private StreamCallback<HTTP2Message> onPushPromise;
	private StreamCallback<HTTPMessageData> onMessage;
	private StreamCallback<HTTPMessageData> onData;
	private StreamCallback<HTTPMessageTrailers> onTrailers;
	private Runnable onDataFlushed;
	private Consumer<Integer> onClosed;

	private boolean receiveData = true;
	private final java.util.Deque<QueuedDataFrame> dataBacklog = new java.util.LinkedList<>();

	public MessageStream(int streamId, SocketConnection connection, ControlStream controlStream, HTTP2Settings localSettings, HTTP2Settings remoteSettings,
			HPackContext hpack) {
		super(streamId, connection);

		super.receiverWindowSize = remoteSettings.get(SETTINGS_INITIAL_WINDOW_SIZE);
		super.localWindowSize = localSettings.get(SETTINGS_INITIAL_WINDOW_SIZE);

		this.controlStream = controlStream;
		this.remoteSettings = remoteSettings;
		this.localSettings = localSettings;
		this.hpack = hpack;
	}


	/**
	 * Sends the given {@link HTTPMessage} on this stream by encoding the data with the configured {@link HPackContext} and sending it in one <i>PUSH_PROMISE</i> and zero or
	 * more <i>CONTINUATION</i> frames.
	 * 
	 * @param promisedStreamId The promised stream ID where the promise response is going to be sent
	 * @param message          The request message to send
	 * @throws IllegalStateException If this stream is not in <code>STATE_HALF_CLOSED</code>
	 * @see #preparePush(boolean)
	 */
	public synchronized void sendPushPromise(int promisedStreamId, HTTPMessage message) {
		if(this.state != STATE_HALF_CLOSED)
			throw new IllegalStateException("Stream is not expecting a push promise");
		if(!message.isRequest())
			throw new IllegalArgumentException("message is not a request");

		this.writeMessage(FRAME_TYPE_PUSH_PROMISE, FrameUtil.int32BE(promisedStreamId), message, false);
	}

	/**
	 * Sends the given {@link HTTPMessage} on this stream by encoding the data with the configured {@link HPackContext} and sending it in one <i>HEADERS</i> and zero or more
	 * <i>CONTINUATION</i> frames.
	 * 
	 * @param message   The message to send
	 * @param endStream Whether the message should end the stream and no payload data will be sent
	 * @throws IllegalStateException If this stream is not in <code>STATE_IDLE</code> or <code>STATE_HALF_CLOSED</code>
	 */
	public synchronized void sendHTTPMessage(HTTPMessage message, boolean endStream) {
		if(this.state == STATE_IDLE)
			this.state = STATE_OPEN;
		else if(this.state != STATE_HALF_CLOSED && this.state != STATE_OPEN)
			// also allow STATE_OPEN here because plugins might try to respond before the full body is received by the client
			throw new IllegalStateException("Stream is not expecting a HTTP message");

		this.writeMessage(FRAME_TYPE_HEADERS, null, message, endStream);
	}

	/**
	 * Sends the given <b>trailers</b> on this stream by encoding the data with the configured {@link HPackContext} and sending it in one <i>HEADERS</i> and zero or more
	 * <i>CONTINUATION</i> frames.
	 * 
	 * @param trailers The trailers to send
	 * @throws IllegalStateException If the stream is not in <code>STATE_OPEN</code> or <code>STATE_HALF_CLOSED</code>
	 */
	public synchronized void sendTrailers(HTTPMessageTrailers htrailers) {
		if(this.state != STATE_OPEN && this.state != STATE_HALF_CLOSED)
			throw new IllegalStateException("Stream is not expecting trailers");

		java.util.Set<Map.Entry<String, String>> trailers = htrailers.getHeaderSet();
		HPackContext.EncoderContext context = new HPackContext.EncoderContext(trailers.size());
		for(Map.Entry<String, String> header : trailers){
			this.hpack.encodeHeader(context, header.getKey().toLowerCase(), header.getValue());
		}
		this.writeHeaders(FRAME_TYPE_HEADERS, context, true);
	}

	private void writeMessage(int type, byte[] prependData, HTTPMessage message, boolean endStream) {
		java.util.Set<Map.Entry<String, String>> headers = message.getHeaderSet();
		HPackContext.EncoderContext context = new HPackContext.EncoderContext(headers.size(), prependData);
		if(message.isRequest()){
			this.hpack.encodeHeader(context, ":method", message.getMethod());
			this.hpack.encodeHeader(context, ":scheme", message.getScheme());
			this.hpack.encodeHeader(context, ":authority", message.getAuthority());
			this.hpack.encodeHeader(context, ":path", message.getPath());
		}else{
			this.hpack.encodeHeader(context, ":status", Integer.toString(message.getStatus()));
		}
		for(Map.Entry<String, String> header : headers){
			this.hpack.encodeHeader(context, header.getKey().toLowerCase(), header.getValue());
		}

		this.writeHeaders(type, context, endStream);
	}

	private void writeHeaders(int type, HPackContext.EncoderContext context, boolean endStream) {
		int maxFrameSize = this.remoteSettings.get(SETTINGS_MAX_FRAME_SIZE);
		int index = 0;
		byte[] data = context.getEncodedData();
		if(data.length <= maxFrameSize){
			int flags = FRAME_FLAG_ANY_END_HEADERS;
			if(endStream)
				flags |= FRAME_FLAG_ANY_END_STREAM;
			this.writeFrame(type, flags, data);
		}else{
			boolean start = true;
			do{
				int nextSize = Math.min(data.length - index, maxFrameSize);
				byte[] frameData = Arrays.copyOfRange(data, index, index + nextSize);
				index += nextSize;
				if(start){
					int flags = 0;
					if(endStream)
						flags |= FRAME_FLAG_ANY_END_STREAM;
					this.writeFrame(type, flags, frameData);
					start = false;
				}else
					this.writeFrame(FRAME_TYPE_CONTINUATION, index == data.length ? FRAME_FLAG_ANY_END_HEADERS : 0, frameData);
			}while(index < data.length);
		}

		if(endStream)
			this.sentES();
	}

	/**
	 * Sends the given <b>data</b> on this stream in one or more <i>DATA</i> frames. If the receiver window size if not large enough to receive all data, it will be buffered
	 * and {@link #hasDataBacklog()} will start returning <code>true</code>, until a <i>WINDOW_UPDATE</i> frame is received.
	 * 
	 * @param data      The data to send
	 * @param endStream Whether this is the last data packet sent on this stream
	 * @return <code>true</code> If all data could be written because the receiver window size is large enough
	 * @throws IllegalStateException If the stream is not in <code>STATE_OPEN</code> or <code>STATE_HALF_CLOSED</code>
	 */
	public synchronized boolean sendData(byte[] data, boolean endStream) {
		if(this.state != STATE_OPEN && this.state != STATE_HALF_CLOSED)
			throw new IllegalStateException("Stream is not expecting data");

		boolean flushed = false;
		int maxFrameSize = this.remoteSettings.get(SETTINGS_MAX_FRAME_SIZE);
		int index = 0;
		if(data.length <= maxFrameSize){
			flushed = this.writeDataFrame(endStream, data);
		}else{
			do{
				int nextSize = Math.min(data.length - index, maxFrameSize);
				synchronized(this.windowSizeLock){
					if(super.receiverWindowSize > 0 && super.receiverWindowSize < nextSize)
						nextSize = super.receiverWindowSize;
				}
				byte[] frameData = Arrays.copyOfRange(data, index, index + nextSize);
				index += nextSize;
				flushed = this.writeDataFrame(endStream && index == data.length, frameData);
			}while(index < data.length);
		}

		return flushed;
	}

	private boolean writeDataFrame(boolean eos, byte[] data) {
		synchronized(this.windowSizeLock){
			int frameFlags = eos ? FRAME_FLAG_ANY_END_STREAM : 0;
			if(super.connection.isWritable() && this.dataBacklog.size() == 0 && this.canAcceptFlowControlledData(data.length)){
				this.writeFrame(FRAME_TYPE_DATA, frameFlags, data);
				if(eos)
					this.sentES();
				return true;
			}else{
				this.dataBacklog.add(new QueuedDataFrame(frameFlags, data));
				return false;
			}
		}
	}


	@Override
	public synchronized void receiveFrame(int type, int flags, byte[] data) throws IOException {
		if(HTTP2Stream.isFlowControlledFrameType(type)){
			synchronized(this.windowSizeLock){
				if(data.length > super.localWindowSize)
					throw new HTTP2ConnectionError(STATUS_FLOW_CONTROL_ERROR, true);
				super.localWindowSize -= data.length;
			}
		}
		if(this.headersReceiving && type != FRAME_TYPE_CONTINUATION)
			throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, "Expected CONTINUATION");
		if(type == FRAME_TYPE_PRIORITY){
			// currently unsupported
		}else if(type == FRAME_TYPE_HEADERS){
			if(this.state == STATE_IDLE) // incoming request (c->s)
				this.state = STATE_OPEN;
			else if(this.state == STATE_RESERVED) // incoming response after push promise (s->c)
				this.state = STATE_HALF_CLOSED_LOCAL;
			else if(this.state != STATE_HALF_CLOSED_LOCAL && this.state != STATE_OPEN) // incoming response (s->c) or incoming request trailers (c->s)
				throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, true);
			if(data.length < 1)
				throw new HTTP2ConnectionError(STATUS_FRAME_SIZE_ERROR);
			int index = 0;
			int padding = 0;
			if((flags & FRAME_FLAG_ANY_PADDED) != 0)
				padding = data[index++];
			if((flags & FRAME_FLAG_HEADERS_PRIORITY) != 0){
				if(data.length < 6)
					throw new HTTP2ConnectionError(STATUS_FRAME_SIZE_ERROR);
				index += 5;
			}
			if(padding > data.length - index)
				throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, "padding is too high");
			this.headersEndStream = (flags & FRAME_FLAG_ANY_END_STREAM) != 0;
			boolean eoh = (flags & FRAME_FLAG_ANY_END_HEADERS) != 0;
			byte[] fragment = Arrays.copyOfRange(data, index, data.length - padding);
			if(eoh){
				this.receiveHeaderBlock(fragment, this.headersEndStream);
			}else{
				this.headersReceiving = true;
				this.headersBuf.write(fragment);
			}
		}else if(type == FRAME_TYPE_PUSH_PROMISE){
			if(this.localSettings.get(SETTINGS_ENABLE_PUSH) == 0)
				throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, "PUSH is not enabled");
			if(this.state == STATE_HALF_CLOSED_LOCAL)
				this.state = STATE_RESERVED;
			else if(this.state != STATE_RESERVED)
				throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR);
			if(data.length < 4)
				throw new HTTP2ConnectionError(STATUS_FRAME_SIZE_ERROR);
			int index = 0;
			int padding = 0;
			if((flags & FRAME_FLAG_ANY_PADDED) != 0)
				padding = data[index++];
			int promisedStreamId = FrameUtil.readInt32BE(data, index) & 0x7fffffff;
			if((promisedStreamId & 1) != 0) // the stream id is odd; servers may only open streams with even IDs
				throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, "Invalid promisedStreamId");
			index += 4;
			if(padding > data.length - index)
				throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, "padding is too high");
			this.headersEndStream = false;
			this.promisedStreamId = promisedStreamId;
			boolean eoh = (flags & FRAME_FLAG_ANY_END_HEADERS) != 0;
			byte[] fragment = Arrays.copyOfRange(data, index, data.length - padding);
			if(eoh){
				this.receiveHeaderBlock(fragment, this.headersEndStream);
			}else{
				this.headersReceiving = true;
				this.headersBuf.write(fragment);
			}
		}else if(type == FRAME_TYPE_CONTINUATION){
			if(!this.headersReceiving)
				throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, "Unexpected CONTINUATION");
			if(this.headersBuf.size() + data.length > this.localSettings.get(SETTINGS_MAX_HEADER_LIST_SIZE))
				throw new HTTP2ConnectionError(STATUS_ENHANCE_YOUR_CALM, true, "Exceeded maxHeadersSize");
			this.headersBuf.write(data);
			boolean eoh = (flags & FRAME_FLAG_ANY_END_HEADERS) != 0;
			if(eoh){
				this.receiveHeaderBlock(this.headersBuf.toByteArray(), this.headersEndStream);
				this.headersBuf.reset();
				this.headersReceiving = false;
			}
		}else if(type == FRAME_TYPE_DATA){
			if(this.state != STATE_OPEN && this.state != STATE_HALF_CLOSED_LOCAL)
				throw new HTTP2ConnectionError(STATUS_STREAM_CLOSED, true);
			int index = 0;
			int padding = 0;
			if((flags & FRAME_FLAG_ANY_PADDED) != 0)
				padding = data[index++];
			if(padding > data.length - index)
				throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, "padding is too high");
			byte[] fdata = Arrays.copyOfRange(data, index, data.length - padding);
			boolean es = (flags & FRAME_FLAG_ANY_END_STREAM) != 0;
			this.receiveData(fdata, es);
			if(this.receiveData && data.length > 0)
				super.sendWindowSizeUpdate(data.length);
		}else if(type == FRAME_TYPE_RST_STREAM){
			if(data.length != 4)
				throw new HTTP2ConnectionError(STATUS_FRAME_SIZE_ERROR);
			int status = FrameUtil.readInt32BE(data, 0);
			logger.debug("Stream ", super.getStreamId(), " closed by RST_STREAM with status ", status);
			this.close(status);
		}else
			super.receiveFrame(type, flags, data);
	}

	private void receiveHeaderBlock(byte[] data, boolean endStream) throws IOException {
		boolean pushPromise = this.state == STATE_RESERVED;
		boolean request = this.state == STATE_OPEN || pushPromise;
		if(endStream)
			this.recvESnc(); // close() is called after events, but state change must be done before (bit of a mess i know)
		Map<String, String> headers = this.hpack.decodeHeaderBlock(data);
		if(headers == null)
			throw new HTTP2ConnectionError(STATUS_COMPRESSION_ERROR);
		if(this.receivedMessage == null){
			int streamId;
			if(!pushPromise)
				streamId = super.getStreamId();
			else
				streamId = this.promisedStreamId;
			HTTP2Message msg;
			if(request){
				String method = headers.remove(":method");
				String scheme = headers.remove(":scheme");
				String authority = headers.remove(":authority");
				String path = headers.remove(":path");
				if(authority == null)
					authority = headers.get("host");
				if(!HTTPValidator.validMethod(method) || !"https".equals(scheme) || !HTTPValidator.validAuthority(authority) || !HTTPValidator.validPath(path))
					throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, true);
				msg = new HTTP2Message(method, scheme, authority, path, "HTTP/2", headers, streamId);
			}else{
				int status = HTTPValidator.parseStatus(headers.remove(":status"));
				if(status < 0)
					throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, true);
				msg = new HTTP2Message(status, "HTTP/2", headers, streamId);
			}
			msg.setSize(data.length);
			msg.setChunkedTransfer(!headers.containsKey("content-length"));

			if(!pushPromise){
				this.receivedMessage = msg;
				if(this.onMessage == null)
					throw new IllegalStateException("onMessage is null");
				this.onMessage.accept(new HTTPMessageData(this.receivedMessage, endStream, null));
			}else if(this.onPushPromise != null){
				this.onPushPromise.accept(msg);
			}else // reset promised stream because there is no handler
				writeFrame(super.connection, streamId, FRAME_TYPE_RST_STREAM, 0, FrameUtil.int32BE(STATUS_INTERNAL_ERROR));
		}else{
			if(!endStream)
				throw new HTTP2ConnectionError(STATUS_PROTOCOL_ERROR, true);
			if(this.onTrailers != null)
				this.onTrailers.accept(new HTTPMessageTrailers(this.receivedMessage, headers));
			else if(this.onData != null)
				this.onData.accept(new HTTPMessageData(this.receivedMessage, true, new byte[0]));
		}
		if(endStream && this.state == STATE_HALF_CLOSED_LOCAL)
			this.close(STATUS_NO_ERROR);
	}

	private void receiveData(byte[] data, boolean endStream) throws IOException {
		if(endStream)
			this.recvESnc();
		if(this.onData != null)
			this.onData.accept(new HTTPMessageData(this.receivedMessage, endStream, data));
		if(endStream && this.state == STATE_HALF_CLOSED_LOCAL)
			this.close(STATUS_NO_ERROR);
	}


	@Override
	public void writeFrame(int type, int flags, byte[] data) {
		if(isFlowControlledFrameType(type)){
			if(!this.canAcceptFlowControlledData(data.length))
				throw new IllegalStateException("Payload in flow-controlled frame is larger than receiver window size");
			synchronized(this.windowSizeLock){
				this.receiverWindowSize -= data.length;
			}
			this.controlStream.consumeReceiverConnectionWindow(data.length);
		}
		super.writeFrame(type, flags, data);
	}

	@Override
	public void windowUpdate() {
		synchronized(this.windowSizeLock){
			QueuedDataFrame qf;
			while(super.connection.isWritable() && (qf = this.dataBacklog.peekFirst()) != null && this.canAcceptFlowControlledData(qf.payload.length)){
				this.dataBacklog.removeFirst();
				this.writeFrame(FRAME_TYPE_DATA, qf.flags, qf.payload);
				if((qf.flags & FRAME_FLAG_ANY_END_STREAM) != 0)
					this.sentES();
			}
		}
		if(this.dataBacklog.size() == 0 && this.onDataFlushed != null)
			this.onDataFlushed.run();
		super.windowUpdate();
	}

	/**
	 * 
	 * @param size The size of a flow-controlled frame payload
	 * @return <code>true</code> if the receiver can receive the amount of data given
	 */
	protected boolean canAcceptFlowControlledData(int size) {
		return Math.min(this.controlStream.getReceiverWindowSize(), this.receiverWindowSize) >= size;
	}


	private synchronized void recvESnc() {
		if(this.state == STATE_OPEN)
			this.state = STATE_HALF_CLOSED;
		else if(this.state != STATE_HALF_CLOSED_LOCAL)
			throw new IllegalStateException();
	}

	private synchronized void sentES() {
		if(this.state == STATE_OPEN)
			this.state = STATE_HALF_CLOSED_LOCAL;
		else if(this.state == STATE_HALF_CLOSED)
			this.close(STATUS_NO_ERROR);
		else
			throw new IllegalStateException();
	}

	private void close(int errorCode) {
		synchronized(this){
			if(this.isClosed())
				return;
			this.state = STATE_CLOSED;
		}
		this.closeTime = System.nanoTime();
		if(this.onClosed == null)
			throw new IllegalStateException("onClosed is null");
		this.onClosed.accept(errorCode);
	}

	/**
	 * Sends the given <b>errorCode</b> in a <i>RST_STREAM</i> frame to close the stream, if the underlying connection is still connected. This also immediately changed the
	 * state to <code>STATE_CLOSED</code> and calls <code>onClose</code>.
	 * 
	 * @param errorCode The status code to close the stream with
	 */
	public void rst(int errorCode) {
		this.close(errorCode);
		if(super.connection.isConnected())
			this.writeFrame(FRAME_TYPE_RST_STREAM, 0, FrameUtil.int32BE(errorCode));
	}


	public void setOnPushPromise(StreamCallback<HTTP2Message> onPushPromise) {
		this.onPushPromise = onPushPromise;
	}

	public void setOnMessage(StreamCallback<HTTPMessageData> onMessage) {
		this.onMessage = onMessage;
	}

	public void setOnData(StreamCallback<HTTPMessageData> onData) {
		this.onData = onData;
	}

	public void setOnTrailers(StreamCallback<HTTPMessageTrailers> onTrailers) {
		this.onTrailers = onTrailers;
	}

	public void setOnDataFlushed(Runnable onDataFlushed) {
		this.onDataFlushed = onDataFlushed;
	}

	public void setOnClosed(Consumer<Integer> onClosed) {
		this.onClosed = onClosed;
	}


	/**
	 * 
	 * @param receiveData <code>true</code> if this stream should continue receiving flow-controlled data by sending WINDOW_UPDATE frames
	 */
	public void setReceiveData(boolean receiveData) {
		if(!this.receiveData && receiveData) // was re-enabled
			super.sendWindowSizeUpdate(this.localSettings.get(SETTINGS_INITIAL_WINDOW_SIZE));
		this.receiveData = receiveData;
	}

	/**
	 * 
	 * @return <code>true</code> if this stream is buffering data because more data was passed to {@link #sendData(byte[], boolean)} than the receiver can receive
	 */
	public boolean hasDataBacklog() {
		return this.dataBacklog.size() > 0;
	}


	/**
	 * Prepares this stream for the receipt or transmission of a promised server response.
	 * 
	 * @param receive <code>true</code> if this stream is about to receive a pushed response, <code>false</code> otherwise
	 * @throws IllegalStateException If this stream is not in <code>STATE_IDLE</code> or the stream ID is an odd number
	 */
	public void preparePush(boolean receive) {
		if(this.state != STATE_IDLE || (this.streamId & 1) != 0)
			throw new IllegalStateException("Stream cannot be used for server push response");
		this.state = receive ? STATE_HALF_CLOSED_LOCAL : STATE_HALF_CLOSED;
	}

	public int getState() {
		return this.state;
	}

	public boolean isExpectingResponse() {
		return this.state == STATE_HALF_CLOSED;
	}

	public boolean isClosed() {
		return this.state == STATE_CLOSED;
	}

	public long getCloseTime() {
		return this.closeTime;
	}


	private static class QueuedDataFrame {
		public final int flags;
		public final byte[] payload;

		public QueuedDataFrame(int flags, byte[] payload) {
			this.flags = flags;
			this.payload = payload;
		}
	}
}
