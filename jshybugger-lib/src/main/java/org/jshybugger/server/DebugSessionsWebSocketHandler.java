package org.jshybugger.server;

import org.webbitserver.BaseWebSocketHandler;
import org.webbitserver.WebSocketConnection;

public class DebugSessionsWebSocketHandler extends BaseWebSocketHandler {

	private DebugServer debugServer;

	public DebugSessionsWebSocketHandler(DebugServer debugServer) {
		this.debugServer = debugServer;
	}

	@Override
	public void onClose(WebSocketConnection connection) throws Exception {
		getDebugSession(connection).onClose(connection);
	}

	public void onMessage(WebSocketConnection connection, String msg) throws Throwable {
		getDebugSession(connection).onMessage(connection, msg);
	}
	
	@Override
	public void onOpen(WebSocketConnection connection) throws Exception {
		getDebugSession(connection).onOpen(connection);
	}
	
	private DebugSession getDebugSession(WebSocketConnection connection) {
		String uri = connection.httpRequest().uri();
		int index = uri.lastIndexOf('/');
		String id = uri.substring(index+1);
		return debugServer.getDebugSession(id);
	}
}
