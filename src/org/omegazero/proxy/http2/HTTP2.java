/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2;

import java.io.IOException;
import java.util.Map;

import org.omegazero.common.logging.Logger;
import org.omegazero.net.common.NetCommon;
import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.config.HTTPEngineConfig;
import org.omegazero.proxy.core.Proxy;
import org.omegazero.proxy.core.ProxyEvents;
import org.omegazero.proxy.http.HTTPCommon;
import org.omegazero.proxy.http.HTTPEngine;
import org.omegazero.proxy.http.HTTPErrdoc;
import org.omegazero.proxy.http.HTTPMessage;
import org.omegazero.proxy.http.HTTPMessageData;
import org.omegazero.proxy.http2.core.HTTP2Client;
import org.omegazero.proxy.http2.core.HTTP2Constants;
import org.omegazero.proxy.http2.core.HTTP2Endpoint;
import org.omegazero.proxy.http2.core.HTTP2Message;
import org.omegazero.proxy.http2.core.HTTP2Settings;
import org.omegazero.proxy.http2.error.HTTP2ConnectionError;
import org.omegazero.proxy.http2.hpack.HPackContext;
import org.omegazero.proxy.http2.streams.ControlStream;
import org.omegazero.proxy.http2.streams.HTTP2Stream;
import org.omegazero.proxy.http2.streams.MessageStream;
import org.omegazero.proxy.net.UpstreamServer;
import org.omegazero.proxy.util.ProxyUtil;

public class HTTP2 extends HTTP2Endpoint implements HTTPEngine {

	private static final Logger logger = Logger.create();

	private static final String[] HTTP2_ALPN = new String[] { "h2" };


	private final Proxy proxy;
	private final HTTPEngineConfig config;

	private final boolean disablePromiseRequestLog;

	private boolean downstreamClosed;
	private final String downstreamConnectionDbgstr;

	private boolean prefaceReceived = false;

	private final HTTP2Settings settingsClient;
	private Map<UpstreamServer, HTTP2Client> upstreamConnections = new java.util.concurrent.ConcurrentHashMap<>();

	private int nextStreamId = 2;

	public HTTP2(SocketConnection downstreamConnection, Proxy proxy, HTTPEngineConfig config) {
		super(downstreamConnection, initSettings(config), new HPackContext.Session(), config.optBoolean("useHuffmanEncoding", true));
		super.closeWaitTimeout = config.optInt("closeWaitTimeout", 5) * 1000000000L;

		this.proxy = proxy;
		this.config = config;

		this.disablePromiseRequestLog = config.optBoolean("disablePromiseRequestLog", config.isDisableDefaultRequestLog());

		this.downstreamConnectionDbgstr = this.proxy.debugStringForConnection(super.connection, null);

		this.settingsClient = new HTTP2Settings(super.settings);
	}

	private static HTTP2Settings initSettings(HTTPEngineConfig config) {
		HTTP2Settings settings = new HTTP2Settings();
		int maxFrameSize = config.optInt("maxFrameSize", 0);
		if(maxFrameSize > HTTP2Constants.SETTINGS_MAX_FRAME_SIZE_MAX)
			throw new IllegalArgumentException("maxFrameSize is too large: " + maxFrameSize + " > " + HTTP2Constants.SETTINGS_MAX_FRAME_SIZE_MAX);
		if(maxFrameSize > 0)
			settings.set(HTTP2Constants.SETTINGS_MAX_FRAME_SIZE, maxFrameSize);

		int maxDynamicTableSize = config.optInt("maxDynamicTableSize", -1);
		if(maxDynamicTableSize >= 0)
			settings.set(HTTP2Constants.SETTINGS_HEADER_TABLE_SIZE, maxDynamicTableSize);

		int initialWindowSize = config.optInt("initialWindowSize", 0);
		if(initialWindowSize > 0)
			settings.set(HTTP2Constants.SETTINGS_INITIAL_WINDOW_SIZE, initialWindowSize);

		settings.set(HTTP2Constants.SETTINGS_MAX_HEADER_LIST_SIZE, config.optInt("maxHeadersSize", 16384));
		settings.set(HTTP2Constants.SETTINGS_MAX_CONCURRENT_STREAMS, config.optInt("maxConcurrentStreams", 100));
		return settings;
	}


