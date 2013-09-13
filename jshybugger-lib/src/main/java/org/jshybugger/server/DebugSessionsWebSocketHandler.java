/*
 * Copyright 2013 Wolfgang Flohr-Hochbichler (wflohr@jshybugger.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jshybugger.server;

import org.webbitserver.BaseWebSocketHandler;
import org.webbitserver.WebSocketConnection;

/**
 * Dispatches the incoming HTTP traffic to the corresponding debug session.
 * @author cyberflohr
 */
public class DebugSessionsWebSocketHandler extends BaseWebSocketHandler {

	private DebugServer debugServer;

	public DebugSessionsWebSocketHandler(DebugServer debugServer) {
		this.debugServer = debugServer;
	}

	/* (non-Javadoc)
	 * @see org.webbitserver.BaseWebSocketHandler#onClose(org.webbitserver.WebSocketConnection)
	 */
	@Override
	public void onClose(WebSocketConnection connection) throws Exception {
		final DebugSession debugSession = getDebugSession(connection);
		if (debugSession != null) {
			debugSession.onClose(connection);
		}
	}

	/* (non-Javadoc)
	 * @see org.webbitserver.BaseWebSocketHandler#onMessage(org.webbitserver.WebSocketConnection, java.lang.String)
	 */
	public void onMessage(WebSocketConnection connection, String msg) throws Throwable {
		final DebugSession debugSession = getDebugSession(connection);
		if (debugSession != null) {
			debugSession.onMessage(connection, msg);
		}
	}
	
	/* (non-Javadoc)
	 * @see org.webbitserver.BaseWebSocketHandler#onOpen(org.webbitserver.WebSocketConnection)
	 */
	@Override
	public void onOpen(WebSocketConnection connection) throws Exception {
		final DebugSession debugSession = getDebugSession(connection);
		if (debugSession != null) {
			debugSession.onOpen(connection);
		}
	}
	
	private DebugSession getDebugSession(WebSocketConnection connection) {
		String uri = connection.httpRequest().uri();
		int index = uri.lastIndexOf('/');
		String id = uri.substring(index+1);
		return debugServer.getDebugSession(id);
	}
}
