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

import java.util.HashMap;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONStringer;
import org.webbitserver.WebSocketConnection;


/**
 * The RuntimeMsgHandler handles all Runtime related debugging protocol messages.
 * 
 * https://developers.google.com/chrome-developer-tools/docs/protocol/tot/runtime
 */
public class RuntimeMsgHandler extends AbstractMsgHandler {

	/** The methods available. */
	private final HashMap<String,Boolean> METHODS_AVAILABLE = new HashMap<String, Boolean>(); 

	/**
	 * Instantiates a new runtime message handler.
	 *
	 * @param debugServer the debug server
	 */
	public RuntimeMsgHandler(DebugSession debugServer) {
		super(debugServer, "Runtime");

		METHODS_AVAILABLE.put("enable", true);
	}

	/* (non-Javadoc)
	 * @see de.cyberflohrs.jshybugger.server.AbstractMsgHandler#onReceiveMessage(org.webbitserver.WebSocketConnection, java.lang.String, org.json.JSONObject)
	 */
	@Override
	public void onReceiveMessage(WebSocketConnection conn, String method, JSONObject message) throws JSONException {
		
		if (METHODS_AVAILABLE.containsKey(method)) {
			
			JSONObject reply = new JSONObject();
			
			reply.put("id", message.getInt("id"));
			reply.put("result", new JSONObject().put("result", METHODS_AVAILABLE.get(method)));
			
			
			conn.send(reply.toString());

		} else {
			super.onReceiveMessage(conn, method, message);
		}
	}

	/* (non-Javadoc)
	 * @see de.cyberflohrs.jshybugger.server.AbstractMsgHandler#onSendMessage(org.webbitserver.WebSocketConnection, java.lang.String, org.json.JSONObject)
	 */
	@Override
	public void onSendMessage(WebSocketConnection conn, String method, JSONObject message)
			throws JSONException {
		
		if ("GlobalInitHybugger".equals(method)) {
			
			sendExecutionContextCreated(conn, message);
		} else {
			super.onSendMessage(conn, method, message);
		}
	}	
	
	/**
	 * Process "Page.loadEventFired" protocol messages.
	 * Forwards the message to the debugger frontend. This message is triggered by loading the jsHybugger.js file. 
	 *
	 * @param conn the websocket connection
	 * @param message the JSON message
	 * @throws JSONException some JSON exception
	 */
	private void sendExecutionContextCreated(WebSocketConnection conn, JSONObject msg) throws JSONException {
		
		if (conn != null) {
			
//			{"method":"Runtime.executionContextCreated","params":{"context":{"id":4,"isPageContext":true,"name":"","frameId":"4990.1"}}}	
			conn.send(new JSONStringer().object()
					.key("method").value("Runtime.executionContextCreated")
						.key("params").object()
							.key("context").object()
								.key("id").value(1)
								.key("isPageContext").value(true)
								.key("name").value("")
					    		.key("frameId").value(msg.get("frameId"))
					    	.endObject()
						.endObject()
					.endObject()
				.toString());
		}
	}
}