	@Override
	public void processData(byte[] data) {
		int index = 0;
		while(index < data.length){
			if(this.prefaceReceived){
				index = super.processData0(data, index);
				if(index < 0)
					break;
			}else{
				if(validClientPreface(data)){
					index += HTTP2Constants.CLIENT_PREFACE.length;
					this.prefaceReceived = true;
					ControlStream cs = new ControlStream(0, super.connection, super.settings);
					super.streams.put(0, cs);
					cs.setOnSettingsUpdate((settings) -> {
						HTTP2.super.hpack.setEncoderDynamicTableMaxSize(settings.get(HTTP2Constants.SETTINGS_HEADER_TABLE_SIZE));
						if(settings.get(HTTP2Constants.SETTINGS_ENABLE_PUSH) == 0)
							HTTP2.this.settingsClient.set(HTTP2Constants.SETTINGS_ENABLE_PUSH, 0);
					});
					cs.setOnWindowUpdate(super::handleConnectionWindowUpdate);
					cs.writeSettings(super.settings);
				}else{
					super.connection.destroy();
					break;
				}
			}
		}
	}

	@Override
	public void close() {
		this.downstreamClosed = true;
		for(HTTP2Client c : this.upstreamConnections.values())
			c.close();
	}

	@Override
	public SocketConnection getDownstreamConnection() {
		return super.connection;
	}

	@Override
	public void respond(HTTPMessage request0, HTTPMessageData responsedata) {
		if(!(request0 instanceof HTTP2Message))
			throw new IllegalArgumentException("request is not a " + HTTP2Message.class.getName());
		HTTP2Message request = (HTTP2Message) request0;
		HTTPMessage response = responsedata.getHttpMessage();
		if(!HTTPCommon.setRequestResponse(request, response))
			return;
		MessageStream stream = (MessageStream) super.streams.get(request.getStreamId());
		if(stream == null)
			throw new IllegalStateException("Stream closed");
		byte[] data = responsedata.getData();
		response.deleteHeader("transfer-encoding");
		response.deleteHeader("connection");
		response.deleteHeader("keep-alive");
		response.deleteHeader("upgrade");
		data = HTTPCommon.prepareHTTPResponse(request, response, data);
		if(data.length > 0){
			stream.sendHTTPMessage(response, false);
			stream.sendData(data, true);
		}else
			stream.sendHTTPMessage(response, true);
	}

	@Override
	public void respond(HTTPMessage request, int status, byte[] data, String... headers) {
		this.respondEx(request, status, data, headers);
	}

	@Override
	public void respondError(HTTPMessage request, int status, String title, String message, String... headers) {
		if(request.getCorrespondingMessage() != null)
			return;
		HTTPErrdoc errdoc = this.proxy.getErrdocForAccept(request.getHeader("accept"));
		byte[] errdocData = errdoc.generate(status, title, message, request.getHeader("x-request-id"), super.connection.getApparentRemoteAddress().toString()).getBytes();
		this.respondEx(request, status, errdocData, headers, "content-type", errdoc.getMimeType());
	}

	private void respondEx(HTTPMessage request, int status, byte[] data, String[] h1, String... hEx) {
		if(request.getCorrespondingMessage() != null)
			return;
		logger.debug(this.downstreamConnectionDbgstr, " Responding with status ", status);
		HTTPMessage response = new HTTPMessage(status, "HTTP/2");
		for(int i = 0; i + 1 < hEx.length; i += 2)
			response.setHeader(hEx[i], hEx[i + 1]);
		for(int i = 0; i + 1 < h1.length; i += 2)
			response.setHeader(h1[i], h1[i + 1]);

		if(!response.headerExists("date"))
			response.setHeader("date", HTTPCommon.dateString());
		response.setHeader("server", this.proxy.getInstanceName());
		response.setHeader("x-proxy-engine", this.getClass().getSimpleName());
		response.setHeader("x-request-id", request.getRequestId());
		this.respond(request, new HTTPMessageData(response, data));
	}


