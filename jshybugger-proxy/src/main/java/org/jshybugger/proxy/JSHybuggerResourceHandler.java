package org.jshybugger.proxy;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.jshybugger.JsHybugger;
import org.jshybugger.server.DebugServer;
import org.jshybugger.server.DebugSession;
import org.json.JSONObject;
import org.webbitserver.HttpControl;
import org.webbitserver.HttpHandler;
import org.webbitserver.HttpRequest;
import org.webbitserver.HttpResponse;

import android.content.Context;

class JSHybuggerResourceHandler implements HttpHandler {

	private final  DebugServer debugServer;
	private long lastCheckedTimeStamp = System.currentTimeMillis();
	private Context context;

	public JSHybuggerResourceHandler(Context context, DebugServer debugServer) {
		this.debugServer = debugServer;
		this.context = context;
	}

	@Override
	public void handleHttpRequest(HttpRequest req, HttpResponse res,
			HttpControl control) throws Exception {
		res.header("Access-Control-Allow-Origin", "*");
		res.header("Allow", "GET, DELETE, POST, PUT, OPTIONS");
		if ("OPTIONS".equals(req.method()) ) {
			res.header("Access-Control-Allow-Methods", "GET, DELETE, POST, PUT, OPTIONS");
			String headers = req.header("Access-Control-Request-Headers");
			if (headers == null)
			{
				headers = "Allow";
			}
			else if (!headers.contains("Allow"))
			{
				headers += ", Allow";
			}
			res.header("Access-Control-Allow-Headers", headers);
			res.header("Access-Control-Expose-Headers", headers);
			res.end();
		} else {
		
			String uri = req.uri();
			//Log.d(TAG,  "START: " + req);
			if (uri.endsWith("jshybugger.js")) {
				res.header("Cache-control", "no-cache, no-store");
				res.header("Expires", "0");
				
				StringBuilder sb = new StringBuilder();
				String line=null;
				
				BufferedReader br = new BufferedReader(new InputStreamReader(JsHybugger.getSource(), "utf-8"));
				while ((line = br.readLine()) != null) {
					sb.append(line);
					sb.append("\r\n");
				}
					
				//Log.d("jshybugger.js", "available: " + sb.length());
				res.content(sb.toString());
				
			} else {
				if (uri.endsWith("sendToDebugService")) {
										
					JSONObject jsonReq = new JSONObject(req.body());
					JSDInterface browserInterface = getBrowserInterface(req,res, "GlobalInitHybugger".equals(jsonReq.getString("arg0")));
					browserInterface.sendToDebugService(jsonReq.getString("arg0"), jsonReq.getString("arg1"));
					
				} else {
					
					JSDInterface browserInterface = getBrowserInterface(req,res, false);
					if (browserInterface == null) {
						res.status(205);
					} else {
						if (uri.endsWith("sendReplyToDebugService")) {
					
							JSONObject jsonReq = new JSONObject(req.body());
							browserInterface.sendReplyToDebugService(jsonReq.getInt("arg0"), jsonReq.getString("arg1"));
	
						} else if (uri.endsWith("getQueuedMessage")) {
	
							JSONObject jsonReq = new JSONObject(req.body());
							browserInterface.getQueuedMessage(res, jsonReq.getBoolean("arg0"));
							return;
							
						} else if (uri.endsWith("pushChannel")) {
		
							browserInterface.openPushChannel(res);
							return;
							
						} else {
							res.status(204);
						}
					}
				}
			}
			res.end();
		}
	}

	private JSDInterface getBrowserInterface(HttpRequest req, HttpResponse resp, boolean createSession) {
		String sessionId = req.header("jshybuggerid");
		DebugSession debugSession = debugServer.getDebugSession(sessionId);
		if (debugSession == null) {
			if  (createSession) {
				JSDInterface browserInterface = new JSDInterface();
				debugSession = new DebugSession(context, sessionId);
				debugSession.setBrowserInterface(browserInterface);
				try {
					debugServer.exportSession(debugSession);
				} catch (InterruptedException e) {
					throw new RuntimeException("Exporting session failed",e);
				}
			} else {
				return null;
			}
		}
		// only set by the push channel
		String title = req.header("jshybugger_title");
		if (title != null) {
			debugSession.setTitle(title);
			debugSession.setUrl( req.header("jshybugger_url"));
			// check the session every 5 minutes if there are sessions older then 5 minutes
			if ( (lastCheckedTimeStamp + 5*60*1000) < System.currentTimeMillis()) {
				DebugSession[] debugSessions = debugServer.getDebugSessions();
				for (DebugSession session : debugSessions) {
					if (session.getLastUsedTimeStamp() < lastCheckedTimeStamp) {
						debugServer.unExportSession(session);
					}
				}
				lastCheckedTimeStamp = System.currentTimeMillis();
			}
		}
		debugSession.updateLastUsedTimeStamp();
		return (JSDInterface)debugSession.getBrowserInterface();
	}
}