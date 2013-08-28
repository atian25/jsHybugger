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
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
	private static final String VERSION = "2.0.0";
	
	private WebServer webServer;
	
	private CountDownLatch debugServerStarted = new CountDownLatch(1);
	private DebugSessionsWebSocketHandler debugSessionsHandler;
	private ConcurrentMap<String,DebugSession> debugSessions  = new ConcurrentHashMap<String, DebugSession>();
	
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
				
				debugSessionsHandler = new DebugSessionsWebSocketHandler(DebugServer.this);
				webServer = WebServers.createWebServer( debugPort)
	                .add("/", getRootHandler())
	                .add("/json/version", getVersionHandler())
	                .add("/json", getJsonHandler())
	                .add("/devtools/page/.*", debugSessionsHandler);
		
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
                if (debugSessions.size() == 1) {
                	response.header("Location", String.format(CHROME_DEVTOOLS_FRONTEND, request.header("Host"), debugSessions.values().iterator().next().getSessionId()));
                } else if (debugSessions.size() > 1) {
					InputStream is = null;
					try {
						byte[] overview = null;
						is = this.getClass().getResourceAsStream("/overview.html.txt");
						while(is.available() > 0) {
							byte[] bytes = new byte[is.available()];
							is.read(bytes);
							if (overview == null) overview = bytes;
							else {
								byte[] newBytes = new byte[overview.length + bytes.length];
								System.arraycopy(overview, 0, newBytes, 0, overview.length);
								System.arraycopy(bytes, 0, newBytes, overview.length, bytes.length);
								overview = newBytes;
							}
						}
						response.content(overview);
					} catch (IOException e) {
						e.printStackTrace();
					}
					finally {
						if(is != null)
							try {
								is.close();
							} catch (IOException e) {
								e.printStackTrace();
							}
					}
				} else {    					
					response.content("No session for debugging available.");
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
					
					for (DebugSession dbgSession : debugSessions.values()) {
						
						res.object()
							.key("id").value(dbgSession.getSessionId())
							.key("devtoolsFrontendUrl").value(String.format(CHROME_DEVTOOLS_FRONTEND, host != null ? host : "//" , dbgSession.getSessionId()))
							.key("faviconUrl").value("http://www.jshybugger.org/favicon.ico")
						    .key("thumbnailUrl").value("http://www.jshybugger.org/favicon.ico")
						    .key("url").value(dbgSession.getUrl());
						
						if (!dbgSession.isConnected()) {
							res.key("webSocketDebuggerUrl").value("ws://" + (host != null ? host : "") + "/devtools/page/" + dbgSession.getSessionId());
						}
						res.key("title").value(dbgSession.getTitle());
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
		debugSessions.put(debugSession.getSessionId(),debugSession);
	}

	public void unExportSession(DebugSession debugSession) {
		debugSessions.remove(debugSession.getSessionId());
		debugSession.stop();
	}

	public DebugSession getDebugSession(String id) {
		return debugSessions.get(id);
	}

	public DebugSession[] getDebugSessions() {
		return debugSessions.values().toArray(
				new DebugSession[debugSessions.size()]);
	}		 
		 
	public void addHandler(String path, HttpHandler handler) throws InterruptedException {
		debugServerStarted.await();
		webServer.add(path, handler);
	}

	public void stop() {
		for (DebugSession debugSession : debugSessions.values()) {
			debugSession.stop();
		}
		debugSessions.clear();
		
		if (webServer != null) {
			webServer.stop();
		}
	}
}
