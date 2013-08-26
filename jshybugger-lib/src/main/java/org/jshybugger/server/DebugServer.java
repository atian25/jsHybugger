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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.json.JSONException;
import org.json.JSONStringer;
import org.webbitserver.HttpControl;
import org.webbitserver.HttpHandler;
import org.webbitserver.HttpRequest;
import org.webbitserver.HttpResponse;
import org.webbitserver.WebServer;
import org.webbitserver.WebServers;

import android.util.Log;


/**
 * The DebugServer is the heart of the whole system. 
 * It's the mediator between the app webview and the debugging frontend.
 */
public class DebugServer {

	private static final String TAG = "DebugServer";
	private static final String CHROME_DEVTOOLS_FRONTEND = "https://chrome-devtools-frontend.appspot.com/static/30.0.1549.0/devtools.html?ws=%s/devtools/page/%s";
	private static final String VERSION = "2.0.0_EAP";
	
	private WebServer webServer;
	
	private CountDownLatch debugServerStarted = new CountDownLatch(1);
	private List<DebugSession> debugSessions  = new ArrayList<DebugSession>();
	
	/**
	 * Instantiates a new debug server.
	 * @param debugPort the tcp listen port number
	 * @param context application context
	 * @param productName product identifier
	 * @param application the application context
	 * @throws IOException 
	 */
	public DebugServer(final int debugPort) throws IOException {
		
		Thread webServerThread = new Thread(new Runnable() {

			@Override
			public void run() {
				
				webServer = WebServers.createWebServer( debugPort)
	                .add("/", getRootHandler())
	                .add("/json/version", getVersionHandler())
	                .add("/json", getJsonHandler());
		
		        webServer.connectionExceptionHandler(new Thread.UncaughtExceptionHandler() {
					@Override
					public void uncaughtException(Thread t, Throwable e) {
						Log.e(TAG, "Debug server terminated unexpected", e);
					}
				});
				Log.i(TAG, "starting debug server on port: " + debugPort);
		        webServer.start();
		        
		        debugServerStarted.countDown();
			}

		});
		
		webServerThread.start();
		webServerThread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
			@Override
			public void uncaughtException(Thread t, Throwable e) {
				Log.e(TAG, "Bootstraping debug server terminated unexpected", e);
			}
		});
	}

	private HttpHandler getRootHandler() {
		return new HttpHandler() {
			
            @Override
            public void handleHttpRequest(HttpRequest request, HttpResponse response, HttpControl control) {
                response.status(301).header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
                    if (!debugSessions.isEmpty()) {
                    	response.header("Location", String.format(CHROME_DEVTOOLS_FRONTEND, request.header("Host"), debugSessions.get(0).getSessionId()));
                    }
                response.end();
            }
        };		
	}
	
	private HttpHandler getJsonHandler() {
		return new HttpHandler() {
		    @Override
		    public void handleHttpRequest(HttpRequest request, HttpResponse response, HttpControl control) {
		    	try {
		    		String host = request.header("Host");
					JSONStringer res = new JSONStringer().array();
					
					for (DebugSession dbgSession : debugSessions) {
						
						res.object()
							.key("id").value(dbgSession.getSessionId())
							.key("devtoolsFrontendUrl").value(String.format(CHROME_DEVTOOLS_FRONTEND, host != null ? host : "//" , "1"))
							.key("faviconUrl").value("http://www.jshybugger.org/favicon.ico")
						    .key("thumbnailUrl").value("/thumb/")
						    .key("url").value("content://jsHybugger.org/");
						
						if (!dbgSession.isConnected()) {
							res.key("webSocketDebuggerUrl").value("ws://" + (host != null ? host : "") + "/devtools/page/" + dbgSession.getSessionId());
						}
						res.key("title").value("jsHybugger powered debugging");
					    res.endObject();
					}
					
					res.endArray();
					
					response.header("Content-type", "application/json")
						.content(res.toString())
						.end();
					
				} catch (JSONException e) {
					e.printStackTrace();
				}
		    }
		};
	}

	private HttpHandler getVersionHandler() {
		return new HttpHandler() {
		    @Override
		    public void handleHttpRequest(HttpRequest request, HttpResponse response, HttpControl control) {
		    	try {
					String res = new JSONStringer().object()
							.key("Browser").value("jsHybugger " +VERSION)
							.key("Protocol-Version").value("1.0")
						 .endObject().toString();
					
					response.header("Content-type", "application/json")
						.content(res)
						.end();
					
				} catch (JSONException e) {
					e.printStackTrace();
				}
		    }
		};
	}
		  
	public void exportSession(DebugSession debugSession) throws InterruptedException {
		debugServerStarted.await();
		webServer.add("/devtools/page/" + debugSession.getSessionId(), debugSession);
		debugSessions.add(debugSession);
	}
	
	public void addHandler(String path, HttpHandler handler) throws InterruptedException {
		debugServerStarted.await();
		webServer.add(path, handler);
	}

	public void stop() {
		if (webServer != null) {
			webServer.stop();
		}
	}
}