	@Override
	protected HTTP2Stream newStreamForFrame(int streamId, int type, int flags, byte[] payload) throws IOException {
		if(type == HTTP2Constants.FRAME_TYPE_HEADERS){
			if((streamId & 1) == 0 || streamId <= super.highestStreamId)
				throw new HTTP2ConnectionError(HTTP2Constants.STATUS_PROTOCOL_ERROR);
			super.checkRemoteCreateStream();
			ControlStream cs = super.getControlStream();
			MessageStream mstream = new MessageStream(streamId, super.connection, cs, super.settings, cs.getRemoteSettings(), super.hpack);
			logger.trace("Created new stream ", mstream.getStreamId(), " for HEADERS frame");
			mstream.setOnMessage((requestdata) -> {
				HTTPMessage request = requestdata.getHttpMessage();
				if(!request.isRequest())
					throw new IllegalArgumentException("Not a request message");
				try{
					this.processHTTPRequest(mstream, request, requestdata.isLastPacket());
				}catch(Exception e){
					if(e instanceof HTTP2ConnectionError)
						throw e;
					logger.error("Error while processing request: ", e);
					HTTP2.this.respondError(request, HTTPCommon.STATUS_INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error has occurred");
				}
			});
			mstream.setOnClosed((status) -> {
				logger.trace("Request stream ", mstream.getStreamId(), " closed with status ", status);
				synchronized(super.closeWaitStreams){
					super.closeWaitStreams.add(mstream);
				}
			});
			return mstream;
		}
		return null;
	}


	private void initRequest(HTTPMessage request) {
		request.setEngine(this);
		request.setRequestId(HTTPCommon.requestId(super.connection));
		if(this.config.isEnableHeaders())
			HTTPCommon.setDefaultHeaders(this.proxy, request);
	}

	private void processHTTPRequest(MessageStream clientStream, HTTPMessage request, boolean endStream) throws IOException {
		this.initRequest(request);
		this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_PRE_LOG, super.connection, request);
		if(!this.config.isDisableDefaultRequestLog())
			logger.info(super.connection.getApparentRemoteAddress(), "/", HTTPCommon.shortenRequestId(request.getRequestId()), " - '", request.requestLine(), "'");
		if(request.getCorrespondingMessage() != null) // received response (eg in event handler)
			return;

		UpstreamServer userver = this.proxy.getUpstreamServer(request.getAuthority(), request.getPath());
		if(userver == null){
			logger.debug(this.downstreamConnectionDbgstr, " No upstream server found");
			this.proxy.dispatchEvent(ProxyEvents.INVALID_UPSTREAM_SERVER, super.connection, request);
			request.respondError(HTTPCommon.STATUS_NOT_FOUND, "Not Found", "No appropriate upstream server was found for this request");
			return;
		}

		this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_PRE, super.connection, request, userver);
		if(request.getCorrespondingMessage() != null)
			return;

		final HTTP2Client client;
		synchronized(this){
			HTTP2Client tmp;
			tmp = this.upstreamConnections.get(userver);
			if(tmp == null)
				tmp = this.createClient(request, userver);
			if(tmp == null)
				return;
			client = tmp;
		}

		MessageStream usStream = client.createRequestStream();
		if(usStream == null)
			throw new HTTP2ConnectionError(HTTP2Constants.STATUS_REFUSED_STREAM);
		logger.trace("Created new upstream request stream ", usStream.getStreamId(), " for request ", request.getRequestId());

