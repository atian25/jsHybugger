package org.jshybugger.proxy;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.jshybugger.JsHybugger;
import org.json.JSONException;
import org.json.JSONObject;
import org.webbitserver.HttpControl;
import org.webbitserver.HttpHandler;
import org.webbitserver.HttpRequest;
import org.webbitserver.HttpResponse;

import android.content.Context;
import android.util.Log;

class JSHybuggerResourceHandler implements HttpHandler {

	private final JSDInterface browserInterface;
	
	JSHybuggerResourceHandler(JSDInterface browserInterface) {
		this.browserInterface = browserInterface;
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
			//Log.d("JSHybuggerResourceHandler",  req.method() + ": " + req.uri());
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
				
			} else if (uri.endsWith("sendToDebugService")) {
									
				JSONObject jsonReq = new JSONObject(req.body());
				this.browserInterface.sendToDebugService(jsonReq.getString("arg0"), jsonReq.getString("arg1"));
				
			} else if (uri.endsWith("sendReplyToDebugService")) {
				
				try{
					JSONObject jsonReq = new JSONObject(req.body());
					this.browserInterface.sendReplyToDebugService(jsonReq.getInt("arg0"), jsonReq.getString("arg1"));
				} catch (JSONException jse) {
					Log.e("browserInterface", req.toString() + ", len: " + req.header("Content-Length"));
					Log.e("browserInterface", "sendReplyToDebugService failed. " + jse);
				}
			} else if (uri.endsWith("getQueuedMessage")) {

				JSONObject jsonReq = new JSONObject(req.body());
				this.browserInterface.getQueuedMessage(res, jsonReq.getBoolean("arg0"));
				return;
				
			} else if (uri.endsWith("pushChannel")) {

				this.browserInterface.openPushChannel(res);
				return;
				
			} else {
				res.status(204);
			}
			res.end();
		}
	}
}