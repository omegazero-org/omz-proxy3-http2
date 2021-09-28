/*
 * Copyright (C) 2021 omegazero.org, user94729
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.omegazero.proxy.http2.core;

import java.io.IOException;

import org.omegazero.net.socket.SocketConnection;
import org.omegazero.proxy.http2.hpack.HPackContext;
import org.omegazero.proxy.http2.hpack.HPackContext.Session;
import org.omegazero.proxy.http2.streams.ControlStream;
import org.omegazero.proxy.http2.streams.HTTP2Stream;
import org.omegazero.proxy.http2.streams.MessageStream;

public class HTTP2Client extends HTTP2Endpoint {


	private int nextStreamId = 1;

	public HTTP2Client(SocketConnection connection, HTTP2Settings settings, Session hpackSession, boolean useHuffmanEncoding) {
		super(connection, settings, hpackSession, useHuffmanEncoding);
	}

	public HTTP2Client(SocketConnection connection, HTTP2Settings settings, HPackContext hpack) {
		super(connection, settings, hpack);
	}


	public void start() {
		super.connection.writeQueue(HTTP2Constants.CLIENT_PREFACE);
		ControlStream cs = new ControlStream(0, super.connection, super.settings);
		super.streams.put(0, cs);
		cs.setOnSettingsUpdate((settings) -> {
			int maxTableSize = settings.get(HTTP2Constants.SETTINGS_HEADER_TABLE_SIZE);
			HTTP2Client.super.hpack.setEncoderDynamicTableMaxSize(maxTableSize);
		});
		cs.setOnWindowUpdate(super::handleConnectionWindowUpdate);
		cs.writeSettings(super.settings);
	}

	/**
	 * Creates a new {@link MessageStream} to send a request with. The caller will need to call
	 * {@link MessageStream#sendHTTPMessage(org.omegazero.proxy.http.HTTPMessage, boolean)} on the returned stream to start the message exchange.
	 * 
	 * @return The created <code>MessageStream</code>
	 */
	public MessageStream createRequestStream() {
		if(this.nextStreamId < 0) // overflow
			return null;
		ControlStream cs = super.getControlStream();
		MessageStream mstream = new MessageStream(this.nextStreamId, super.connection, cs, super.settings, cs.getRemoteSettings(), super.hpack);
		super.registerStream(mstream);
		this.nextStreamId += 2;
		return mstream;
	}

	/**
	 * Creates a new {@link MessageStream} that expects to receive a promise response by the server. The stream ID of the {@link HTTP2Message} is the promised stream ID by the
	 * server and must be an even number.
	 * 
	 * @param promisedRequest The promise request sent by the server
	 * @return The created <code>MessageStream</code>
	 */
	public MessageStream handlePushPromise(HTTP2Message promisedRequest) {
		int pushStreamId = promisedRequest.getStreamId();
		if((pushStreamId & 1) != 0)
			throw new IllegalArgumentException("promisedRequest.getStreamId is not an even number");
		ControlStream cs = super.getControlStream();
		MessageStream mstream = new MessageStream(pushStreamId, super.connection, cs, super.settings, cs.getRemoteSettings(), super.hpack);
		mstream.preparePush(true);
		super.highestStreamId = pushStreamId;
		super.registerStream(mstream);
		return mstream;
	}

	public void connectionClosed() {
		for(HTTP2Stream s : super.streams.values()){
			if(s instanceof MessageStream)
				((MessageStream) s).rst(HTTP2Constants.STATUS_CANCEL);
		}
	}

	public void close() {
		if(this.connection.isConnected())
			super.getControlStream().sendGoaway(super.highestStreamId, HTTP2Constants.STATUS_NO_ERROR);
		super.connection.close();
	}


	@Override
	protected HTTP2Stream newStreamForFrame(int streamId, int type, int flags, byte[] payload) throws IOException {
		return null;
	}
}
