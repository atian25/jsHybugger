package org.jshybugger.proxy;

import java.net.UnknownHostException;

import org.jshybugger.server.DebugSession;
import org.jshybugger.server.MessageHandler;

import android.content.Context;

public class ProxyDebugSession extends DebugSession {

	public ProxyDebugSession(Context application) throws UnknownHostException {
		super(application);
	}

	@Override
	public MessageHandler getMessageHandler(String handlerName) {

		if ("CSS".equals(handlerName) || "DOM".equals(handlerName)) {
			return null;
		}
		return super.getMessageHandler(handlerName);
	}
}