		this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST, super.connection, request, userver);


		usStream.setOnPushPromise((promiseRequest) -> {
			if(!clientStream.isExpectingResponse())
				throw new HTTP2ConnectionError(HTTP2Constants.STATUS_CANCEL, true);

			this.initRequest(promiseRequest);
			if(!this.disablePromiseRequestLog)
				logger.info(super.connection.getApparentRemoteAddress(), "/", HTTPCommon.shortenRequestId(promiseRequest.getRequestId()), "/<promise> - '",
						promiseRequest.requestLine(), "'");

			int ppDSStreamId = HTTP2.this.nextStreamId;
			HTTP2.this.nextStreamId += 2;
			ControlStream cs = super.getControlStream();
			// The stream in the client connection where the push response is going to be sent on
			MessageStream ppDSStream = new MessageStream(ppDSStreamId, super.connection, cs, super.settings, cs.getRemoteSettings(), super.hpack);
			ppDSStream.preparePush(false);
			HTTP2.super.registerStream(ppDSStream);
			logger.trace("Created new push promise stream ", ppDSStreamId, " for promise request ", promiseRequest.getRequestId());

			// need to make another request object because the stream ID of promiseRequest is the stream ID in the connection to the upstream server, and
			// when attempting to use it in respond etc it will not be a valid stream ID for the client
			HTTP2Message dsPromiseRequest = promiseRequest.clone(ppDSStreamId);

			// The stream in the upstream server connection where the push response is going to be received from
			MessageStream ppUSStream = client.handlePushPromise(promiseRequest);
			logger.trace("Created new upstream push promise stream ", ppUSStream.getStreamId(), " for promise request ", promiseRequest.getRequestId());
			HTTP2.this.prepareResponseStream(dsPromiseRequest, ppDSStream, ppUSStream, userver);

			ppDSStream.setOnClosed((status) -> {
				logger.trace("Push promise request stream ", ppDSStream.getStreamId(), " closed with status ", status);
				if(status != HTTP2Constants.STATUS_NO_ERROR)
					ppUSStream.rst(HTTP2Constants.STATUS_CANCEL);
			});

			HTTP2.this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST, HTTP2.super.connection, dsPromiseRequest, userver);
			HTTP2.this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_ENDED, HTTP2.super.connection, dsPromiseRequest, userver);

			// forward push promise to client
			clientStream.sendPushPromise(ppDSStreamId, dsPromiseRequest);
		});
		this.prepareResponseStream(request, clientStream, usStream, userver);

		if(endStream){
			this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_ENDED, super.connection, request, userver);
		}else{
			clientStream.setOnData((requestdata) -> {
				if(usStream.isClosed())
					throw new HTTP2ConnectionError(HTTP2Constants.STATUS_CANCEL, true);
				try{
					HTTP2.this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_DATA, HTTP2.super.connection, requestdata, userver);
					if(!usStream.sendData(requestdata.getData(), requestdata.isLastPacket()))
						clientStream.setReceiveData(false);
					if(requestdata.isLastPacket())
						HTTP2.this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_ENDED, HTTP2.super.connection, request, userver);
				}catch(Exception e){
					if(e instanceof HTTP2ConnectionError)
						throw e;
					logger.error("Error while processing request data: ", e);
					HTTP2.this.respondError(request, HTTPCommon.STATUS_INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error has occurred");
				}
			});
			clientStream.setOnTrailers((trailers) -> {
				if(usStream.isClosed())
					throw new HTTP2ConnectionError(HTTP2Constants.STATUS_CANCEL, true);
				try{
					HTTP2.this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_TRAILERS, HTTP2.super.connection, trailers, userver);
					HTTP2.this.proxy.dispatchEvent(ProxyEvents.HTTP_REQUEST_ENDED, HTTP2.super.connection, request, userver); // trailers always imply EOS
					usStream.sendTrailers(trailers);
				}catch(Exception e){
					if(e instanceof HTTP2ConnectionError)
						throw e;
					logger.error("Error while processing request trailers: ", e);
				}
			});

			usStream.setOnDataFlushed(() -> {
				clientStream.setReceiveData(true);
			});
		}

		usStream.sendHTTPMessage(request, endStream);
	}

	private void prepareResponseStream(HTTPMessage request, MessageStream dsStream, MessageStream usStream, UpstreamServer userver) {
		usStream.setOnMessage((responsedata) -> {
			if(dsStream.isClosed())
				throw new HTTP2ConnectionError(HTTP2Constants.STATUS_CANCEL, true);
			HTTPMessage response = responsedata.getHttpMessage();
			if(response.isRequest())
				throw new IllegalArgumentException("Not a response message");
			try{
				if(!HTTPCommon.setRequestResponse(request, response))
					return;
				response.setCorrespondingMessage(request);
				response.setRequestId(request.getRequestId());
				if(this.config.isEnableHeaders()){
					if(!response.headerExists("date"))
						response.setHeader("date", HTTPCommon.dateString());
					HTTPCommon.setDefaultHeaders(this.proxy, response);
				}

				boolean wasChunked = response.isChunkedTransfer();
				HTTP2.this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE, HTTP2.super.connection, usStream.getConnection(), response, userver);
				if(wasChunked && !response.isChunkedTransfer())
					throw new IllegalStateException("Cannot unchunkify a response body");
				else if(response.isChunkedTransfer() && !wasChunked)
					response.deleteHeader("content-length");
				if(response.isIntermediateMessage())
					request.setCorrespondingMessage(null);
				dsStream.sendHTTPMessage(response, responsedata.isLastPacket());
			}catch(Exception e){
				request.setCorrespondingMessage(null);
				if(e instanceof HTTP2ConnectionError)
					throw e;
				logger.error("Error while processing response: ", e);
				HTTP2.this.respondError(request, HTTPCommon.STATUS_INTERNAL_SERVER_ERROR, "Internal Server Error",
						"An unexpected error has occurred while processing response");
			}
		});
		usStream.setOnData((responsedata) -> {
			if(dsStream.isClosed())
				throw new HTTP2ConnectionError(HTTP2Constants.STATUS_CANCEL, true);
			HTTP2.this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_DATA, HTTP2.super.connection, usStream.getConnection(), responsedata, userver);
			if(!dsStream.sendData(responsedata.getData(), responsedata.isLastPacket()))
				usStream.setReceiveData(false);
		});
		usStream.setOnTrailers((trailers) -> {
			if(dsStream.isClosed())
				throw new HTTP2ConnectionError(HTTP2Constants.STATUS_CANCEL, true);
			HTTP2.this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_TRAILERS, HTTP2.super.connection, usStream.getConnection(), trailers, userver);
			HTTP2.this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_ENDED, HTTP2.super.connection, usStream.getConnection(), trailers.getHttpMessage(), userver);
			dsStream.sendTrailers(trailers);
		});
		usStream.setOnClosed((status) -> {
			if(HTTP2.this.downstreamClosed)
				return;
			logger.trace("Upstream request stream ", usStream.getStreamId(), " closed with status ", status);
			HTTPMessage response = request.getCorrespondingMessage();
			if(response == null){
				if(status == HTTP2Constants.STATUS_ENHANCE_YOUR_CALM || status == HTTP2Constants.STATUS_HTTP_1_1_REQUIRED){ // passthrough error to client
					dsStream.rst(status);
				}else if(dsStream.isExpectingResponse()){
					logger.error(usStream.getConnection().getAttachment(), " Stream ", usStream.getStreamId(), " closed unexpectedly with status ", status);
					HTTP2.this.respondError(request, HTTPCommon.STATUS_BAD_GATEWAY, "Bad Gateway", "Message stream closed unexpectedly");
				}
			}else if(status == 0)
				HTTP2.this.proxy.dispatchEvent(ProxyEvents.HTTP_RESPONSE_ENDED, HTTP2.super.connection, usStream.getConnection(), response, userver);
		});

		dsStream.setOnDataFlushed(() -> {
			usStream.setReceiveData(true);
		});
	}


	private synchronized HTTP2Client createClient(HTTPMessage request, UpstreamServer userver) throws IOException {
		if(!userver.isProtocolSupported("http/2"))
			throw new HTTP2ConnectionError(HTTP2Constants.STATUS_HTTP_1_1_REQUIRED, true, "HTTP/2 is not supported by the upstream server");

		SocketConnection uconn;
		try{
			uconn = ProxyUtil.connectUpstreamTCP(this.proxy, true, userver, HTTP2_ALPN);
		}catch(IOException e){
			logger.error("Connection failed: ", e);
			this.respondError(request, HTTPCommon.STATUS_INTERNAL_SERVER_ERROR, "Internal Server Error", "Upstream connection creation failed");
			return null;
		}

		HTTP2Client client = new HTTP2Client(uconn, this.settingsClient, super.hpack.getSession(), super.hpack.isUseHuffmanEncoding());

		uconn.setAttachment(this.proxy.debugStringForConnection(super.connection, uconn));
		uconn.setOnConnect(() -> {
			logger.debug(uconn.getAttachment(), " Connected");
			HTTP2.this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION, uconn);
		});
		uconn.setOnTimeout(() -> {
			logger.error(uconn.getAttachment(), " Connect timed out");
			HTTP2.this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_TIMEOUT, uconn);
			if(HTTP2.this.downstreamClosed)
				return;
			HTTP2.this.respondError(request, HTTPCommon.STATUS_GATEWAY_TIMEOUT, "Gateway Timeout", "Connection to the upstream server timed out");
		});
		uconn.setOnError((e) -> {
			if(e instanceof IOException)
				logger.error(uconn.getAttachment(), " Error: ", NetCommon.PRINT_STACK_TRACES ? e : e.toString());
			else
				logger.error(uconn.getAttachment(), " Internal error: ", e);

			try{
				HTTP2.this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_ERROR, uconn, e);

				if(HTTP2.this.downstreamClosed)
					return;
				if(e instanceof HTTP2ConnectionError)
					this.respondError(request, HTTPCommon.STATUS_BAD_GATEWAY, "Bad Gateway", "HTTP/2 protocol error");
				else if(e instanceof IOException)
					this.respondError(request, HTTPCommon.STATUS_BAD_GATEWAY, "Bad Gateway", HTTPCommon.getUpstreamErrorMessage(e));
				else
					this.respondError(request, HTTPCommon.STATUS_INTERNAL_SERVER_ERROR, "Internal Server Error",
							"An internal error occurred in the connection to the upstream server");
			}catch(Exception ue){
				logger.error("Internal error while handling upstream connection error: ", ue);
			}
		});
		uconn.setOnClose(() -> {
			logger.debug(uconn.getAttachment(), " Disconnected");
			HTTP2.this.proxy.dispatchEvent(ProxyEvents.UPSTREAM_CONNECTION_CLOSED, uconn);
			if(!HTTP2.this.downstreamClosed)
				client.connectionClosed();
			HTTP2.this.upstreamConnections.remove(userver, client);
		});
		uconn.setOnData(client::processData);

		client.start();
		uconn.connect(this.config.getUpstreamConnectionTimeout());
		this.upstreamConnections.put(userver, client);
		return client;
	}


	private static boolean validClientPreface(byte[] data) {
		if(data.length < HTTP2Constants.CLIENT_PREFACE.length)
			return false;
		for(int i = 0; i < HTTP2Constants.CLIENT_PREFACE.length; i++){
			if(HTTP2Constants.CLIENT_PREFACE[i] != data[i])
				return false;
		}
		return true;
	}
}
